package com.hiynet.aspatoys.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTextTest {
    @Test
    fun `strip comments keeps string content intact`() {
        val text = """
            {
              // 行注释
              "name": "a//b",
              "url": "http://example.com/*x*/",
              /* 块注释 */
              "enabled": false
            }
        """.trimIndent()

        val stripped = stripJsonComments(text)
        val root = parseJsonObjectOrNull(stripped)!!

        assertEquals("a//b", root.string("name"))
        assertEquals("http://example.com/*x*/", root.string("url"))
        assertEquals(false, root.booleanOrDefault("enabled", true))
    }

    @Test
    fun `parse tolerates BOM and trailing comments`() {
        val text = "\uFEFF{\n \"version\": \"1.2.3\" // 模组版本\n}\n"
        val root = parseJsonObjectOrNull(text)!!
        assertEquals("1.2.3", root.string("version"))
    }

    @Test
    fun `parse returns null for broken json`() {
        assertNull(parseJsonObjectOrNull("{ \"a\": "))
        assertNull(parseJsonObjectOrNull(""))
        assertNull(parseJsonObjectOrNull(null))
    }

    @Test
    fun `readers fall back to defaults`() {
        val root = parseJsonObjectOrNull(
            """{"count": 12, "flag": true, "tags": ["a", "b"], "deps": [{"id": "X"}, 3]}""",
        )!!
        assertEquals(12L, root.long("count"))
        assertEquals(0L, root.long("missing"))
        assertTrue(root.boolean("flag"))
        assertEquals(listOf("a", "b"), root.stringArray("tags"))
        assertEquals(listOf("X"), root.objectArray("deps").map { it.string("id") })
    }
}
