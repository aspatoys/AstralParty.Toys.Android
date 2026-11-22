package com.hiynet.aspatoys.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.data.updates.UpdateProgressListener
import com.hiynet.aspatoys.ui.format.Formats
import com.hiynet.aspatoys.ui.theme.ToysMotion
import kotlinx.coroutines.launch

/**
 * 多步骤操作的统一进度（装 / 卸加载器、市场下载安装……）。
 *
 * 页面调用 [start] 拿到一个 [Operation]，把底层的 `log` 回调接到 [Operation.log]，
 * 结束时调用 [Operation.succeed] / [Operation.fail]。界面由挂在 App 外壳上的
 * [OperationHost] 统一画成底部面板：
 *
 * - 进行中显示最新一行日志（底层没有结构化步骤，最新一行就是"现在在做什么"）；
 *   有真实进度时才显示确定进度条，不编步数。
 * - 结束后**不自动关闭**，结果和日志留给用户看、可以复制，点「完成」才关。
 * - 状态放在进程级单例里，切页签不会丢。
 *
 * 所有修改都 post 到主线程，保证日志行和结束状态按调用顺序生效
 * （底层的 `log` 回调跑在 IO 线程上）。
 */
object Operations {

    enum class Outcome { Running, Succeeded, Failed, Cancelled }

    private val main = Handler(Looper.getMainLooper())

    /** 日志行数上限：一次安装可能打几百行，全留着会让面板越来越卡。 */
    private const val MaxLogLines = 300

    /** 行尾计数「前缀 i / n」。 */
    private val Counter = Regex("""^(.*?)\s*(\d+)\s*/\s*(\d+)$""")

    @Stable
    class Operation internal constructor(
        val title: String,
        private val onCancel: (() -> Unit)?,
    ) {
        /** 进行中：现在在做什么；结束后：结果说明。 */
        var headline by mutableStateOf("正在准备…")
            private set

        /** 0..1；为 null 时显示不确定进度条。 */
        var fraction by mutableStateOf<Float?>(null)
            private set

        /** 进度条下面的小字，例如「1.2 MB / 3.4 MB」。 */
        var detail by mutableStateOf<String?>(null)
            private set

        var logs by mutableStateOf<List<String>>(emptyList())
            private set

        var outcome by mutableStateOf(Outcome.Running)
            private set

        private var cancelAllowed by mutableStateOf(onCancel != null)

        val running: Boolean get() = outcome == Outcome.Running
        val cancellable: Boolean get() = running && onCancel != null && cancelAllowed

        /**
         * 底层的一行日志：记进日志，同时作为当前状态。
         *
         * 行尾是「3 / 20」这种计数时（卡面导入、加载器更新都这样报），换算成确定进度条；
         * 同一前缀的计数行只保留最新一条，不会刷出几百行。
         */
        fun log(line: String) = post {
            if (!running) return@post
            val text = line.trim()
            if (text.isEmpty()) return@post
            val counter = Counter.matchEntire(text)
            val previous = logs.lastOrNull()?.let { Counter.matchEntire(it) }
            logs = if (counter != null && previous != null && previous.groupValues[1] == counter.groupValues[1]) {
                logs.dropLast(1) + text
            } else {
                (logs + text).takeLast(MaxLogLines)
            }
            headline = text
            fraction = counter?.let {
                val done = it.groupValues[2].toFloat()
                val total = it.groupValues[3].toFloat()
                if (total > 0f) done / total else null
            }
            detail = null
        }

        /**
         * 换一个阶段（例如「正在校验 SHA-256…」）。
         * 阶段文字也记进日志，方便事后看到经过了哪几步；进度条随阶段重置。
         */
        fun status(text: String) = post {
            if (!running || text == headline) return@post
            logs = (logs + text).takeLast(MaxLogLines)
            headline = text
            fraction = null
            detail = null
        }

        /** 只更新进度条，不记日志（下载进度一秒能回调很多次）。 */
        fun progress(value: Float?, detailText: String?) = post {
            if (!running) return@post
            fraction = value?.coerceIn(0f, 1f)
            detail = detailText
        }

        /** 进入不能中断的阶段（例如 shell 命令已经发出去了）时关掉「取消」。 */
        fun allowCancel(allowed: Boolean) = post { cancelAllowed = allowed }

        fun cancel() {
            if (cancellable) onCancel?.invoke()
        }

        fun succeed(text: String) = finish(Outcome.Succeeded, text)
        fun fail(text: String) = finish(Outcome.Failed, text)
        fun cancelled(text: String) = finish(Outcome.Cancelled, text)

        private fun finish(result: Outcome, text: String) = post {
            if (!running) return@post
            logs = (logs + "—— $text").takeLast(MaxLogLines)
            headline = text
            fraction = null
            detail = null
            outcome = result
        }

        /** 复制用的纯文本：标题、结果、全部日志。 */
        fun transcript(): String = buildString {
            appendLine(title)
            logs.forEach { appendLine(it) }
        }.trimEnd()

        /** 结束时的震动只响一次（旋转屏幕、展开收起都会重新组合界面）。只在主线程读写。 */
        internal var announced = false
    }

    var current by mutableStateOf<Operation?>(null)
        private set

    /** 面板收起成了悬浮导航上方的小胶囊；操作照常进行。 */
    var collapsed by mutableStateOf(false)
        private set

    val busy: Boolean get() = current?.running == true

    /**
     * 开始一个操作并打开进度面板。上一个操作已经结束、但用户还没关面板时，直接换成新的。
     *
     * 上一个操作还没结束时返回 null（调用方什么都不做），并把那个操作的面板重新展开：
     * 用户能看到是什么在占着，不会以为按钮没反应（Win 版这时会提示「上一个操作还在处理」）。
     *
     * 只能在主线程调用。
     */
    fun start(title: String, onCancel: (() -> Unit)? = null): Operation? {
        if (busy) {
            collapsed = false
            return null
        }
        collapsed = false
        return Operation(title, onCancel).also { current = it }
    }

    /** 收起成胶囊（只有进行中的才收起；已经结束的直接关掉）。 */
    fun collapse() {
        if (busy) collapsed = true else dismiss()
    }

    /** 点胶囊：重新展开面板。 */
    fun expand() {
        collapsed = false
    }

    /** 用户看完结果、关掉面板。进行中的操作只能收起，不能关。 */
    fun dismiss() {
        if (busy) return
        current = null
        collapsed = false
    }

    /**
     * 把 `Updates` 里的下载类任务（加载器导入 / 更新、软件更新下载）接到进度面板上。
     *
     * [launch] 返回 false（没有开始）时直接把面板标成失败，免得面板一直转圈；[notStarted] 是那时显示的原因。
     * 下载阶段可以取消；下载结束、开始校验或写文件后，面板就不再显示「取消」。
     */
    fun runUpdateTask(
        title: String,
        onCancel: (() -> Unit)? = null,
        notStarted: String = "没有开始：另一个加载器操作还在进行，或者还没检查到可用版本。",
        launch: (UpdateProgressListener) -> Boolean,
    ) {
        val operation = start(title, onCancel) ?: return
        val listener = object : UpdateProgressListener {
            private var downloaded = false
            private var lastPercent = -1

            override fun step(text: String) {
                if (downloaded) operation.allowCancel(false)
                operation.log(text)
            }

            override fun download(received: Long, total: Long) {
                downloaded = true
                val percent = if (total > 0) (received * 100 / total).toInt() else null
                // 按百分比节流：底层每读一块都会回调。
                val bucket = percent ?: (received / (256 * 1024)).toInt()
                if (bucket == lastPercent) return
                lastPercent = bucket
                operation.progress(
                    percent?.let { it / 100f },
                    Formats.progressText(received, total.takeIf { it > 0 }, percent),
                )
            }

            override fun finished(success: Boolean, text: String) {
                if (success) operation.succeed(text) else operation.fail(text)
            }
        }
        if (!launch(listener)) operation.fail(notStarted)
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }
}

/**
 * 进度面板 + 收起后的胶囊。挂在 App 外壳最外层，任何页面发起的操作都画在这里。
 *
 * - 进行中下拉、点外面、按返回：收起成悬浮导航上方的胶囊，操作照常进行；点胶囊再展开。
 *   面板从底部来、往底部去，胶囊也停在底部——进出走同一条路，用户知道它去了哪。
 * - 结束后这些手势等于「完成」。收起时结束的，胶囊变成 ✓ / ! 留着，等用户点开看结果，不会自己消失。
 * - 结束时震一下：成功轻、失败重；过程中不震。
 *
 * [pillBottomPadding]：胶囊离底边的距离（竖屏要让开悬浮导航栏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.OperationHost(pillBottomPadding: Dp) {
    val operation = Operations.current
    val reducedMotion = rememberReducedMotion()

    val haptics = LocalHapticFeedback.current
    if (operation != null) {
        LaunchedEffect(operation, operation.outcome) {
            if (operation.running || operation.announced) return@LaunchedEffect
            operation.announced = true
            when (operation.outcome) {
                Operations.Outcome.Succeeded -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                Operations.Outcome.Failed -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
                else -> Unit
            }
        }
    }

    AnimatedVisibility(
        visible = operation != null && Operations.collapsed,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 24.dp, end = 24.dp, bottom = pillBottomPadding),
        // 不回弹的弹簧（阻尼 1）：从底部升起，跟面板收下去的方向一致。
        enter = if (reducedMotion) fadeIn(snap()) else
            slideInVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(ToysMotion.Enter)),
        exit = if (reducedMotion) fadeOut(snap()) else
            slideOutVertically(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)) { it } + fadeOut(tween(ToysMotion.Exit)),
    ) {
        // 退场动画期间 current 可能已经变成 null，用最后一次的值画完（普通容器，不是 State，不会引起重组）。
        val last = remember { arrayOfNulls<Operations.Operation>(1) }
        if (operation != null) last[0] = operation
        last[0]?.let { OperationPill(it, onClick = Operations::expand) }
    }

    if (operation == null || Operations.collapsed) return
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }
    ModalBottomSheet(
        // 下拉 / 点外面 / 返回：进行中收起成胶囊，结束后等于「完成」。
        onDismissRequest = { Operations.collapse() },
        sheetState = sheetState,
    ) {
        OperationPanel(
            operation = operation,
            reducedMotion = reducedMotion,
            onCollapse = { hideThen(Operations::collapse) },
            onClose = { hideThen(Operations::dismiss) },
        )
    }
}

/** 收起后的小胶囊：状态标记 + 标题 + 现在在做什么。点它重新展开面板。 */
@Composable
private fun OperationPill(operation: Operations.Operation, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        // 和悬浮导航栏同一种材质：半透明表面 + 细描边，看起来是一家的。
        color = scheme.surfaceContainerHigh.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                when (operation.outcome) {
                    Operations.Outcome.Running -> {
                        val fraction = operation.fraction
                        if (fraction != null) CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Operations.Outcome.Succeeded -> GlyphDot("✓", scheme.primary, scheme.onPrimary, size = 22.dp)
                    Operations.Outcome.Failed -> GlyphDot("!", scheme.error, scheme.onError, size = 22.dp)
                    Operations.Outcome.Cancelled -> GlyphDot("–", scheme.surfaceVariant, scheme.onSurfaceVariant, size = 22.dp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    operation.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    operation.headline,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (operation.outcome == Operations.Outcome.Failed) scheme.error else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (operation.running) "展开" else "查看结果",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
            )
        }
    }
}

@Composable
private fun OperationPanel(
    operation: Operations.Operation,
    reducedMotion: Boolean,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val outcome = operation.outcome
    var logsOpen by remember(operation) { mutableStateOf(false) }
    // 失败时直接把日志展开：用户要看的就是原因。
    LaunchedEffect(outcome) { if (outcome == Operations.Outcome.Failed) logsOpen = true }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutcomeGlyph(outcome, reducedMotion)
            Text(
                operation.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 只在文字真的变了时淡入淡出一次；日志刷得快，动画要短，不然文字会糊成一片。
        AnimatedContent(
            targetState = operation.headline,
            transitionSpec = {
                if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(tween(ToysMotion.Fast)) togetherWith fadeOut(tween(ToysMotion.Fast))
            },
            label = "operation.headline",
        ) { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = when (outcome) {
                    Operations.Outcome.Failed -> scheme.error
                    Operations.Outcome.Running -> scheme.onSurfaceVariant
                    else -> scheme.onSurface
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AnimatedVisibility(visible = outcome == Operations.Outcome.Running, exit = fadeOut(tween(ToysMotion.Exit))) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val fraction = operation.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                operation.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }

        if (operation.logs.isNotEmpty()) {
            LogSection(lines = operation.logs, open = logsOpen, reducedMotion = reducedMotion, onToggle = { logsOpen = !logsOpen })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (outcome == Operations.Outcome.Running) {
                if (operation.cancellable) {
                    TextButton(onClick = operation::cancel) { Text("取消") }
                }
                // 不想干等：收起成胶囊，操作照常进行（下拉面板也是一样的效果）。
                OutlinedButton(onClick = onCollapse) { Text("收起到后台") }
            } else {
                val context = LocalContext.current
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(operation.title, operation.transcript()))
                    // Android 13 起系统自己会提示「已复制」，旧版本补一句。
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toasts.show(context, "日志已复制")
                }) { Text("复制日志") }
                Button(onClick = onClose) { Text("完成") }
            }
        }
    }
}

/** 左上角的状态标记：转圈 → 对勾 / 叹号。换状态时只缩放进来一次，不做持续动画。 */
@Composable
private fun OutcomeGlyph(outcome: Operations.Outcome, reducedMotion: Boolean) {
    val scheme = MaterialTheme.colorScheme
    AnimatedContent(
        targetState = outcome,
        transitionSpec = {
            if (reducedMotion) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
            (scaleIn(spring(dampingRatio = 1f, stiffness = 600f), initialScale = 0.8f) + fadeIn(tween(ToysMotion.Fast))) togetherWith
                fadeOut(tween(ToysMotion.Fast))
        },
        label = "operation.glyph",
    ) { state ->
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (state) {
                Operations.Outcome.Running -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Operations.Outcome.Succeeded -> GlyphDot("✓", scheme.primary, scheme.onPrimary)
                Operations.Outcome.Failed -> GlyphDot("!", scheme.error, scheme.onError)
                Operations.Outcome.Cancelled -> GlyphDot("–", scheme.surfaceVariant, scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GlyphDot(symbol: String, container: Color, content: Color, size: Dp = 26.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = content, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LogSection(lines: List<String>, open: Boolean, reducedMotion: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (if (open) "▾ " else "▸ ") + "详细日志（${lines.size} 行）",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = if (reducedMotion) EnterTransition.None
                else expandVertically(tween(ToysMotion.Enter, easing = ToysMotion.Easing)) + fadeIn(tween(ToysMotion.Enter)),
            exit = if (reducedMotion) ExitTransition.None
                else shrinkVertically(tween(ToysMotion.Exit, easing = ToysMotion.Easing)) + fadeOut(tween(ToysMotion.Exit)),
        ) {
            val listState = rememberLazyListState()
            // 新日志进来时跟到最后一行。
            LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurface,
                    )
                }
            }
        }
    }
}
