package com.hiynet.aspatoys.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON 文本读取工具。
 *
 * 这里的解析器只做「读」：市场的 HTTP 响应、模组的 sidecar（`{ModId}.json`）与配置
 * （`config.json`）都允许带注释，所以统一先剥注释再交给 kotlinx-serialization 解析。
 */
private val JsonParser = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

/** 剥掉 JSON 里的 `//` 与 `/* */` 注释，并去掉 UTF-8 BOM（与 Toys 的 StripJsonComments 一致）。 */
fun stripJsonComments(json: String): String {
    if (json.isEmpty()) return json
    val builder = StringBuilder(json.length)
    var inString = false
    var index = 0
    while (index < json.length) {
        val c = json[index]
        if (inString) {
            builder.append(c)
            if (c == '\\' && index + 1 < json.length) {
                builder.append(json[index + 1])
                index += 2
                continue
            }
            if (c == '"') inString = false
            index++
            continue
        }
        if (c == '"') {
            inString = true
            builder.append(c)
            index++
            continue
        }
        if (c == '/' && index + 1 < json.length && json[index + 1] == '/') {
            while (index < json.length && json[index] != '\n') index++
            continue
        }
        if (c == '/' && index + 1 < json.length && json[index + 1] == '*') {
            index += 2
            while (index + 1 < json.length && !(json[index] == '*' && json[index + 1] == '/')) index++
            index += 2
            continue
        }
        builder.append(c)
        index++
    }
    return builder.toString()
}

/** 解析一个 JSON 对象；文本为空、无注释容忍失败或根节点不是对象时返回 null（不抛异常）。 */
fun parseJsonObjectOrNull(text: String?): JsonObject? {
    if (text.isNullOrBlank()) return null
    return try {
        val trimmed = text.trimStart('\uFEFF')
        val element: JsonElement = JsonParser.parseToJsonElement(stripJsonComments(trimmed))
        element as? JsonObject
    } catch (_: Exception) {
        null
    }
}

/** 读取字符串字段；不是字符串或不存在时返回空串。 */
fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

/** 读取字符串字段；不存在或不是字符串时返回 null。 */
fun JsonObject.nullableString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 读取数字字段；不存在或不是数字时返回 0。 */
fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

fun JsonObject.int(key: String): Int = long(key).toInt()

fun JsonObject.double(key: String): Double =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0

/** 读取布尔字段；不存在时返回 false（需要「缺省为真」时用 [booleanOrDefault]）。 */
fun JsonObject.boolean(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.content?.equals("true", ignoreCase = true) ?: false

fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.content?.let { it.equals("true", ignoreCase = true) } ?: default

/** 读取数组字段；不存在或不是数组时返回空数组。 */
fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** 把字符串数组读成 List（忽略非字符串项）。 */
fun JsonArray.strings(): List<String> = mapNotNull { element ->
    (element as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** 把对象数组映射成 List（忽略非对象项）。 */
fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

/** 读取字符串数组字段；不存在时返回空列表。 */
fun JsonObject.stringArray(key: String): List<String> = array(key).strings()

/** 读取对象数组字段；不存在时返回空列表。 */
fun JsonObject.objectArray(key: String): List<JsonObject> = array(key).objects()
