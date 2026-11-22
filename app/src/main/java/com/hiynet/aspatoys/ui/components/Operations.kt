package com.hiynet.aspatoys.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material3.SheetValue
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

        /** 底层的一行日志：记进日志，同时作为当前状态。 */
        fun log(line: String) = post {
            if (!running) return@post
            val text = line.trim()
            if (text.isEmpty()) return@post
            logs = (logs + text).takeLast(MaxLogLines)
            headline = text
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
    }

    var current by mutableStateOf<Operation?>(null)
        private set

    val busy: Boolean get() = current?.running == true

    /**
     * 开始一个操作并打开进度面板。上一个操作还没结束时返回 null（调用方什么都不做）；
     * 上一个已经结束、但用户还没关面板时，直接换成新的。
     *
     * 只能在主线程调用。
     */
    fun start(title: String, onCancel: (() -> Unit)? = null): Operation? {
        if (busy) return null
        return Operation(title, onCancel).also { current = it }
    }

    /** 用户关掉面板。进行中的操作不允许关。 */
    fun dismiss() {
        if (!busy) current = null
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }
}

/**
 * 进度面板。挂在 App 外壳最外层，任何页面发起的操作都画在这里。
 *
 * 进行中不能下拉、点外面或按返回关闭（和原来市场下载弹窗一致），结束后这些手势都等于「完成」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationHost() {
    val operation = Operations.current ?: return
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // 读的是全局状态而不是捕获的 operation：结束之后同一个面板就能被关掉。
        confirmValueChange = { it != SheetValue.Hidden || !Operations.busy },
    )
    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { Operations.dismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = { Operations.dismiss() },
        sheetState = sheetState,
    ) {
        OperationPanel(operation = operation, onClose = ::close)
    }
}

@Composable
private fun OperationPanel(operation: Operations.Operation, onClose: () -> Unit) {
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
            OutcomeGlyph(outcome)
            Text(
                operation.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // 只在文字真的变了时淡入淡出一次；日志刷得快，动画要短，不然文字会糊成一片。
        AnimatedContent(
            targetState = operation.headline,
            transitionSpec = { fadeIn(tween(ToysMotion.Fast)) togetherWith fadeOut(tween(ToysMotion.Fast)) },
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
            LogSection(lines = operation.logs, open = logsOpen, onToggle = { logsOpen = !logsOpen })
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
private fun OutcomeGlyph(outcome: Operations.Outcome) {
    val scheme = MaterialTheme.colorScheme
    AnimatedContent(
        targetState = outcome,
        transitionSpec = {
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
private fun GlyphDot(symbol: String, container: Color, content: Color) {
    Box(
        Modifier.size(26.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = content, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LogSection(lines: List<String>, open: Boolean, onToggle: () -> Unit) {
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
            enter = expandVertically(tween(ToysMotion.Enter, easing = ToysMotion.Easing)) + fadeIn(tween(ToysMotion.Enter)),
            exit = shrinkVertically(tween(ToysMotion.Exit, easing = ToysMotion.Easing)) + fadeOut(tween(ToysMotion.Exit)),
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
