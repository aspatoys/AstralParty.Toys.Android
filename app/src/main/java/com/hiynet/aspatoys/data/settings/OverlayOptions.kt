package com.hiynet.aspatoys.data.settings

import java.util.Base64

data class OverlayGroup(val name: String, val mods: List<String> = emptyList())

/** Versioned, locale-independent wire format shared with the in-game C# overlay. */
data class OverlayOptions(
    val opacity: Float = 0.5f,
    val width: Float = 1f,
    val height: Float = 1f,
    val fontScale: Float = 1f,
    /** Vertical centre of the bar as a fraction of screen height; negative means "never moved". */
    val center: Float = -1f,
    /** Idle seconds before the floating blob fades out; 0 keeps it fully opaque. */
    val idleSeconds: Float = 5f,
    /** Alpha the idle blob fades to. */
    val idleOpacity: Float = 0.35f,
    /** Last speed above 1.0x, restored by the resume action in the speed page. */
    val previousSpeed: Float = 1f,
    val groups: List<OverlayGroup> = defaultGroups,
) {
    fun normalized(): OverlayOptions {
        val assigned = mutableSetOf<String>()
        return copy(
            opacity = opacity.takeIf { it.isFinite() }?.coerceIn(0.25f, 1f) ?: 0.5f,
            width = width.takeIf { it.isFinite() }?.coerceIn(0.65f, 1.6f) ?: 1f,
            height = height.takeIf { it.isFinite() }?.coerceIn(0.65f, 1.6f) ?: 1f,
            fontScale = fontScale.takeIf { it.isFinite() }?.coerceIn(0.8f, 1.4f) ?: 1f,
            idleSeconds = idleSeconds.takeIf { it.isFinite() }?.coerceIn(0f, 60f) ?: 5f,
            idleOpacity = idleOpacity.takeIf { it.isFinite() }?.coerceIn(0.15f, 1f) ?: 0.35f,
            previousSpeed = previousSpeed.takeIf { it.isFinite() }?.coerceIn(1f, 10f) ?: 1f,
            // Negative means "the bar was never moved"; clamping it to 0 would pin it to the top.
            center = when {
                !center.isFinite() -> -1f
                center < 0f -> -1f
                else -> center.coerceIn(0f, 1f)
            },
            groups = groups.take(8).map { group ->
                OverlayGroup(group.name.trim().take(30).ifBlank { "分组" }, group.mods.filter {
                    validId.matches(it) && it != "SpeedHackMod" && assigned.add(it.lowercase())
                }.take(128))
            },
        )
    }

    fun encode(): String = normalized().let { value -> buildString {
        appendLine("version=1")
        appendLine("opacity=${value.opacity}")
        appendLine("width=${value.width}")
        appendLine("height=${value.height}")
        appendLine("font=${value.fontScale}")
        appendLine("idle=${value.idleSeconds}")
        appendLine("idleOpacity=${value.idleOpacity}")
        appendLine("previousSpeed=${value.previousSpeed}")
        if (value.center >= 0f) appendLine("center=${value.center}")
        value.groups.forEach { group ->
            appendLine("group=${Base64.getEncoder().encodeToString(group.name.toByteArray(Charsets.UTF_8))}|${group.mods.joinToString(",")}")
        }
    } }

    companion object {
        val validId = Regex("[A-Za-z0-9_.-]{1,128}")
        val defaultGroups = listOf(OverlayGroup("功能类", listOf("HideHudMod", "AutoThanksMod", "ShopRelicPeekMod", "PveWaveForecastMod")))
        fun decode(raw: String): OverlayOptions {
            require(raw.length <= 64 * 1024) { "悬浮窗设置文件过大。" }
            val lines = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            require(lines.firstOrNull() == "version=1") { "悬浮窗设置版本不支持。" }
            fun number(key: String, fallback: Float) = lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.toFloatOrNull() ?: fallback
            val groups = lines.filter { it.startsWith("group=") }.map {
                val pair = it.substringAfter('=').split('|', limit = 2)
                require(pair.size == 2) { "悬浮窗分组格式错误。" }
                OverlayGroup(String(Base64.getDecoder().decode(pair[0]), Charsets.UTF_8), pair[1].split(',').filter(String::isNotBlank))
            }
            return OverlayOptions(
                opacity = number("opacity", 0.5f),
                width = number("width", 1f),
                height = number("height", 1f),
                fontScale = number("font", 1f),
                center = number("center", -1f),
                idleSeconds = number("idle", 5f),
                idleOpacity = number("idleOpacity", 0.35f),
                previousSpeed = number("previousSpeed", 1f),
                groups = groups,
            ).normalized()
        }
    }
}