package com.hiynet.aspatoys.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 骨架屏：加载时先把版式画出来，再慢慢呼吸，比转圈更"跟手"——
 * 用户能预判内容会长什么样，也不会盯着空白屏猜是不是卡了。
 */
@Composable
private fun breathingAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    return alpha
}

/** 一块骨架矩形。 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    corner: Dp = 6.dp,
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(corner))
            .alpha(breathingAlpha())
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** 一行文字占位；[widthFraction] 是相对可用的宽度。 */
@Composable
fun SkeletonLine(widthFraction: Float = 1f, height: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(widthFraction)) {
        SkeletonBlock(modifier = Modifier.fillMaxWidth(), height = height)
    }
}

/** 已装模组卡片的骨架。 */
@Composable
fun SkeletonModCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkeletonBlock(modifier = Modifier.size(44.dp), height = 44.dp, corner = 12.dp)
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonLine(0.55f, 14.dp)
            SkeletonLine(0.8f, 10.dp)
            SkeletonLine(0.35f, 10.dp)
        }
    }
}

/** 市场列表行的骨架。 */
@Composable
fun SkeletonMarketRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkeletonBlock(modifier = Modifier.size(64.dp), height = 64.dp, corner = 12.dp)
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonLine(0.6f, 15.dp)
            SkeletonLine(0.45f, 10.dp)
            SkeletonLine(0.9f, 10.dp)
        }
    }
}

/** 若干张卡片骨架。 */
@Composable
fun SkeletonList(count: Int = 4, market: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) {
            if (market) SkeletonMarketRow() else SkeletonModCard()
        }
    }
}

/** 详情面板的骨架：封面 + 标题 + 正文。 */
@Composable
fun SkeletonDetail() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SkeletonBlock(modifier = Modifier.size(84.dp), height = 84.dp, corner = 16.dp)
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonLine(0.7f, 18.dp)
                SkeletonLine(0.45f, 11.dp)
                Spacer(Modifier.height(2.dp))
                SkeletonLine(0.3f, 11.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
        SkeletonLine(1f, 11.dp)
        SkeletonLine(0.92f, 11.dp)
        SkeletonLine(0.6f, 11.dp)
        Spacer(Modifier.height(8.dp))
        repeat(2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.width(120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonLine(0.7f, 13.dp)
                    SkeletonLine(0.9f, 10.dp)
                }
                Spacer(Modifier.weight(1f))
                SkeletonBlock(modifier = Modifier.width(96.dp), height = 36.dp, corner = 10.dp)
            }
        }
    }
}
