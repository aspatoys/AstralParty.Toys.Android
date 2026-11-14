package com.hiynet.aspatoys.data.mods

import com.hiynet.aspatoys.core.boolean
import com.hiynet.aspatoys.core.double
import com.hiynet.aspatoys.core.obj
import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 配置项的类型；只用来决定界面上给什么控件。 */
enum class ConfigKind { BOOLEAN, NUMBER, TEXT, OTHER }

/**
 * 配置里的一个字段。
 *
 * [token] 是它在文本里的**原样字面量**（`true` / `12` / `"F3"`），表单改值时用它回写，
 * 这样注释、缩进和键顺序都不会被动到。
 */
data class ConfigField(
    val key: String,
    val kind: ConfigKind,
    val token: String,
    val display: String,
    val description: String,
)

/** 一份配置的解析结果；[raw] 是原文本，[fields] 只包含能安全改写的标量字段。 */
data class ParsedConfig(
    val raw: String,
    val fields: List<ConfigField>,
    /** 读不懂的字段（嵌套对象、数组等），只能去文本编辑里改。 */
    val uneditable: List<String>,
)

/**
 * 模组配置（`configs/<ModId>.json`）的读写辅助。
 *
 * 配置由游戏内 SDK 生成与消费，格式是「平铺键值 + `_comments` 多语言说明」。
 * 界面改值只做**定点替换**，不从解析结果重新序列化：重排一遍就会把用户的注释、空行、
 * 数字写法（`1` 与 `1.0`）全部改掉，改动看起来比实际大得多。
 */
object ModConfigText {

    const val COMMENTS_KEY = "_comments"

    /** 解析文本；不是合法 JSON 对象时返回 null。 */
    fun parse(text: String, language: String = "zh-Hans"): ParsedConfig? {
        val root = parseJsonObjectOrNull(text) ?: return null
        val comments = root.obj(COMMENTS_KEY)
        val fields = mutableListOf<ConfigField>()
        val uneditable = mutableListOf<String>()

        for ((key, element) in root) {
            if (key == COMMENTS_KEY) continue
            if (element !is JsonPrimitive) {
                uneditable += key
                continue
            }
            val description = comments?.obj(key)?.let { localized(it, language) }.orEmpty()
            val kind = when {
                element.isString -> ConfigKind.TEXT
                element.content == "true" || element.content == "false" -> ConfigKind.BOOLEAN
                element.content.toDoubleOrNull() != null -> ConfigKind.NUMBER
                else -> ConfigKind.OTHER
            }
            fields += ConfigField(
                key = key,
                kind = kind,
                token = element.toString(),
                display = if (element.isString) element.content else element.content,
                description = description,
            )
        }
        return ParsedConfig(raw = text, fields = fields, uneditable = uneditable)
    }

    private fun localized(comments: JsonObject, language: String): String {
        comments.string(language).takeIf { it.isNotBlank() }?.let { return it }
        comments.string("zh-Hans").takeIf { it.isNotBlank() }?.let { return it }
        comments.string("en").takeIf { it.isNotBlank() }?.let { return it }
        return comments.string(comments.keys.firstOrNull().orEmpty())
    }

    /**
     * 把某个键的值原地换成 [token]（已带引号或就是数字/布尔的字面量）。
     *
     * 只在 `_comments` 之前找：说明区里也有同名键，改错地方就把文档写坏了。
     * 找不到该键时返回 null，让调用方保留原文而不是瞎猜。
     */
    fun patchValue(text: String, key: String, token: String): String? {
        val commentsAt = text.indexOf("\"$COMMENTS_KEY\"")
        val head = if (commentsAt > 0) text.substring(0, commentsAt) else text
        val pattern = Regex(
            "(\"" + Regex.escape(key) + "\"\\s*:\\s*)" +
                "(\"(?:[^\"\\\\]|\\\\.)*\"|true|false|-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)",
        )
        val match = pattern.find(head) ?: return null
        val start = match.range.first
        val end = match.range.last + 1
        val rebuilt = text.substring(0, start) + match.groupValues[1] + token + text.substring(end)
        // 回写后必须还能解析成对象，并且值确实变了。
        val check = parseJsonObjectOrNull(rebuilt) ?: return null
        val changed = (check[key] as? JsonPrimitive)?.toString() ?: return null
        if (changed != token) return null
        return rebuilt
    }

    /** 布尔字段取当前值（解析失败按 false）。 */
    fun booleanOf(root: JsonObject, key: String): Boolean = root.boolean(key)

    /** 数字字段取当前值（解析失败按 0）。 */
    fun numberOf(root: JsonObject, key: String): Double = root.double(key)

    /** 把用户输入的文本转成 JSON 字符串字面量（含引号）。 */
    fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
