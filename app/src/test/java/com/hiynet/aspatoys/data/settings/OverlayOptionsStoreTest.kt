package com.hiynet.aspatoys.data.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.hiynet.aspatoys.data.loader.InMemoryLoaderFs
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Exercises the production preference queue and filesystem sync, including offline drafts. */
class OverlayOptionsStoreTest {
    private val root = "/game/CesiumLoader"
    private val path = "$root/data/toys-overlay.txt"
    private val fs = InMemoryLoaderFs()
    private val prefs = MemoryPreferences()
    private val context = object : ContextWrapper(null as Context?) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    private val store = OverlayOptionsStore(context, fs)
    private fun seed(value: OverlayOptions = OverlayOptions()): OverlayOptions {
        fs.writeText("$root/sdk/CesiumLoader.SDK.dll", "sdk")
        fs.writeText(path, value.encode())
        return store.read(root)
    }
    private fun remote(value: OverlayOptions) { fs.writeText(path, value.encode()) }
    private fun saved() = OverlayOptions.decode(fs.readText(path)!!)

    @Test fun `saving groups retains newer game position speed and untouched shared fields`() {
        val draft = seed()
        val current = draft.copy(opacity = .85f, center = .7f, previousSpeed = 4f,
            width = 1.2f, height = 1.2f, fontScale = 1.3f, idleSeconds = 10f, idleOpacity = .55f)
        remote(current)
        val groups = listOf(OverlayGroup("本地", listOf("CardSkinMod")))
        assertTrue(store.save(root, draft.copy(groups = groups)))
        assertEquals(current.copy(groups = groups), saved())
    }

    @Test fun `explicit APK field changes win without overwriting game owned values`() {
        val draft = seed()
        val current = draft.copy(center = .6f, previousSpeed = 5f, fontScale = 1.15f, opacity = .7f)
        remote(current)
        assertTrue(store.save(root, draft.copy(fontScale = 1.3f, width = 1.5f, height = 1.5f)))
        assertEquals(current.copy(fontScale = 1.3f, width = 1.5f, height = 1.5f), saved())
    }

    @Test fun `pending read rebases the view without making untouched fields dirty`() {
        val draft = seed()
        fs.available = false
        assertFalse(store.save(root, draft.copy(width = 1.4f, height = 1.4f)))
        val game = draft.copy(center = .4f, previousSpeed = 3f, fontScale = 1.15f)
        remote(game)
        fs.available = true
        val view = store.read(root)
        assertEquals(game.copy(width = 1.4f, height = 1.4f), view)
        remote(game.copy(fontScale = 1.3f))
        assertTrue(store.sync(root))
        assertEquals(game.copy(width = 1.4f, height = 1.4f, fontScale = 1.3f), saved())
    }

    @Test fun `repeated offline saves retain all explicit edits and latest game values`() {
        val draft = seed()
        fs.available = false
        assertFalse(store.save(root, draft.copy(width = 1.4f, height = 1.4f)))
        val pending = store.read(root)
        assertFalse(store.save(root, pending.copy(fontScale = 1.3f)))
        val game = draft.copy(center = .3f, previousSpeed = 2f, idleSeconds = 10f)
        remote(game)
        fs.available = true
        assertTrue(store.sync(root))
        assertEquals(game.copy(width = 1.4f, height = 1.4f, fontScale = 1.3f), saved())
    }

    @Test fun `legacy pending drafts preserve newly introduced game fields`() {
        val game = seed(OverlayOptions(center = .5f, previousSpeed = 3f, fontScale = 1.15f, idleSeconds = 10f))
        val id = MessageDigest.getInstance("SHA-256").digest(root.toByteArray()).joinToString("") { "%02x".format(it) }
        prefs.edit().putString(id, "version=1\nopacity=0.5\nwidth=1.4\nheight=1.4\n")
            .putBoolean("$id.pending", true).apply()
        assertTrue(store.sync(root))
        assertEquals(game.copy(width = 1.4f, height = 1.4f, groups = emptyList()), saved())
    }

    @Test fun `unreadable current settings remain intact and retain the pending edit`() {
        val draft = seed()
        remote(draft)
        fs.writeText(path, "invalid-current-settings")
        assertTrue(runCatching { store.save(root, draft.copy(fontScale = 1.3f)) }.isFailure)
        assertEquals("invalid-current-settings", fs.readText(path))
        val repaired = draft.copy(center = .4f, previousSpeed = 2f)
        remote(repaired)
        assertTrue(store.sync(root))
        assertEquals(repaired.copy(fontScale = 1.3f), saved())
    }

    @Test fun `ordinary resume without pending changes writes nothing`() {
        seed()
        val before = fs.writes
        assertTrue(store.sync(root))
        assertEquals(before, fs.writes)
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { updates[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { updates[key] = values?.toSet() }
            override fun putInt(key: String, value: Int) = apply { updates[key] = value }
            override fun putLong(key: String, value: Long) = apply { updates[key] = value }
            override fun putFloat(key: String, value: Float) = apply { updates[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { updates[key] = value }
            override fun remove(key: String) = apply { updates[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) values.clear()
                updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
        }
    }
}
