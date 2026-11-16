package com.hiynet.aspatoys.installer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Real-fixture regression for the exact screenshot incident:
 * 77da original -> 37fc installed (19,043,364 B), then install again and restore.
 *
 * This reads the fixture and the byte-for-byte rebuilt bundle produced by
 * InstallerRebuildReproTest, so it is skipped when those local fixtures are unavailable.
 */
class LoaderBundleTransactionRealBundleTest {

    private lateinit var work: File
    private lateinit var io: FakeIo
    private lateinit var tx: LoaderBundleTransaction
    private lateinit var originalBytes: ByteArray
    private lateinit var patchedBytes: ByteArray

    @Before
    fun setUp() {
        originalBytes = InstallerFixtures.original
        patchedBytes = InstallerFixtures.patched
        assertEquals(LoaderInstaller.ORIGINAL_SHA256, LoaderBundleTransaction.sha256(originalBytes))
        assertEquals(LoaderInstaller.PATCHED_SHA256, LoaderBundleTransaction.sha256(patchedBytes))

        work = Files.createTempDirectory("bundle-real-fixture-test").toFile()
        io = FakeIo(patchedBytes)
        tx = LoaderBundleTransaction(
            workDir = work,
            io = io,
            originalBytes = { originalBytes },
            patch = { _, _ -> error("installed output must not be re-patched") },
            deploy = { _ -> },
            originalSha256 = LoaderInstaller.ORIGINAL_SHA256,
            patchedSha256 = LoaderInstaller.PATCHED_SHA256,
        )
    }

    @Test
    fun repeatedInstallOfTheExact37fcBundleIsAcceptedAndBacksUp77da() {
        tx.install("/remote/__data") { }

        assertEquals(LoaderInstaller.ORIGINAL_SHA256, LoaderBundleTransaction.sha256(tx.backupFile.readBytes()))
        assertArrayEquals(originalBytes, tx.backupFile.readBytes())
        assertEquals(LoaderInstaller.PATCHED_SHA256, LoaderBundleTransaction.sha256(io.remote))
        assertEquals(0, io.publishCount)
        assertEquals(1, io.stopCount)
    }

    @Test
    fun legacy37fcBackupRestoresTheExact77daOriginal() {
        val legacy = File(work, "__data.orig")
        legacy.writeBytes(patchedBytes)

        tx.restore("/remote/__data") { }

        assertArrayEquals(originalBytes, io.remote)
        assertArrayEquals(originalBytes, tx.backupFile.readBytes())
        assertArrayEquals(patchedBytes, legacy.readBytes())
    }

    @Test
    fun unknownNewerCacheIsStillRejectedAndDoesNotUseTheRealBackup() {
        io.remote = patchedBytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        val failure = runCatching { tx.install("/remote/__data") { } }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, failure?.javaClass)
        assertEquals(0, io.publishCount)
        assertEquals(0, io.stopCount)
        assertEquals(false, tx.backupFile.exists())
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return dir
    }

    private class FakeIo(var remote: ByteArray) : BundleIo {
        var stopCount = 0
        var publishCount = 0

        override fun read(path: String): ByteArray = remote.copyOf()

        override fun hash(path: String): String = LoaderBundleTransaction.sha256(remote)

        override fun publish(path: String, bytes: ByteArray, expectedSha256: String) {
            require(LoaderBundleTransaction.sha256(bytes) == expectedSha256)
            publishCount++
            remote = bytes.copyOf()
        }

        override fun stopGame() {
            stopCount++
        }
    }
}
