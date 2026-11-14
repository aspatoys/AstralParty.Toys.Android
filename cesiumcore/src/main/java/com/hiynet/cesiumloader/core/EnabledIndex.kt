package com.hiynet.cesiumloader.core

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `enabled.json`：启用/停用提示，供其他实现（含 CesiumLoader-Android-ModPack）互操作。
 *
 * 判断模组状态时**不要**读这里，读目录（[CesiumLayout.mods] / [CesiumLayout.disabledMods]）。
 *
 * 盘上格式（本实现写出）：
 * ```json
 * { "version": 1, "enabled": ["A"], "disabled": ["B"] }
 * ```
 * 读取时额外兼容 `{ "mods": { "A": true } }` 与裸数组两种写法。
 */
data class EnabledIndex(
    val enabled: Set<String> = emptySet(),
    val disabled: Set<String> = emptySet(),
) {

    fun serialize(): String = buildJsonObject {
        put("version", Version)
        put("enabled", JsonArray(enabled.sorted().map(::JsonPrimitive)))
        put("disabled", JsonArray(disabled.sorted().map(::JsonPrimitive)))
    }.toString()

    companion object {
        const val Version = 1

        fun parse(text: String?): EnabledIndex {
            val element = JsonText.parseOrNull(text) ?: return EnabledIndex()
            val enabled = LinkedHashSet<String>()
            val disabled = LinkedHashSet<String>()
            when (element) {
                is JsonArray -> enabled += element.ids()
                is JsonObject -> {
                    enabled += element["enabled"]?.ids().orEmpty()
                    disabled += element["disabled"]?.ids().orEmpty()
                    val legacy = element["mods"] as? JsonObject
                    if (legacy != null) {
                        for ((id, value) in legacy) {
                            when ((value as? JsonPrimitive)?.booleanOrNull) {
                                true -> enabled += id
                                false -> disabled += id
                                null -> Unit
                            }
                        }
                    }
                }

                else -> Unit
            }
            return EnabledIndex(enabled.toSet(), disabled.toSet())
        }

        fun read(file: File): EnabledIndex {
            if (!file.isFile) return EnabledIndex()
            val text = runCatching { file.readText() }.getOrNull()
            return parse(text)
        }

        fun write(file: File, index: EnabledIndex) {
            AtomicFiles.writeText(file, index.serialize())
        }

        private fun JsonElement.ids(): List<String> = when (this) {
            is JsonArray -> mapNotNull { item ->
                (item as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }

            is JsonObject -> keys.toList()
            else -> emptyList()
        }
    }
}
