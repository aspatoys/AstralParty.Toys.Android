package com.hiynet.aspatoys.data.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkVersionsTest {
    @Test
    fun `compare follows semver ordering`() {
        assertTrue(SdkVersions.compare("2.9.1", "2.8.6") > 0)
        assertTrue(SdkVersions.compare("2.8.6", "2.9.1") < 0)
        assertEquals(0, SdkVersions.compare("3.7.5", "3.7.5"))
        assertEquals(0, SdkVersions.compare("3.7", "3.7.0"))
        // 解析不出的段按 0：与加载器一致
        assertEquals(0, SdkVersions.compare("", "0.0.0"))
    }

    @Test
    fun `incompatible only when required is higher`() {
        assertTrue(SdkVersions.isIncompatible("2.9.1", "2.8.6"))
        assertFalse(SdkVersions.isIncompatible("2.8.6", "2.9.1"))
        assertFalse(SdkVersions.isIncompatible("2.8.6", "2.8.6"))
        assertFalse(SdkVersions.isIncompatible("", "1.0.0"))
        assertFalse(SdkVersions.isIncompatible("2.0.0", ""))
        assertFalse(SdkVersions.isIncompatible(null, null))
    }

    @Test
    fun `normalize keeps three numeric segments`() {
        assertEquals("3.2.0", SdkVersions.normalize("3.2.0.0"))
        assertEquals("3.2", SdkVersions.normalize("3.2"))
        assertEquals("beta", SdkVersions.normalize("beta"))
        assertEquals("", SdkVersions.normalize(null))
    }

    @Test
    fun `describe mismatch mentions both versions`() {
        val text = SdkVersions.describeMismatch("CombatOddsMod", "2.9.1", "2.8.6")
        assertTrue(text.contains("CombatOddsMod"))
        assertTrue(text.contains("2.9.1"))
        assertTrue(text.contains("2.8.6"))
    }
}
