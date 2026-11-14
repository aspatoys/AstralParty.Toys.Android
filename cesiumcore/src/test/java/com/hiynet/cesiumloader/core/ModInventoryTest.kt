package com.hiynet.cesiumloader.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModInventoryTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private lateinit var layout: CesiumLayout

    private fun setUpLayout() {
        val root = temp.root
        layout = CesiumLayout(root)
        for (dir in layout.managedDirectories) dir.mkdirs()
    }

    private fun touch(file: File, body: String = "") {
        file.parentFile?.mkdirs()
        file.writeText(body)
    }

    @Test
    fun `finds folder layout mod with external config`() {
        setUpLayout()
        touch(layout.modDll("ModA"))
        touch(layout.configFile("ModA"), "{}")

        val mods = ModInventory.scan(layout)
        assertEquals(1, mods.size)
        val mod = mods.single()
        assertEquals("ModA", mod.modId)
        assertTrue(mod.enabled)
        assertEquals(layout.modDll("ModA"), mod.dll)
        assertEquals(layout.modDir("ModA"), mod.folder)
        assertTrue(mod.hasConfig)
        assertFalse(mod.hasSidecar)
        assertFalse(mod.duplicated)
    }

    @Test
    fun `detects sidecar next to dll`() {
        setUpLayout()
        touch(layout.modDll("ModB"))
        touch(layout.modSidecar("ModB"), """{"modId":"ModB"}""")

        assertTrue(ModInventory.scan(layout).single().hasSidecar)
    }

    @Test
    fun `accepts flat dll layout`() {
        setUpLayout()
        touch(File(layout.mods, "FlatMod.dll"))

        val mod = ModInventory.scan(layout).single()
        assertEquals("FlatMod", mod.modId)
        assertNull(mod.folder)
        assertTrue(mod.enabled)
    }

    @Test
    fun `reports disabled mods`() {
        setUpLayout()
        touch(layout.disabledDll("ModC"))

        val mod = ModInventory.scan(layout).single()
        assertEquals("ModC", mod.modId)
        assertFalse(mod.enabled)
    }

    @Test
    fun `marks mod present on both sides as duplicated and prefers enabled`() {
        setUpLayout()
        touch(layout.modDll("ModD"))
        touch(layout.disabledDll("ModD"))

        val mod = ModInventory.scan(layout).single()
        assertEquals("ModD", mod.modId)
        assertTrue(mod.enabled)
        assertTrue(mod.duplicated)
        assertEquals(layout.modDll("ModD"), mod.dll)
    }

    @Test
    fun `treats legacy in-folder config as existing config`() {
        setUpLayout()
        touch(layout.modDll("ModE"))
        touch(File(layout.modDir("ModE"), "config.json"), "{}")

        assertTrue(ModInventory.scan(layout).single().hasConfig)
    }

    @Test
    fun `ignores directories without matching dll`() {
        setUpLayout()
        touch(File(layout.modDir("EmptyMod"), "data.txt"), "x")
        assertTrue(ModInventory.scan(layout).isEmpty())
    }

    @Test
    fun `sorts by id ignoring case`() {
        setUpLayout()
        touch(layout.modDll("beta"))
        touch(layout.modDll("Alpha"))

        assertEquals(listOf("Alpha", "beta"), ModInventory.scan(layout).map { it.modId })
    }
}
