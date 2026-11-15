package com.hiynet.aspatoys.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiynet.aspatoys.R
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.data.settings.BackgroundImages

/** Desktop motion.css timing. Compose animations still honor the system animator scale. */
object ToysMotion {
    const val Fast = 120
    const val Enter = 220
    const val Exit = 140
    val Easing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)
}

/** A static, GPU-blurred image: scrolling and tab changes never restart a background animation. */
@Composable
fun ToysBackdrop(settings: AppSettings, modifier: Modifier = Modifier) {
    val imageName by rememberBackgroundImage(settings)
    val context = LocalContext.current
    val images = remember(context, settings) { BackgroundImages(context, settings) }
    val customImage by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, imageName) {
        value = runCatching { images.load(imageName)?.asImageBitmap() }.getOrNull()
    }
    val base = MaterialTheme.colorScheme.background
    val dark = base.luminance() < 0.5f

    Box(modifier = modifier.fillMaxSize().background(base)) {
        val imported = customImage
        if (imported != null && imageName.isNotEmpty()) {
            val imageModifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f }
                .blur(24.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            Image(
                bitmap = imported,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        if (dark) listOf(base.copy(alpha = 0.50f), base.copy(alpha = 0.72f), base.copy(alpha = 0.94f))
                        else listOf(base.copy(alpha = 0.83f), base.copy(alpha = 0.90f), base.copy(alpha = 0.97f)),
                    ),
                ),
            )
        }
    }
}

@Composable
fun ToysBrandHeader(page: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.background.copy(alpha = 0.86f))
            .borderBottom(scheme.outlineVariant.copy(alpha = 0.46f))
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painterResource(R.drawable.toys_brand_mark),
            contentDescription = null,
            modifier = Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)),
        )
        Text(
            "ASTRAL PARTY TOYS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(page, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Cosmetic only: leaves click handling, hit targets and accessibility semantics with the caller. */
@Composable
fun Modifier.toysPressFeedback(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(ToysMotion.Fast, easing = ToysMotion.Easing),
        label = "toys-press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

fun Modifier.borderBottom(color: Color): Modifier = drawWithCache {
    onDrawWithContent {
        drawContent()
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
    }
}

/** Same outlined, translucent panels used by the desktop version, without clipping control hit areas. */
@Composable
fun Modifier.toysPanel(corner: Int = 20): Modifier = this
    .clip(RoundedCornerShape(corner.dp))
    .background(MaterialTheme.colorScheme.surfaceContainerLow)
    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f), RoundedCornerShape(corner.dp))

/** Shared page hierarchy and spacing, following the reference installer's grouped layout. */
@Composable
fun ToysPageHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ToysSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().toysPanel().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}
