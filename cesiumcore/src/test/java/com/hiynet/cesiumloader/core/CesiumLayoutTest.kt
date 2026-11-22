package com.hiynet.cesiumloader.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CesiumLayoutTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `derives every path from root`() {
        val root = File(temp.root, "CesiumLoader")
        val layout = CesiumLayout(root)

        assertEquals(File(root, "mods"), layout.mods)
        assertEquals(File(root, "disabled_mods"), layout.disabledMods)
        assertEquals(File(root, "configs"), layout.configs)
        assertEquals(File(root, "logs"), layout.logs)
        assertEquals(File(root, "data"), layout.dataDir)
        assertEquals(File(File(root, "sdk"), "CesiumLoader.SDK.dll"), layout.sdkDll)
        assertEquals(File(File(root, "bootstrap"), "CesiumLoader.Android.Bootstrap.dll"), layout.androidBootstrapDll)
        assertEquals(File(root, "CesiumAndroidApi.dll"), layout.androidApiDll)
        assertEquals(File(root, "enabled.json"), layout.enabledIndex)
    }

    @Test
    fun `per mod paths follow one folder per mod`() {
        val layout = CesiumLayout(File(temp.root, "CesiumLoader"))
        assertEquals(File(layout.root, "mods/MyMod/MyMod.dll"), layout.modDll("MyMod"))
        assertEquals(File(layout.root, "mods/MyMod/MyMod.json"), layout.modSidecar("MyMod"))
        assertEquals(File(layout.root, "disabled_mods/MyMod/MyMod.dll"), layout.disabledDll("MyMod"))
        assertEquals(File(layout.root, "configs/MyMod.json"), layout.configFile("MyMod"))
        assertEquals(File(layout.root, "data/MyMod"), layout.modDataDir("MyMod"))
    }

    @Test
    fun `managed directories cover the loader contract`() {
        val layout = CesiumLayout(File(temp.root, "CesiumLoader"))
        val names = layout.managedDirectories.map { it.name }
        assertTrue(names.containsAll(listOf("CesiumLoader", "mods", "disabled_mods", "configs", "logs")))
        assertEquals(13, layout.managedDirectories.size)
        assertEquals(layout.managedDirectories.size, layout.managedDirectories.toSet().size)
    }

    @Test
    fun `android paths are stable`() {
        assertEquals(File("/sdcard/CesiumLoader"), AndroidPaths.loaderRoot())
        assertEquals(
            File("/sdcard/Android/data/com.feimo.astralparty/files/com.unity.addressables/AssetBundles"),
            AndroidPaths.bundleCacheDir("com.feimo.astralparty"),
        )
    }

    @Test
    fun `recognises game packages but not neighbours`() {
        assertTrue(AndroidPaths.isGamePackage("com.feimo.astralparty"))
        assertTrue(AndroidPaths.isGamePackage("com.feimo.astralparty.cesium"))
        assertTrue(AndroidPaths.isGamePackage("com.feimo.astralparty.bilibili"))
        assertTrue(AndroidPaths.isGamePackage("com.feimo.astralparty.haoyou"))
        assertTrue(AndroidPaths.isGamePackage("com.feimo.astralparty.mi"))
        assertTrue(AndroidPaths.isGamePackage("com.feimo.astralparty.huawei"))
        assertTrue(AndroidPaths.isGamePackage("com.feimo.astralparty.nearme.gamecenter"))
        assertTrue(AndroidPaths.isGamePackage("com.feimo.jxpd"))
        assertTrue(AndroidPaths.isGamePackage("com.feimo.jxpd.bilibili"))
        assertTrue(AndroidPaths.isGamePackage("com.feimo.jxpd.vivo"))
        assertTrue(AndroidPaths.isGamePackage("com.jxpd.feimo.m4399"))
        assertTrue(AndroidPaths.isGamePackage("com.jxpd.feimo"))
        assertTrue(AndroidPaths.isGamePackage("com.astralparty.feimo.m4399"))
        assertTrue(AndroidPaths.isGamePackage("com.tencent.tmgp.jxpd"))
        assertTrue(AndroidPaths.isGamePackage("com.tencent.tmgp.astralparty"))
        assertFalse(AndroidPaths.isGamePackage("com.feimo.astralpartyfoo"))
        assertFalse(AndroidPaths.isGamePackage("com.feimo.astralparty.jpn"))
        assertFalse(AndroidPaths.isGamePackage("com.jxpd.feimo.jpn"))
        assertFalse(AndroidPaths.isGamePackage("com.hiynet.aspatoys"))
        assertFalse(AndroidPaths.isGamePackage(null))
        assertEquals(
            "com.feimo.astralparty.bilibili",
            AndroidPaths.gamePackageForPath("/storage/emulated/0/Android/data/com.feimo.astralparty.bilibili/files/CesiumLoader/mods"),
        )
        assertEquals(
            "com.jxpd.feimo.m4399",
            AndroidPaths.gamePackageForPath("/storage/emulated/0/Android/data/com.jxpd.feimo.m4399/files/CesiumLoader"),
        )
        assertEquals(
            "com.feimo.jxpd.nearme.gamecenter",
            AndroidPaths.gamePackageForPath("/sdcard/Android/data/com.feimo.jxpd.nearme.gamecenter/files/CesiumLoader"),
        )
    }
}
