package com.hiynet.aspatoys.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import android.content.res.Configuration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.hiynet.aspatoys.R
import com.hiynet.aspatoys.data.AppContainer
import com.hiynet.aspatoys.installer.LoaderInstaller
import com.hiynet.aspatoys.installer.ShizukuShell
import com.hiynet.aspatoys.ui.loader.LoaderScreen
import com.hiynet.aspatoys.ui.market.MarketController
import com.hiynet.aspatoys.ui.market.MarketScreen
import com.hiynet.aspatoys.ui.mods.ModsController
import com.hiynet.aspatoys.ui.mods.ModsScreen
import com.hiynet.aspatoys.ui.customize.CardCustomizationController
import com.hiynet.aspatoys.ui.customize.CardCustomizationScreen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.hiynet.aspatoys.ui.settings.SettingsScreen
import com.hiynet.aspatoys.ui.theme.ToysBackdrop
import com.hiynet.aspatoys.ui.theme.ToysMotion
import com.hiynet.aspatoys.ui.theme.toysPressFeedback
import com.hiynet.aspatoys.ui.theme.OverlayAppearanceSync

/** 五个顶层页面：模组、市场、自定义、加载器、设置。图标统一成同一套 Material 线性字形。 */
enum class Destination(val label: String, val icon: Int) {
    MODS("模组", R.drawable.ic_nav_mods),
    MARKET("市场", R.drawable.ic_nav_market),
    CUSTOMIZE("自定义", R.drawable.ic_nav_customize),
    LOADER("加载器", R.drawable.ic_nav_loader),
    SETTINGS("设置", R.drawable.ic_nav_settings),
}

/**
 * App 外壳：装配依赖、持有页面状态、按窗口宽度切换导航。
 *
 * 竖屏用悬浮 Dock；横屏改用左侧 NavigationRail，
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
    val cards = remember { CardCustomizationController(container, scope, appContext) }
    OverlayAppearanceSync(container.settings)

    var destination by rememberSaveable { mutableStateOf(Destination.MODS) }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            ToysBackdrop(settings = container.settings)
            Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val wide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                if (wide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                            NavigationRail(modifier = Modifier.verticalScroll(rememberScrollState()), containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
                                Spacer(Modifier.height(12.dp))
                                Destination.entries.forEach { item ->
                                    val interactions = remember { MutableInteractionSource() }
                                    NavigationRailItem(
                                        selected = item == destination,
                                        onClick = { if (destination != item) destination = item },
                                        icon = { Icon(painterResource(item.icon), contentDescription = item.label) },
                                        label = { Text(item.label) },
                                        modifier = Modifier.toysPressFeedback(interactions),
                                        interactionSource = interactions,
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                }
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                                DestinationContent(
                                    destination = destination,
                                    market = market,
                                    mods = mods,
                                    installer = installer,
                                cards = cards,
                                    container = container,
                                    onOpenSettings = { destination = Destination.SETTINGS },
                                    modifier = if (destination == Destination.MARKET) Modifier.fillMaxSize()
                                        else Modifier.widthIn(max = 980.dp).fillMaxHeight(),
                                )
                            }
                        }
                    } else {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = Color.Transparent,
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            bottomBar = {
                                ToysDock(destination = destination, onSelect = { destination = it })
                            },
                        ) { innerPadding ->
                            DestinationContent(
                                destination = destination,
                                market = market,
                                mods = mods,
                                installer = installer,
                                cards = cards,
                                container = container,
                                onOpenSettings = { destination = Destination.SETTINGS },
                                modifier = Modifier.fillMaxSize().padding(innerPadding),
                            )
                        }
                    }
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
    cards: CardCustomizationController,
    container: AppContainer,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier) {
        AnimatedContent(
            targetState = destination,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val direction = if (forward) 1 else -1
                (slideInHorizontally(
                    animationSpec = tween(ToysMotion.Enter, easing = ToysMotion.Easing),
                    initialOffsetX = { (it * 0.025f).toInt() * direction },
                ) + fadeIn(tween(ToysMotion.Enter))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = tween(ToysMotion.Exit, easing = ToysMotion.Easing),
                        targetOffsetX = { -(it * 0.015f).toInt() * direction },
                    ) + fadeOut(tween(ToysMotion.Fast)))
            },
            label = "destination",
        ) { target ->
            when (target) {
                Destination.MODS -> ModsScreen(controller = mods)

                Destination.MARKET -> MarketScreen(controller = market)

                Destination.CUSTOMIZE -> CardCustomizationScreen(cards)

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


/** 具有半透明与 Material 3 原生胶囊指示器风格的底部悬浮导航栏。 */
@Composable
internal fun ToysDock(destination: Destination, onSelect: (Destination) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .testTag("navigation.dock")
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = scheme.surfaceContainer.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.35f)),
            shadowElevation = 0.dp,
        ) {
            Row(
                Modifier
                    .selectableGroup()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Destination.entries.forEach { item ->
                    val selected = item == destination
                    val interactions = remember { MutableInteractionSource() }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("navigation.${item.name.lowercase()}")
                            .clip(RoundedCornerShape(20.dp))
                            .toysPressFeedback(interactions)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                interactionSource = interactions,
                                indication = androidx.compose.foundation.LocalIndication.current,
                                onClick = { if (!selected) onSelect(item) },
                            )
                            .heightIn(min = 54.dp)
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    ) {
                        Box(
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .background(
                                    if (selected) scheme.secondaryContainer.copy(alpha = 0.85f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(item.icon),
                                contentDescription = null,
                                tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
