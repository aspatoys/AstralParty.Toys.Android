package com.hiynet.cesiumloader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemVerTest {

    @Test
    fun `parses android suffix`() {
        val version = SemVer.parse("3.7.5-android-mobile")
        assertEquals(listOf(3, 7, 5), version?.numbers)
        assertEquals("android-mobile", version?.suffix)
        assertEquals("3.7.5-android-mobile", version?.text())
    }

    @Test
    fun `parses short and prefixed forms`() {
        assertEquals(listOf(2, 8, 6), SemVer.parse("v2.8.6")?.numbers)
        assertEquals(listOf(3, 7), SemVer.parse("3.7")?.numbers)
        assertEquals(listOf(1), SemVer.parse("1")?.numbers)
    }

    @Test
    fun `rejects garbage`() {
        assertNull(SemVer.parse(null))
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse("abc"))
        assertNull(SemVer.parse("-3.7"))
    }

    @Test
    fun `compares numerically and ignores suffix`() {
        val old = SemVer.parse("2.8.6")!!
        val current = SemVer.parse("3.7.5-android-mobile")!!
        assertEquals(true, old < current)
        assertEquals(compareValues(0, 0), current.compareTo(SemVer.parse("3.7.5")!!))
        assertEquals(true, SemVer.parse("3.7")!! < SemVer.parse("3.7.1")!!)
        assertEquals(true, SemVer.parse("3.7.5")!! < SemVer.parse("3.7.5.1")!!)
    }

    @Test
    fun `sdk gate accepts equal or newer loader`() {
        assertEquals(SdkCompatibility.COMPATIBLE, SdkVersion.check("2.8.6", "3.7.5-android-mobile"))
        assertEquals(SdkCompatibility.COMPATIBLE, SdkVersion.check("3.7.5", "3.7.5-android-mobile"))
        assertEquals(SdkCompatibility.INCOMPATIBLE, SdkVersion.check("3.8", "3.7.5-android-mobile"))
    }

    @Test
    fun `sdk gate is unknown instead of guessing`() {
        assertEquals(SdkCompatibility.COMPATIBLE, SdkVersion.check(null, null))
        assertEquals(SdkCompatibility.UNKNOWN, SdkVersion.check("2.8.6", null))
        assertEquals(SdkCompatibility.UNKNOWN, SdkVersion.check("x", "3.7.5"))
    }
}
