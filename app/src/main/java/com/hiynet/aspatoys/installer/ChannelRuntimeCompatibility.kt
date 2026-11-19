package com.hiynet.aspatoys.installer

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs

internal class UnsupportedRuntimeEdit(message: String) : IllegalStateException(message)

internal class ChannelRuntimeCompatibility(
    private val references: List<Reference>,
    private val readReference: (String) -> ByteArray,
    private val edit: (ByteArray) -> ByteArray,
    private val retarget: (ByteArray) -> ByteArray,
) {
    data class Reference(val id: String, val originalSha256: String, val originalBytes: Int, val patchedSha256: String)

    fun patch(input: ByteArray, force: Boolean, log: (String) -> Unit): ByteArray {
        val sha = LoaderBundleTransaction.sha256(input)
        val known = references.firstOrNull { it.originalSha256 == sha }
        require(force || known != null) { "渠道资源未收录。可在加载器页开启「实验性强制兼容渠道资源」。" }
        try {
            return edit(input)
        } catch (error: UnsupportedRuntimeEdit) {
            val chosen = known ?: references.minByOrNull { abs(input.size - it.originalBytes) }
                ?: throw error
            val runtime = readReference(chosen.id)
            require(LoaderBundleTransaction.sha256(runtime) == chosen.patchedSha256) { "渠道兼容基准指纹不匹配" }
            val selection = if (known != null) "匹配程序集指纹" else "按程序集大小选择"
            log("渠道兼容：$selection 3.3.0 基准 ${chosen.id}。如登录或运行异常，可禁用加载器恢复原包。")
            return retarget(runtime)
        }
    }

    companion object {
        private const val DIRECTORY = "cesium/channel"

        fun bundled(context: Context, edit: (ByteArray) -> ByteArray, retarget: (ByteArray) -> ByteArray): ChannelRuntimeCompatibility {
            val index = context.assets.open("$DIRECTORY/references.json").bufferedReader().use { JSONObject(it.readText()) }
            val profiles = index.getJSONArray("profiles")
            val references = List(profiles.length()) { i ->
                val item = profiles.getJSONObject(i)
                Reference(item.getString("id"), item.getString("originalSha256"),
                    item.getInt("originalBytes"), item.getString("patchedSha256"))
            }
            return ChannelRuntimeCompatibility(references, { id ->
                require(id.matches(Regex("[a-f0-9]{32}")))
                context.assets.open("$DIRECTORY/$id.dll").use { it.readBytes() }
            }, edit, retarget)
        }
    }
}
