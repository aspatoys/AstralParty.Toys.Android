package com.hiynet.cesiumloader.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 宽松 JSON：容忍 BOM 与 `//`、`/* */` 注释（模组配置与清单里都出现过）。 */
object JsonText {

    private val lenient = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    fun stripBom(text: String): String =
        if (text.startsWith('\uFEFF')) text.substring(1) else text

    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        var inString = false
        var escaped = false
        while (index < text.length) {
            val char = text[index]
            if (inString) {
                out.append(char)
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                index++
                continue
            }
            when {
                char == '"' -> {
                    inString = true
                    out.append(char)
                    index++
                }

                char == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                    while (index < text.length && text[index] != '\n') index++
                }

                char == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                    index += 2
                    while (index + 1 < text.length && !(text[index] == '*' && text[index + 1] == '/')) index++
                    index = minOf(index + 2, text.length)
                }

                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return out.toString()
    }

    fun parseOrNull(text: String?): JsonElement? {
        if (text.isNullOrBlank()) return null
        val cleaned = stripComments(stripBom(text)).trim()
        if (cleaned.isEmpty()) return null
        return runCatching { lenient.parseToJsonElement(cleaned) }.getOrNull()
    }

    fun parseObjectOrNull(text: String?): JsonObject? = parseOrNull(text) as? JsonObject

    fun readOrNull(file: java.io.File): JsonElement? =
        if (file.isFile) runCatching { parseOrNull(file.readText()) }.getOrNull() else null
}
