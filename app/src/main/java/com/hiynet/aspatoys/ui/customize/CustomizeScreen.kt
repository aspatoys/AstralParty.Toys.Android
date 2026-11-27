package com.hiynet.aspatoys.ui.customize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

enum class CustomizeTab(val label: String) {
    CARDS("卡牌"),
    HOME_BACKGROUND("主页背景"),
}

@Composable
fun CustomizeScreen(
    cards: CardCustomizationController,
    homeBackground: HomeBackgroundController,
    loaderVersion: Int,
    onOpenMarketMod: (String) -> Unit,
    onOpenLoader: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(CustomizeTab.CARDS) }

    val tabSelector: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CustomizeTab.entries.forEach { item ->
                FilterChip(
                    selected = tab == item,
                    onClick = { tab = item },
                    label = { Text(item.label) },
                    modifier = Modifier.testTag("customize.tab.${item.name.lowercase()}"),
                )
            }
        }
    }

    when (tab) {
        CustomizeTab.CARDS -> CardCustomizationScreen(
            controller = cards,
            tabSelector = tabSelector,
            modifier = modifier,
        )
        CustomizeTab.HOME_BACKGROUND -> HomeBackgroundScreen(
            controller = homeBackground,
            loaderVersion = loaderVersion,
            onOpenMarketMod = onOpenMarketMod,
            onOpenLoader = onOpenLoader,
            tabSelector = tabSelector,
            modifier = modifier,
        )
    }
}
