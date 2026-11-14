package com.hiynet.aspatoys.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hiynet.aspatoys.R
import com.hiynet.aspatoys.data.AppContainer
import com.hiynet.aspatoys.installer.LoaderInstaller
import com.hiynet.aspatoys.installer.ShizukuShell
import com.hiynet.aspatoys.ui.loader.LoaderScreen
import com.hiynet.aspatoys.ui.market.MarketController
import com.hiynet.aspatoys.ui.market.MarketScreen
import com.hiynet.aspatoys.ui.mods.ModsController
import com.hiynet.aspatoys.ui.mods.ModsScreen
import com.hiynet.aspatoys.ui.settings.SettingsScreen

/** 四个顶层页面：模组、市场、加载器、设置。图标统一成同一套 Material 线性字形。 */
enum class Destination(val label: String, val icon: Int) {
    MODS("模组", R.drawable.ic_nav_mods),
    MARKET("市场", R.drawable.ic_nav_market),
    LOADER("加载器", R.drawable.ic_nav_loader),
    SETTINGS("设置", R.drawable.ic_nav_settings),
}

/**
 * App 外壳：装配依赖、持有页面状态、按窗口宽度切换导航。
 *
 * 手机竖屏用底部导航；平板/横屏（>= 900dp）改用左侧 NavigationRail，
 * 让内容拿到完整高度，市场页也能左右分栏。
 */
@Composable
fun ToysApp() {
    val appContext = LocalContext.current.applicationContext
    val container = remember { AppContainer(appContext) }
    val scope = rememberCoroutineScope()
    val market = remember { MarketController(container, scope) }
    val mods = remember { ModsController(container, scope, appContext) }
    val installer = remember { LoaderInstaller(appContext) }

    var destination by rememberSaveable { mutableStateOf(Destination.MODS) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp

        if (wide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                NavigationRail {
                    Spacer(Modifier.height(12.dp))
                    Destination.entries.forEach { item ->
                        NavigationRailItem(
                            selected = item == destination,
                            onClick = { if (destination != item) destination = item },
                            icon = { Icon(painterResource(item.icon), contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (destination == Destination.MARKET) {
                        DestinationContent(
                            destination = destination,
                            market = market,
                            mods = mods,
                            installer = installer,
                            container = container,
                            onOpenSettings = { destination = Destination.SETTINGS },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            DestinationContent(
                                destination = destination,
                                market = market,
                                mods = mods,
                                installer = installer,
                                container = container,
                                onOpenSettings = { destination = Destination.SETTINGS },
                                modifier = Modifier
                                    .widthIn(max = 980.dp)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    NavigationBar {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = item == destination,
                                onClick = { if (destination != item) destination = item },
                                icon = { Icon(painterResource(item.icon), contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                DestinationContent(
                    destination = destination,
                    market = market,
                    mods = mods,
                    installer = installer,
                    container = container,
                    onOpenSettings = { destination = Destination.SETTINGS },
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            }
        }
    }
}

@Composable
private fun DestinationContent(
    destination: Destination,
    market: MarketController,
    mods: ModsController,
    installer: LoaderInstaller,
    container: AppContainer,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier) {
        AnimatedContent(
            targetState = destination,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val slide = if (forward) {
                    AnimatedContentTransitionScope.SlideDirection.Left
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Right
                }
                (slideIntoContainer(slide, animationSpec = tween(180)) + fadeIn(tween(180))) togetherWith
                    (slideOutOfContainer(slide, animationSpec = tween(180)) + fadeOut(tween(120)))
            },
            label = "destination",
        ) { target ->
            when (target) {
                Destination.MODS -> ModsScreen(controller = mods)

                Destination.MARKET -> MarketScreen(controller = market)

                Destination.LOADER -> LoaderScreen(
                    installer = installer,
                    mods = mods,
                    settings = container.settings,
                    onModeChanged = { mode ->
                        container.settings.privilegeMode = mode
                        ShizukuShell.setMode(mode)
                    },
                    onOpenSettings = onOpenSettings,
                    onLoaderChanged = { container.loader.invalidate() },
                )

                Destination.SETTINGS -> SettingsScreen(
                    settings = container.settings,
                    onChanged = {
                        market.reloadSettings()
                        mods.reloadWorkspace()
                    },
                    onReviewConsent = { market.reviewConsent() },
                )
            }
        }
    }
}
