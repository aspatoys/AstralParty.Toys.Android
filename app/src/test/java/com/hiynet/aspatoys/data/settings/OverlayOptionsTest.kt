package com.hiynet.aspatoys.data.settings

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Tests OverlayOptions encode/decode protocol consistency.
 *  Directly references production source: app/src/main/java/.../data/settings/OverlayOptions.kt
 *  Validates cross-platform wire compatibility with the C# OverlayOptions.cs implementation. */
class OverlayOptionsTest {

    // ─── normalized() clamping ────────────────────────────────────────────────

    @Test fun `opacity NaN returns fallback 0_94`() {
        val o = OverlayOptions(opacity = Float.NaN).normalized()
        assertEquals(0.94f, o.opacity, 1e-5f)
    }

    @Test fun `opacity infinity returns fallback 0_94`() {
        val o = OverlayOptions(opacity = Float.POSITIVE_INFINITY).normalized()
        assertEquals(0.94f, o.opacity, 1e-5f)
    }

    @Test fun `opacity coerces below 0_25`() {
        val o = OverlayOptions(opacity = 0.1f).normalized()
        assertEquals(0.25f, o.opacity, 1e-5f)
    }

    @Test fun `opacity coerces above 1_0`() {
        val o = OverlayOptions(opacity = 1.5f).normalized()
        assertEquals(1.0f, o.opacity, 1e-5f)
    }

    @Test fun `width coerces below 0_65`() {
        val o = OverlayOptions(width = 0.1f).normalized()
        assertEquals(0.65f, o.width, 1e-5f)
    }

    @Test fun `height coerces above 1_6`() {
        val o = OverlayOptions(height = 2.0f).normalized()
        assertEquals(1.6f, o.height, 1e-5f)
    }

    // ─── Chinese group name base64 round-trip ─────────────────────────────────

    @Test fun `Chinese group name encodes and decodes correctly`() {
        val opts = OverlayOptions(groups = listOf(OverlayGroup("功能类", listOf("HideHudMod"))))
        val decoded = OverlayOptions.decode(opts.encode())
        assertEquals("功能类", decoded.groups[0].name)
    }

    // ─── Encode / Decode round-trip ───────────────────────────────────────────

    @Test fun `encode decode round-trip preserves all fields`() {
        val original = OverlayOptions(
            opacity = 0.75f, width = 1.2f, height = 0.9f,
            groups = listOf(OverlayGroup("测试组", listOf("HideHudMod", "AutoThanksMod")))
        )
        val decoded = OverlayOptions.decode(original.encode())
        assertEquals(0.75f, decoded.opacity, 1e-4f)
        assertEquals(1.2f,  decoded.width,   1e-4f)
        assertEquals(0.9f,  decoded.height,  1e-4f)
        assertEquals(1, decoded.groups.size)
        assertEquals("测试组", decoded.groups[0].name)
        assertEquals(listOf("HideHudMod", "AutoThanksMod"), decoded.groups[0].mods)
    }

    // ─── Empty groups ─────────────────────────────────────────────────────────

    @Test fun `empty groups encode and decode correctly`() {
        val opts = OverlayOptions(groups = emptyList())
        val decoded = OverlayOptions.decode(opts.encode())
        assertEquals(0, decoded.groups.size)
    }

    // ─── SpeedHackMod excluded ────────────────────────────────────────────────

    @Test fun `SpeedHackMod is stripped from groups on normalize`() {
        val group = OverlayGroup("g", listOf("SpeedHackMod", "HideHudMod"))
        val normalized = OverlayOptions(groups = listOf(group)).normalized()
        assertFalse(normalized.groups[0].mods.contains("SpeedHackMod"))
        assertTrue(normalized.groups[0].mods.contains("HideHudMod"))
    }

    // ─── Cross-group dedup case-insensitive ───────────────────────────────────

    @Test fun `cross-group dedup removes same mod in second group case-insensitive`() {
        val groups = listOf(
            OverlayGroup("A", listOf("HideHudMod")),
            OverlayGroup("B", listOf("hidehudmod", "AutoThanksMod"))
        )
        val decoded = OverlayOptions.decode(OverlayOptions(groups = groups).encode())
        // HideHudMod in group A; lowercase dup should be stripped from group B
        assertTrue(decoded.groups[0].mods.contains("HideHudMod"))
        assertFalse(decoded.groups[1].mods.any { it.equals("HideHudMod", ignoreCase = true) })
        assertTrue(decoded.groups[1].mods.contains("AutoThanksMod"))
    }

    // ─── Bad version → IllegalArgumentException ───────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `bad version throws`() {
        OverlayOptions.decode("version=2\nopacity=0.5\n")
    }

    // ─── Oversized input → IllegalArgumentException ───────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `oversized input throws`() {
        OverlayOptions.decode("x".repeat(65 * 1024))
    }

    // ─── Blank group name → "分组" ─────────────────────────────────────────────

    @Test fun `blank group name normalizes to 分组`() {
        val group = OverlayGroup("   ", listOf("HideHudMod"))
        val normalized = OverlayOptions(groups = listOf(group)).normalized()
        assertEquals("分组", normalized.groups[0].name)
    }

    // ─── Boundary values for width and height ────────────────────────────────

    @Test fun `width and height boundary values round-trip`() {
        val opts = OverlayOptions(width = 0.65f, height = 1.6f)
        val decoded = OverlayOptions.decode(opts.encode())
        assertEquals(0.65f, decoded.width,  1e-4f)
        assertEquals(1.6f,  decoded.height, 1e-4f)
    }

    // ─── Wire format: NaN opacity in raw wire → fallback ─────────────────────

    @Test fun `NaN opacity in raw wire text defaults to 0_94`() {
        val raw = "version=1\nopacity=NaN\nwidth=1\nheight=1\n"
        val decoded = OverlayOptions.decode(raw)
        // toFloatOrNull on "NaN" returns Float.NaN; isFinite check → fallback .94
        assertEquals(0.94f, decoded.opacity, 1e-4f)
    }

    // ─── Defaults match C# Defaults() ────────────────────────────────────────

    @Test fun `defaultGroups match C# defaults`() {
        val groups = OverlayOptions.defaultGroups
        assertEquals(1, groups.size)
        assertEquals("功能类", groups[0].name)
        assertEquals(listOf("HideHudMod", "AutoThanksMod", "ShopRelicPeekMod", "PveWaveForecastMod"), groups[0].mods)
    }

    // ─── Encode produces version=1 header ────────────────────────────────────

    @Test fun `encoded text starts with version=1`() {
        val enc = OverlayOptions().encode()
        assertTrue("encoded text must start with version=1", enc.trimStart().startsWith("version=1"))
    }

    // ─── Mod id validation: illegal chars stripped ────────────────────────────

    @Test fun `mod id with illegal characters is stripped`() {
        val group = OverlayGroup("g", listOf("Invalid Mod!", "ValidMod"))
        val normalized = OverlayOptions(groups = listOf(group)).normalized()
        assertFalse(normalized.groups[0].mods.any { it.contains(' ') || it.contains('!') })
        assertTrue(normalized.groups[0].mods.contains("ValidMod"))
    }

    // ─── Max 8 groups enforced ────────────────────────────────────────────────

    @Test fun `max 8 groups enforced by normalized`() {
        val groups = (1..10).map { OverlayGroup("g$it", listOf("Mod$it")) }
        val normalized = OverlayOptions(groups = groups).normalized()
        assertEquals(8, normalized.groups.size)
    }
}
