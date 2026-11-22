package com.hiynet.aspatoys.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.data.apps.InstalledAppRepository
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.installer.LoaderInstaller
import com.hiynet.aspatoys.installer.RootShell
import com.hiynet.aspatoys.installer.ShizukuShell
import com.hiynet.aspatoys.ui.components.EmphasisButton
import com.hiynet.aspatoys.ui.components.Operations
import com.hiynet.aspatoys.ui.components.Snackbars
import com.hiynet.aspatoys.ui.components.TargetChip
import com.hiynet.aspatoys.ui.loader.TargetAppList
import com.hiynet.cesiumloader.core.AndroidPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class OnboardingStep(val label: String) {
    Welcome("开始之前"),
    Target("选择目标游戏"),
    Privilege("授权访问"),
    Install("安装加载器"),
    Done("完成"),
}

/**
 * 首次使用引导：没装过加载器时，一步一步把人带到"选好目标游戏 + 装好加载器"。
 *
 * 这一步只允许选**一个**目标（安卓版一次只管理一个游戏），之后随时能在加载器页或设置里换。
 * 任何一步都能退出：退出会把 [AppSettings.onboardingCompleted] 标上，不反复弹。
 */
@Composable
fun LoaderOnboarding(
    settings: AppSettings,
    onModeChanged: (String) -> Unit,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { InstalledAppRepository(context.applicationContext) }
    val permissionVersion = ShizukuShell.permissionVersion

    var step by remember { mutableStateOf(OnboardingStep.Welcome) }
    var gamePackage by remember { mutableStateOf(settings.gamePackage) }
    val targetLabel = remember(gamePackage) { InstalledAppRepository.appLabel(context, gamePackage) }
    var mode by remember { mutableStateOf(settings.privilegeMode) }
    var granted by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }

    fun finish() {
        settings.onboardingCompleted = true
        onFinished()
    }

    fun select(next: String) {
        settings.gamePackage = next
        gamePackage = next
    }

    LaunchedEffect(permissionVersion, mode) {
        granted = withContext(Dispatchers.IO) {
            runCatching {
                if (mode == AppSettings.PrivilegeRoot) RootShell.isAvailable()
                else ShizukuShell.isServiceAlive() && ShizukuShell.hasPermission()
            }.getOrDefault(false)
        }
    }

    // 进到"安装"这一步时先看一眼：已经装过就直接让人往下走，不用重装一遍。
    LaunchedEffect(step, permissionVersion) {
        if (step != OnboardingStep.Install) return@LaunchedEffect
        checking = true
        installed = withContext(Dispatchers.IO) {
            runCatching { LoaderInstaller(context.applicationContext, gamePackage).status().loaderPayload }
                .getOrDefault(false)
        }
        checking = false
    }

    fun install() {
        if (installing || checking) return
        val operation = Operations.start("安装内置加载器 → $targetLabel") ?: return
        installing = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    LoaderInstaller(context.applicationContext, gamePackage).install { line -> operation.log(line) }
                }
                installed = result.loaderPayload
                settings.onboardingCompleted = true
                operation.succeed("已安装到 $targetLabel。完全退出并重新启动游戏后生效。")
                step = OnboardingStep.Done
            } catch (error: Throwable) {
                operation.fail("没有完成：" + (error.message ?: error.toString()))
            } finally {
                installing = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("首次使用", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(step.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (step) {
                OnboardingStep.Welcome -> WelcomeStep()

                OnboardingStep.Target -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "加载器要装进游戏自己的私有目录，所以先确认改的是哪个游戏。这一步只选一个，之后可以随时切换。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TargetChip(
                        label = targetLabel,
                        packageName = gamePackage,
                        knownChannel = AndroidPaths.isGamePackage(gamePackage),
                    )
                    TargetAppList(
                        repository = repository,
                        currentPackage = gamePackage,
                        onSelect = { select(it) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }

                OnboardingStep.Privilege -> PrivilegeStep(
                    mode = mode,
                    granted = granted,
                    onModeChanged = { next ->
                        mode = next
                        settings.privilegeMode = next
                        ShizukuShell.setMode(next)
                        onModeChanged(next)
                    },
                    onGrantShizuku = {
                        // 服务没起来时 requestPermission() 什么都不做，要给个提示。
                        if (ShizukuShell.isServiceAlive()) ShizukuShell.requestPermission()
                        else Snackbars.show("Shizuku 没在运行：先打开 Shizuku App 启动服务，再回来点授权。")
                    },
                    onRecheckRoot = { RootShell.reset() },
                )

                OnboardingStep.Install -> InstallStep(
                    targetLabel = targetLabel,
                    gamePackage = gamePackage,
                    checking = checking,
                    installed = installed,
                    installing = installing,
                    onInstall = { install() },
                )

                OnboardingStep.Done -> DoneStep(targetLabel = targetLabel)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (step) {
                OnboardingStep.Welcome -> {
                    EmphasisButton(primary = true, onClick = { step = OnboardingStep.Target }) { Text("开始") }
                    TextButton(onClick = { finish() }) { Text("跳过引导") }
                }

                OnboardingStep.Target -> {
                    EmphasisButton(primary = true, onClick = { step = OnboardingStep.Privilege }) { Text("下一步") }
                    TextButton(onClick = { step = OnboardingStep.Welcome }) { Text("上一步") }
                }

                OnboardingStep.Privilege -> {
                    EmphasisButton(primary = true, onClick = { step = OnboardingStep.Install }) { Text("下一步") }
                    TextButton(onClick = { step = OnboardingStep.Target }) { Text("上一步") }
                }

                OnboardingStep.Install -> {
                    if (installed) {
                        EmphasisButton(primary = true, onClick = { step = OnboardingStep.Done }) { Text("已安装，下一步") }
                    }
                    TextButton(onClick = { step = OnboardingStep.Privilege }) { Text("上一步") }
                }

                OnboardingStep.Done -> {
                    EmphasisButton(primary = true, onClick = { finish() }) { Text("进入应用") }
                }
            }
        }
        TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.Start)) {
            Text(if (step == OnboardingStep.Done) "稍后重启游戏" else "稍后再说")
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "安卓版的 CesiumLoader 装在游戏的私有目录里，需要借 Shizuku 或 Root 身份才能写入。",
            style = MaterialTheme.typography.bodyMedium,
        )
        StepLine("1", "选一个目标游戏（渠道服也可以）")
        StepLine("2", "授权 Shizuku 或 Root")
        StepLine("3", "安装内置加载器，然后完全退出并重启游戏")
        Text(
            "同一时间只管理一个游戏。装完之后可以在「加载器」页或设置里换目标，各渠道服的模组和配置互不影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepLine(index: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    index,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PrivilegeStep(
    mode: String,
    granted: Boolean,
    onModeChanged: (String) -> Unit,
    onGrantShizuku: () -> Unit,
    onRecheckRoot: () -> Unit,
) {
    val root = mode == AppSettings.PrivilegeRoot
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "写游戏私有目录只能借设备上的高权限身份，两种选一个：",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EmphasisButton(primary = !root, onClick = { onModeChanged(AppSettings.PrivilegeShizuku) }) { Text("Shizuku") }
            EmphasisButton(primary = root, onClick = { onModeChanged(AppSettings.PrivilegeRoot) }) { Text("Root") }
        }
        Text(
            if (root) "Root 模式直接用 su，不需要 Shizuku 服务。" else "需要先安装并启动 Shizuku，再回到这里授权。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EmphasisButton(
            primary = !granted,
            onClick = { if (root) onRecheckRoot() else onGrantShizuku() },
        ) { Text(if (root) "重新检测 Root" else "授权 Shizuku") }
        Text(
            if (granted) "已就绪。" else "还没有可用的提权：可以先往下走，但装不了加载器。",
            style = MaterialTheme.typography.bodyMedium,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun InstallStep(
    targetLabel: String,
    gamePackage: String,
    checking: Boolean,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("安装目标", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "$targetLabel（$gamePackage）",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "会写入托管 SDK、官方模组和引导程序，并把注入补丁写进游戏的热更缓存。已有的用户配置保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            checking -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text("正在检查这台设备上的加载器状态…", style = MaterialTheme.typography.bodySmall)
            }

            installed -> Text(
                "这个包名下已经有加载器。要重新装一遍也可以。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        EmphasisButton(primary = true, enabled = !installing && !checking, onClick = onInstall) {
            Text(if (installed) "重新安装" else "安装内置加载器")
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "安装前请先完全退出游戏。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneStep(targetLabel: String) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("已完成", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "$targetLabel 的加载器已就位。完全退出并重新启动游戏后生效，游戏内会出现悬浮窗。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "之后可以在「模组」页管理模组和配置，在「加载器」页切换目标游戏或更新加载器。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
