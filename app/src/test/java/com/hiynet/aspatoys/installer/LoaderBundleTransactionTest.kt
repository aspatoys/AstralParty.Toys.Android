package com.hiynet.aspatoys.installer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Pure transaction tests: real [LoaderBundleTransaction], no Android framework and no shell.
 *
 * The fake [BundleIo] models a remote cache file and injects transport failures. The production
 * transaction must refuse unknown versions before any backup/deploy/write, must never overwrite a
 * legacy __data.orig, and must only roll back a bundle that is exactly its own published output.
 */
class LoaderBundleTransactionTest {

    private lateinit var work: File
    private lateinit var io: FakeIo
    private lateinit var tx: LoaderBundleTransaction

    private val original = bytes(1024, 7, 11)
    private val patched = bytes(1500, 13, 3)
    private val unknown = bytes(1400, 17, 5)
    private val bundled = original.copyOf()

    private var deployCalls = 0
    private var patchCalls = 0
    private var stopError: Exception? = null
    private var deployError: Exception? = null

    @Before
    fun setUp() {
        work = Files.createTempDirectory("bundle-transaction-test").toFile()
        io = FakeIo(original)
        tx = transaction(bundled)
    }

    private fun transaction(bundledBytes: ByteArray): LoaderBundleTransaction =
        LoaderBundleTransaction(
            workDir = work,
            io = io,
            originalBytes = { bundledBytes },
            patch = { input, _ ->
                patchCalls++
                assertEquals(sha(original), sha(input))
                patched
            },
            deploy = { _ ->
                deployCalls++
                io.onDeploy?.invoke()
                deployError?.let { throw it }
            },
            originalSha256 = sha(original),
            patchedSha256 = sha(patched),
        )

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bytes(size: Int, step: Int, base: Int) =
        ByteArray(size) { (it * step + base).toByte() }

    private fun canonical() = tx.backupFile
    private fun legacy() = File(work, "__data.orig")

    @Test
    fun classifyUsesTheSameVersionIdentityAsTheInstaller() {
        assertEquals(LoaderBundleTransaction.State.ORIGINAL, tx.classify(sha(original)))
        assertEquals(LoaderBundleTransaction.State.INSTALLED, tx.classify(sha(patched)))
        assertEquals(LoaderBundleTransaction.State.UNKNOWN, tx.classify(sha(unknown)))
        assertEquals(LoaderBundleTransaction.State.UNKNOWN, tx.classify(null))
    }

    @Test
    fun originalInstallWritesCanonicalBackupThenDeploysAndPublishes() {
        val beforeStop = deployCalls
        tx.install("/remote/__data") { }

        assertEquals(beforeStop + 1, deployCalls)
        assertEquals(1, io.stopCount)
        assertEquals(1, io.publishCount)
        assertEquals(sha(original), sha(canonical().readBytes()))
        assertEquals(sha(patched), sha(io.remote))
        assertFalse(legacy().exists())
    }

    @Test
    fun repeatedInstallOfKnownPatchedBundleDoesNotRewriteBackupOrGameFile() {
        canonical().parentFile.mkdirs()
        canonical().writeBytes(original)
        io.remote = patched.copyOf()

        tx.install("/remote/__data") { }

        assertEquals(sha(original), sha(canonical().readBytes()))
        assertEquals(sha(patched), sha(io.remote))
        assertEquals(0, io.publishCount)
        assertEquals(1, io.stopCount)
        assertEquals(1, deployCalls)
        assertFalse(legacy().exists())
        assertEquals(1, io.accessChecks)
    }

    @Test
    fun repeatedInstallPreservesExistingGoodCanonicalBackup() {
        canonical().parentFile.mkdirs()
        canonical().writeBytes(original)
        io.remote = patched.copyOf()

        tx.install("/remote/__data") { }

        assertEquals(sha(original), sha(canonical().readBytes()))
    }

    @Test
    fun repeatedInstallCannotSucceedWhenCacheOwnershipRepairFails() {
        io.remote = patched.copyOf()
        io.accessError = IllegalStateException("cache owner is root")
        assertThrows(IllegalStateException::class.java) { tx.install("/remote/__data") }
        assertEquals(0, deployCalls)
        assertEquals(0, io.publishCount)
        assertEquals(sha(patched), sha(io.remote))
    }

    @Test
    fun failedPayloadOwnershipCheckNeverPublishesPatchedGameCache() {
        deployError = IllegalStateException("loader owner is root")
        assertThrows(IllegalStateException::class.java) { tx.install("/remote/__data") }
        assertEquals(0, io.publishCount)
        assertEquals(sha(original), sha(io.remote))
    }

    @Test
    fun unknownHashIsRejectedBeforeBackupDeployAndWrite() {
        io.remote = unknown.copyOf()

        assertThrows(IllegalArgumentException::class.java) {
            tx.install("/remote/__data") { }
        }

        assertEquals(sha(unknown), sha(io.remote))
        assertEquals(0, io.stopCount)
        assertEquals(0, io.publishCount)
        assertEquals(0, deployCalls)
        assertFalse(canonical().exists())
        assertFalse(legacy().exists())
    }

    @Test
    fun restoreRejectsUnknownWithoutWriting() {
        io.remote = unknown.copyOf()

        assertThrows(IllegalStateException::class.java) {
            tx.restore("/remote/__data") { }
        }

        assertEquals(sha(unknown), sha(io.remote))
        assertEquals(0, io.stopCount)
        assertEquals(0, io.publishCount)
        assertFalse(canonical().exists())
    }

    @Test
    fun legacyBackupThatIsNotOriginalIsNeverTreatedAsRecoverySource() {
        legacy().writeBytes(patched)
        io.remote = patched.copyOf()

        tx.restore("/remote/__data") { }

        // The bundled original is verified and copied to the canonical location; the legacy file is
        // left untouched rather than being overwritten or trusted.
        assertEquals(sha(original), sha(canonical().readBytes()))
        assertEquals(sha(patched), sha(legacy().readBytes()))
        assertEquals(sha(original), sha(io.remote))
    }

    @Test
    fun badBundledOriginalIsRejectedBeforeWriting() {
        legacy().writeBytes(patched)
        io.remote = patched.copyOf()
        val bad = bundled.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val badTx = transaction(bad)

        val error = assertThrows(IllegalArgumentException::class.java) {
            badTx.restore("/remote/__data") { }
        }

        assertTrue(error.message!!.contains("内置原包校验失败"))
        assertEquals(sha(patched), sha(io.remote))
        assertEquals(0, io.publishCount)
        assertEquals(0, io.stopCount)
    }

    @Test
    fun readFailureLeavesRemoteAndBackupUntouched() {
        io.readError = IOException("stream disconnected")
        assertThrows(IOException::class.java) {
            tx.install("/remote/__data") { }
        }
        assertEquals(sha(original), sha(io.remote))
        assertEquals(0, io.publishCount)
        assertFalse(canonical().exists())
    }

    @Test
    fun publishFailureBeforeReplacementLeavesTargetUntouched() {
        io.failPublishBefore = true

        assertThrows(IOException::class.java) {
            tx.install("/remote/__data") { }
        }

        assertEquals(sha(original), sha(io.remote))
        assertEquals(sha(original), sha(canonical().readBytes()))
    }

    @Test
    fun publishFailureAfterReplacementRollsBackOnlyTheKnownOutput() {
        io.failPublishAfter = true

        assertThrows(IOException::class.java) {
            tx.install("/remote/__data") { }
        }

        assertEquals(sha(original), sha(io.remote))
        assertEquals(2, io.publishCount)
        assertEquals(sha(original), sha(canonical().readBytes()))
    }

    @Test
    fun remoteChangeDuringDeployAbortsBeforePublishAndKeepsNewFile() {
        deployError = IOException("game cache changed")
        io.onDeploy = { io.remote = unknown.copyOf() }

        assertThrows(IOException::class.java) {
            tx.install("/remote/__data") { }
        }

        assertEquals(sha(unknown), sha(io.remote))
        assertEquals(0, io.publishCount)
        assertEquals(sha(original), sha(canonical().readBytes()))
    }

    @Test
    fun stopGameFailureAbortsBeforePublish() {
        io.stopError = IOException("stop failed")

        assertThrows(IOException::class.java) {
            tx.install("/remote/__data") { }
        }

        assertEquals(sha(original), sha(io.remote))
        assertEquals(0, io.publishCount)
        assertEquals(sha(original), sha(canonical().readBytes()))
    }

    @Test
    fun alreadyOriginalRestoreIsANoop() {
        io.remote = original.copyOf()

        tx.restore("/remote/__data") { }

        assertEquals(1, io.stopCount)
        assertEquals(1, io.accessChecks)
        assertEquals(0, io.publishCount)
    }

    @Test
    fun damagedCanonicalBackupIsQuarantinedAndBundledOriginalIsUsed() {
        canonical().parentFile.mkdirs()
        canonical().writeBytes(unknown)
        io.remote = patched.copyOf()

        tx.restore("/remote/__data") { }

        assertEquals(sha(original), sha(canonical().readBytes()))
        assertEquals(sha(original), sha(io.remote))
        val quarantined = canonical().parentFile.listFiles()!!
            .filter { it.name.startsWith("__data.orig.invalid-") }
        assertTrue("damaged backup must be preserved as evidence", quarantined.isNotEmpty())
    }

    private class FakeIo(var remote: ByteArray) : BundleIo {
        var accessChecks = 0
        var accessError: Exception? = null
        override fun ensureAccessible(path: String) { accessChecks++; accessError?.let { throw it } }
        var stopCount = 0
        var publishCount = 0
        var readError: Exception? = null
        var stopError: Exception? = null
        var failPublishBefore = false
        var failPublishAfter = false
        var onDeploy: (() -> Unit)? = null

        override fun read(path: String): ByteArray {
            readError?.let { throw it }
            return remote.copyOf()
        }

        override fun hash(path: String): String = sha(remote)

        override fun publish(path: String, bytes: ByteArray, expectedSha256: String) {
            require(LoaderBundleTransaction.sha256(bytes) == expectedSha256) {
                "staged bytes do not match expected sha"
            }
            publishCount++
            if (failPublishBefore) throw IOException("temporary hash mismatch")
            remote = bytes.copyOf()
            if (failPublishAfter) {
                failPublishAfter = false
                throw IOException("post-publish verification failed")
            }
        }

        override fun stopGame() {
            stopError?.let { throw it }
            stopCount++
        }

        private fun sha(bytes: ByteArray) = LoaderBundleTransaction.sha256(bytes)
    }
}
