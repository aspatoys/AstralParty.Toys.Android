package com.hiynet.aspatoys.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.hiynet.aspatoys.R
import com.hiynet.aspatoys.data.AppContainer
import com.hiynet.aspatoys.installer.LoaderInstaller
import com.hiynet.aspatoys.ui.loader.LoaderScreen
import com.hiynet.aspatoys.ui.market.MarketController
import com.hiynet.aspatoys.ui.market.MarketScreen
import com.hiynet.aspatoys.ui.mods.ModsController
import com.hiynet.aspatoys.ui.mods.ModsScreen
import com.hiynet.aspatoys.ui.settings.SettingsScreen

/** 四个顶层页面：模组、市场、加载器、设置。 */
enum class Destination(val label: String, val icon: Int) {
    MODS("模组", R.drawable.ic_home),
    MARKET("市场", R.drawable.ic_favorite),
    LOADER("加载器", R.drawable.ic_extension),
    SETTINGS("设置", R.drawable.ic_account_box),
}

/**
 * App 外壳：装配依赖、持有页面状态、按底部导航切换页面。
 *
 * 状态放在这里而不是各页面内部：切页不重建控制器，市场列表与下载状态不会丢。
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == destination,
                        onClick = { destination = item },
                        icon = { Icon(painterResource(item.icon), contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (destination) {
                Destination.MODS -> ModsScreen(
                    controller = mods,
                    onOpenSettings = { destination = Destination.SETTINGS },
                )

                Destination.MARKET -> MarketScreen(controller = market)

                Destination.LOADER -> LoaderScreen(
                    installer = installer,
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
