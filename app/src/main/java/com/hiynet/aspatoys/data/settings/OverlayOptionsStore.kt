package com.hiynet.aspatoys.data.settings

import android.content.Context
import com.hiynet.aspatoys.data.loader.LoaderFs
import com.hiynet.aspatoys.data.loader.ShizukuLoaderFs

/** Queues offline changes per loader root; ordinary app resumes never overwrite game-side resizing. */
class OverlayOptionsStore(context: Context, private val fs: LoaderFs = ShizukuLoaderFs()) {
    private val prefs = context.applicationContext.getSharedPreferences("overlay-options", Context.MODE_PRIVATE)
    private fun key(root: String) = java.security.MessageDigest.getInstance("SHA-256").digest(root.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun path(root: String) = "$root/data/toys-overlay.txt"

    fun read(root: String): OverlayOptions {
        val id = key(root)
        if (!prefs.getBoolean("$id.pending", false) && fs.isAvailable() && fs.exists(path(root))) {
            val raw = fs.readText(path(root))?.takeIf(String::isNotBlank) ?: error("无法读取游戏内悬浮窗设置。")
            val value = OverlayOptions.decode(raw)
            prefs.edit().putString(id, value.encode()).apply()
            return value
        }
        return prefs.getString(id, null)?.let(OverlayOptions::decode) ?: OverlayOptions()
    }

    fun save(root: String, value: OverlayOptions): Boolean {
        val id = key(root)
        prefs.edit().putString(id, value.encode()).putBoolean("$id.pending", true).apply()
        return sync(root)
    }

    fun sync(root: String): Boolean {
        val id = key(root)
        if (!prefs.getBoolean("$id.pending", false)) return true
        if (!fs.isAvailable() || (!fs.exists("$root/sdk/CesiumLoader.SDK.dll") && !fs.exists("$root/CesiumLoader.SDK.dll"))) return false
        val raw = prefs.getString(id, null) ?: return false
        fs.makeDirectories("$root/data")
        fs.writeText(path(root), raw)
        prefs.edit().putBoolean("$id.pending", false).apply()
        return true
    }
}
