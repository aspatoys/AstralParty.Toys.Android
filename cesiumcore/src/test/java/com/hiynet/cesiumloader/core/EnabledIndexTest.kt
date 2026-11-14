package com.hiynet.cesiumloader.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnabledIndexTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `accepts bare array as enabled list`() {
        val index = EnabledIndex.parse("""["ModA","ModB"]""")
        assertEquals(setOf("ModA", "ModB"), index.enabled)
        assertTrue(index.disabled.isEmpty())
    }

    @Test
    fun `accepts object with comments`() {
        val text = """
            {
              // 本实现写出的格式
              "version": 1,
              "enabled": ["ModA"],
              "disabled": ["ModB"]
            }
        """.trimIndent()
        val index = EnabledIndex.parse(text)
        assertEquals(setOf("ModA"), index.enabled)
        assertEquals(setOf("ModB"), index.disabled)
    }

    @Test
    fun `accepts legacy map form`() {
        val index = EnabledIndex.parse("""{"mods":{"ModA":true,"ModB":false,"ModC":"x"}}""")
        assertEquals(setOf("ModA"), index.enabled)
        assertEquals(setOf("ModB"), index.disabled)
    }

    @Test
    fun `returns empty index for broken json`() {
        assertEquals(EnabledIndex(), EnabledIndex.parse("not json"))
        assertEquals(EnabledIndex(), EnabledIndex.parse(null))
        assertEquals(EnabledIndex(), EnabledIndex.parse(""))
    }

    @Test
    fun `round trips through disk without leaving temp file`() {
        val file = File(temp.root, "enabled.json")
        val index = EnabledIndex(enabled = setOf("ModB", "ModA"), disabled = setOf("ModC"))
        EnabledIndex.write(file, index)

        val read = EnabledIndex.read(file)
        assertEquals(index, read)
        assertFalse(File(temp.root, "enabled.json.tmp").exists())
        assertTrue(file.readText().contains("\"version\":1"))
    }
}
