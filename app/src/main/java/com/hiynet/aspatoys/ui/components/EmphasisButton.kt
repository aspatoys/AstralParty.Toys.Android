package com.hiynet.aspatoys.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/**
 * 每页只有一个主按钮（和 Win 版的规则一样）：同一个按钮在不同状态下可能是、也可能不是这一页最该点的那个。
 * [primary] 为 true 时画实心按钮，否则画线框按钮。
 */
@Composable
fun EmphasisButton(
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    content: @Composable RowScope.() -> Unit,
) {
    if (primary) {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, content = content)
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, content = content)
    }
}
