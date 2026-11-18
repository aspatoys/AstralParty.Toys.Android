package com.hiynet.aspatoys.installer

import com.hiynet.cesiumloader.core.unity.SerializedRuntimeAsset
import com.hiynet.cesiumloader.core.unity.UnityFs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdaptiveCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun newCacheInstallsAndRestoresItsOwnOriginal() {
        val bundle = listOf("src/main/assets", "app/src/main/assets")
            .map { File(it, "cesium/recovery/original.bundle") }.firstOrNull { it.isFile }
        assumeTrue("需要已验证的原版缓存夹具", bundle != null)
        val legacy = bundle!!.readBytes()
        val original = System.getenv("CESIUM_ADAPTIVE_BUNDLE")?.let { File(it).readBytes() } ?:
            legacy.copyOf().also { it[12] = '4'.code.toByte() } // Vary the UnityFS player version string.
        var current = original
        var writes = 0
        val remote = "/cache/current/__data"
        val io = object : BundleIo {
            override fun stopGame() = Unit
            override fun read(path: String) = current
            override fun hash(path: String) = LoaderBundleTransaction.sha256(current)
            override fun publish(path: String, bytes: ByteArray, expectedSha256: String) {
                assertEquals(expectedSha256, LoaderBundleTransaction.sha256(bytes))
                current = bytes
                writes++
            }
        }
        val outputDll = System.getenv("CESIUM_ADAPTIVE_PATCHED_DLL")?.let { File(it).readBytes() }
        val editor = CurrentCacheEditor { input -> outputDll ?: (input + byteArrayOf(1, 2, 3, 4, 5)) }
        val transaction = LoaderBundleTransaction(
            temporary.root, io, { legacy }, { _, _ -> error("legacy patcher") }, {},
            LoaderInstaller.ORIGINAL_SHA256, LoaderInstaller.PATCHED_SHA256, editor::patch,
        )
        val originalSha = io.hash(remote)
        assertNotEquals(LoaderInstaller.ORIGINAL_SHA256, originalSha)
        transaction.install(remote)
        assertEquals(LoaderBundleTransaction.State.INSTALLED, transaction.classify(io.hash(remote), remote))
        assertTrue(transaction.backupAvailable(io.hash(remote), remote))
        val before = UnityFs.parse(original)
        val result = UnityFs.parse(current)
        assertEquals(before.nodes.map { it.path }, result.nodes.map { it.path })
        val script = result.nodes.mapIndexedNotNull { index, node ->
            val bytes = result.readNode(node)
            val asset = if (node.flags and 4 != 0) SerializedRuntimeAsset.find(bytes, LoaderInstaller.ASSEMBLY) else null
            if (asset == null) assertArrayEquals(before.readNode(before.nodes[index]), bytes)
            asset
        }.single().assembly()
        if (outputDll != null) assertArrayEquals(outputDll, script)
        transaction.install(remote)
        assertEquals(1, writes)
        assertEquals(originalSha, transaction.restore(remote))
        assertArrayEquals(original, current)
        assertEquals(2, writes)
        current = byteArrayOf(99, 7)
        assertThrows(Exception::class.java) { transaction.install(remote) }
        assertArrayEquals(byteArrayOf(99, 7), current)
        assertEquals(2, writes)
    }
}
