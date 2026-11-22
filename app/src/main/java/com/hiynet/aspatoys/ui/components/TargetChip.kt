package com.hiynet.aspatoys.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * "现在正在改哪个包"的标记：挂在页面标题下面。
 *
 * 只会装一个游戏，所以最怕的是切错渠道改到别人的缓存上；[knownChannel] 为 false 时用警示色，
 * 表示这个包名不在加载器已知的渠道规则里（不一定不是游戏，只是更依赖指纹匹配）。
 *
 * 应用名和包名都可能很长（渠道服后缀一堆），所以这里**固定两行**：第一行是应用名 + 徽标 + 切换，
 * 应用名占剩余宽度并省略号；第二行单独放包名。挤在一行会直接顶出控件边界。
 *
 * [onClick] 传了就是可点的，点开切换目标。
 */
@Composable
fun TargetChip(
    label: String,
    packageName: String,
    knownChannel: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (knownChannel) scheme.surfaceContainerHigh else scheme.errorContainer
    val content = if (knownChannel) scheme.onSurfaceVariant else scheme.onErrorContainer
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = container,
        border = BorderStroke(1.dp, content.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier
                .clip(shape)
                .clickable(enabled = onClick != null) { onClick?.invoke() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "正在管理",
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.75f),
                    maxLines = 1,
                )
                Text(
                    label.ifBlank { packageName },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!knownChannel) {
                    Text(
                        "未收录渠道",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = content,
                        maxLines = 1,
                    )
                }
                if (onClick != null) {
                    Text(
                        "切换",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.primary,
                        maxLines = 1,
                    )
                }
            }
            Text(
                packageName,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = content.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
