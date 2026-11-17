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

    private fun current(root: String): OverlayOptions? {
        if (!fs.isAvailable() || !fs.exists(path(root))) return null
        val raw = fs.readText(path(root))?.takeIf(String::isNotBlank) ?: error("无法读取游戏内悬浮窗设置。")
        return OverlayOptions.decode(raw)
    }

    private fun edits(id: String): Set<String> = prefs.getString("$id.edits", null)
        ?.split(',')?.filter(String::isNotEmpty)?.toSet() ?: OverlayOptionEdits.legacyFields

    fun read(root: String): OverlayOptions {
        val id = key(root)
        val pending = prefs.getBoolean("$id.pending", false)
        val draft = prefs.getString(id, null)?.let(OverlayOptions::decode) ?: OverlayOptions()
        val game = current(root)
        val value = when {
            pending && game != null -> OverlayOptionEdits.merge(game, draft, edits(id))
            pending -> draft
            else -> game ?: draft
        }
        // Rebase the visible draft without marking refreshed game fields as APK edits.
        prefs.edit().putString(id, value.encode()).putString("$id.baseline", value.encode()).apply()
        return value
    }

    fun save(root: String, value: OverlayOptions): Boolean {
        val id = key(root)
        val draft = value.normalized()
        val baseline = prefs.getString("$id.baseline", null)?.let(OverlayOptions::decode)
            ?: prefs.getString(id, null)?.let(OverlayOptions::decode) ?: OverlayOptions()
        val fields = (if (prefs.getBoolean("$id.pending", false)) edits(id) else emptySet()) +
            OverlayOptionEdits.changed(draft, baseline)
        prefs.edit().putString(id, draft.encode()).putString("$id.edits", fields.sorted().joinToString(","))
            .putBoolean("$id.pending", true).apply()
        return sync(root)
    }

    fun sync(root: String): Boolean {
        val id = key(root)
        if (!prefs.getBoolean("$id.pending", false)) return true
        if (!fs.isAvailable() || (!fs.exists("$root/sdk/CesiumLoader.SDK.dll") && !fs.exists("$root/CesiumLoader.SDK.dll"))) return false
        val raw = prefs.getString(id, null) ?: return false
        fs.makeDirectories("$root/data")
        // Opacity is owned by the game settings page; a stale APK draft must not overwrite it.
        // Position and speed memory are game-owned too; shared fields change only if explicitly edited.
        val game = current(root)
        val merged = OverlayOptions.decode(raw).let { if (game != null) OverlayOptionEdits.merge(game, it, edits(id)) else it }
        fs.writeText(path(root), merged.encode())
        prefs.edit().putString(id, merged.encode()).putString("$id.baseline", merged.encode())
            .remove("$id.edits").putBoolean("$id.pending", false).apply()
        return true
    }
}
