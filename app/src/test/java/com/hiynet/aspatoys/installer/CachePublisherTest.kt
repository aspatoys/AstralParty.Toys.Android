package com.hiynet.aspatoys.installer

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/**
 * [CachePublisher] regressions against a fake remote cache that models the two Android/data shapes:
 *
 * 1. A writable bundle directory: sibling temp files can be created and renamed onto `__data`.
 * 2. A restricted bundle directory (the reported test.4 EPIPE environment): the existing `__data`
 *    inode accepts writes, but creating a sibling file inside the bundle directory or renaming onto
 *    `__data` fails with EACCES. The game's own `files/CesiumLoader/data/transport` area is still
 *    writable, which is what the compatibility publisher uses for its verified copies.
 *
 * Assertions are deliberately narrow: a non-permission error must propagate instead of silently
 * downgrading, and a partially written cache must be restored to its exact previous bytes.
 */
class CachePublisherTest {

    private lateinit var remote: FakeRemote
    private lateinit var journalDir: File
    private lateinit var publisher: CachePublisher

    private val original = ByteArray(4096) { (it * 7 + 3).toByte() }
    private val patched = ByteArray(5000) { (it * 11 + 5).toByte() }
    private val originalSha = sha(original)
    private val patchedSha = sha(patched)

    private val bundleDir =
        "/sdcard/Android/data/${LoaderInstaller.GAME_PACKAGE}/files/com.unity.addressables/" +
            "AssetBundles/${LoaderInstaller.BUNDLE_KEY}/0c6650c5e9eccffde61b8397d4694f72"
    private val path = "$bundleDir/__data"
    private val transportArea =
        "/sdcard/Android/data/${LoaderInstaller.GAME_PACKAGE}/files/CesiumLoader/data/transport"

    @Before
    fun setUp() {
        remote = FakeRemote()
        remote.files[path] = original.copyOf()
        journalDir = Files.createTempDirectory("cache-journal").toFile()
        publisher = CachePublisher(remote, journalDir)
    }

    @After
    fun tearDown() {
        journalDir.deleteRecursively()
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun journals() = journalDir.listFiles().orEmpty().toList()

    // ---------------------------------------------------------------- normal atomic path

    @Test
    fun normalPublishUsesSiblingTempAndRename() {
        publisher.publish(path, patched, patchedSha)

        assertArrayEquals(patched, remote.files[path])
        assertEquals("the sibling rename path must be used, not the fallback", 1, remote.moveCount)
        assertEquals("no compatibility copies may be created", 0, remote.copyIntoExistingCount)
        assertTrue("the temp file must be gone", remote.files.keys.none { "cesium-stage" in it })
        assertTrue("no journal is needed for a successful rename", journals().isEmpty())
    }

    @Test
    fun publishIsIdempotentWhenTheTargetAlreadyHasTheExpectedHash() {
        remote.files[path] = patched.copyOf()

        publisher.publish(path, patched, patchedSha)

        assertEquals("an already-published cache must not be rewritten", 0, remote.moveCount)
        assertEquals(0, remote.writeCount)
    }

    @Test
    fun publishRejectsPayloadWhoseHashDoesNotMatchTheExpectedValue() {
        val wrong = sha(patched.copyOf().also { it[0] = (it[0] + 1).toByte() })

        assertThrows(IllegalArgumentException::class.java) { publisher.publish(path, patched, wrong) }
        assertArrayEquals("a rejected payload must not touch the cache", original, remote.files[path])
        assertEquals(0, remote.writeCount)
    }

    // ------------------------------------------------- restricted directory: the real fallback

    @Test
    fun restrictedDirectoryFallsBackToVerifiedCopiesOnTheExistingInode() {
        remote.restrictBundleDirectory = true

        publisher.publish(path, patched, patchedSha)

        assertArrayEquals("the existing inode must end up with the new payload", patched, remote.files[path])
        assertEquals("no rename may succeed inside a restricted directory", 0, remote.moveCount)
        assertEquals("the fallback writes onto __data", 1, remote.copyIntoExistingCount)
        assertTrue("the temp file must be cleaned up", remote.files.keys.none { "cesium-stage" in it })
        assertTrue("a completed publish must not leave a journal behind", journals().isEmpty())
        assertTrue("staged copies must be cleaned up after success",
            remote.files.keys.none { it.startsWith(transportArea) })
    }

    @Test
    fun restrictedFallbackWritesBothCopiesBeforeOpeningTheTargetAndVerifiesThem() {
        remote.restrictBundleDirectory = true
        val observed = mutableListOf<String>()
        remote.onWrite = { observed.add(it) }

        remote.onCopyIntoExisting = { from, to ->
            assertEquals("write-back must use the verified after copy", "$transportArea", from.substringBeforeLast('/'))
            assertTrue("before copy missing", remote.files.keys.any { it.endsWith(".before") })
            assertTrue("after copy missing", remote.files.keys.any { it.endsWith(".after") })
            assertEquals(path, to)
        }

        publisher.publish(path, patched, patchedSha)

        assertTrue("the copies must have been staged", observed.any { it.endsWith(".before") })
        assertTrue("the copies must have been staged", observed.any { it.endsWith(".after") })
        assertArrayEquals(patched, remote.files[path])
    }

    @Test
    fun nonPermissionErrorNeverDowngradesToTheCompatibilityPath() {
        remote.restrictBundleDirectory = true
        // EIO is not a permission denial: the publisher must rethrow instead of guessing.
        remote.hiddenError = IOException("Input/output error")

        val error = assertThrows(IOException::class.java) { publisher.publish(path, patched, patchedSha) }

        assertTrue(error.message.orEmpty().contains("Input/output error"))
        assertEquals("the compatibility path must not run", 0, remote.copyIntoExistingCount)
        assertArrayEquals("the cache must be untouched", original, remote.files[path])
        assertTrue("no stage copies may leak into the transport area",
            remote.files.keys.none { it.startsWith(transportArea) })
    }

    @Test
    fun permissionErrorInsideTheCompatibilityPathStillFails() {
        remote.restrictBundleDirectory = true
        remote.denyTransportWrites = true

        val error = assertThrows(IOException::class.java) { publisher.publish(path, patched, patchedSha) }

        assertTrue("failure must be reported, got: ${error.message}",
            error.message.orEmpty().contains("Permission denied"))
        assertArrayEquals("the cache must survive a failed staging attempt", original, remote.files[path])
        assertTrue("a failed staging must not leave a journal", journals().isEmpty())
    }

    // ------------------------------------------------------------- stream break + rollback

    @Test
    fun interruptedWriteBackRollsTheCacheBackToTheOriginalBytes() {
        remote.restrictBundleDirectory = true
        remote.breakAfterCopy = "写入通道失败（退出码 13）：cat: Permission denied"

        val error = assertThrows(IllegalStateException::class.java) {
            publisher.publish(path, patched, patchedSha)
        }

        assertTrue("the original failure must be kept as cause",
            error.cause?.message.orEmpty().contains("Permission denied"))
        assertTrue("message must state the rollback succeeded: ${error.message}",
            error.message.orEmpty().contains("已恢复操作前缓存"))
        assertArrayEquals("the cache must be byte-identical to before", original, remote.files[path])
        assertTrue("a completed rollback must clear the journal", journals().isEmpty())
    }

    @Test
    fun truncatedWriteBackDetectedByHashAlsoRollsBack() {
        remote.restrictBundleDirectory = true
        // No exception at all: the shell exits 0 but only part of the payload landed.
        remote.silentTruncate = true

        assertThrows(IllegalStateException::class.java) { publisher.publish(path, patched, patchedSha) }

        assertArrayEquals("a silent truncation must still be rolled back", original, remote.files[path])
        assertTrue(journals().isEmpty())
    }

    @Test
    fun writeBackThatFailsAfterLandingTheWholeAfterCopyStillRollsBackToTheOriginal() {
        remote.restrictBundleDirectory = true
        // The copy landed completely, but the channel then reported a failure.
        remote.breakAfterCopy = "connection reset"

        val error = assertThrows(IllegalStateException::class.java) {
            publisher.publish(path, patched, patchedSha)
        }

        assertTrue("failure must be reported: ${error.message}",
            error.message.orEmpty().contains("已恢复操作前缓存"))
        assertArrayEquals("an unconfirmed publish must not be left in place", original, remote.files[path])
    }

    @Test
    fun rollbackRefusesToOverwriteAnExternallyChangedCacheAndKeepsTheJournal() {
        remote.restrictBundleDirectory = true
        // Somebody else replaced the file with unrelated content while we were writing.
        remote.replaceTargetOnCopy = ByteArray(64) { 0x5A }

        val error = assertThrows(IllegalStateException::class.java) {
            publisher.publish(path, patched, patchedSha)
        }

        assertTrue("must refuse to clobber external content: ${error.message}",
            error.message.orEmpty().contains("回滚未完成"))
        assertTrue("the reason must name the external change: ${error.message}",
            error.message.orEmpty().contains("外部变化"))
        assertArrayEquals("external content must be preserved", ByteArray(64) { 0x5A }, remote.files[path])
        assertFalse("the recovery record must be kept", journals().isEmpty())
    }

    // ------------------------------------------------------------- crash + restart recovery

    @Test
    fun crashAfterJournalIsRecoveredOnTheNextRead() {
        remote.restrictBundleDirectory = true
        // Die (Error, not Exception) right after the journal is durable and a prefix landed.
        remote.throwOnCopy = OutOfMemoryError("simulated process death")
        runCatching { publisher.publish(path, patched, patchedSha) }
        assertFalse("the crash must leave a recovery record", journals().isEmpty())

        // A fresh publisher (new process) must recover before returning the cache content.
        val restarted = CachePublisher(remote, journalDir)
        restarted.recover(path)

        assertArrayEquals("restart must restore the pre-publish bytes", original, remote.files[path])
        assertTrue("recovery must clean up the journal", journals().isEmpty())
    }

    @Test
    fun recoveryRejectsATamperedBeforeCopyAndKeepsTheRecord() {
        val record = seedInterruptedJournal()
        // Tamper the before copy so it no longer matches the journal hash.
        remote.files[record.beforeFile] = original.copyOf().also { it[0] = (it[0] + 1).toByte() }

        val error = assertThrows(IllegalArgumentException::class.java) {
            CachePublisher(remote, journalDir).recover(path)
        }

        assertTrue("must report the failed copy verification: ${error.message}",
            error.message.orEmpty().contains("恢复副本校验失败"))
        assertFalse("the record must survive", journals().isEmpty())
    }

    @Test
    fun recoveryRejectsATamperedJournalPathAndKeepsTheRecord() {
        val record = seedInterruptedJournal()
        record.journalFile.writeText(record.journalFile.readText().replace(record.beforeFile, "/sdcard/evil.before"))

        val error = assertThrows(IllegalArgumentException::class.java) {
            CachePublisher(remote, journalDir).recover(path)
        }

        assertTrue("must reject a stage path outside the transport area: ${error.message}",
            error.message.orEmpty().contains("恢复文件路径无效"))
        assertFalse(journals().isEmpty())
    }

    @Test
    fun recoveryRejectsAJournalBoundToAnotherTarget() {
        val record = seedInterruptedJournal()
        val text = record.journalFile.readText()
        val rebound = text.replace(Regex("(?m)^target=.*$"), "target=/sdcard/other")
        assertTrue("the journal must contain a target line", rebound != text)
        record.journalFile.writeText(rebound)

        val error = assertThrows(IllegalArgumentException::class.java) {
            CachePublisher(remote, journalDir).recover(path)
        }

        assertTrue("must not apply a foreign journal: ${error.message}",
            error.message.orEmpty().contains("恢复记录路径不匹配"))
        assertFalse(journals().isEmpty())
    }

    @Test
    fun recoveryRefusesToOverwriteAnExternallyNewerVersion() {
        val record = seedInterruptedJournal()
        val newer = ByteArray(2048) { 0x77 }
        remote.files[path] = newer

        val error = assertThrows(IllegalArgumentException::class.java) {
            CachePublisher(remote, journalDir).recover(path)
        }

        assertTrue("must refuse an unknown external version: ${error.message}",
            error.message.orEmpty().contains("外部变化"))
        assertArrayEquals("the newer cache must be preserved", newer, remote.files[path])
        assertFalse("the recovery record must be kept", journals().isEmpty())
    }

    @Test
    fun recoveryRollsBackAnInterruptedPrefix() {
        val record = seedInterruptedJournal()
        // The interrupted copy left a prefix of the after bytes on the real target.
        remote.files[path] = patched.copyOf(patched.size / 2)

        CachePublisher(remote, journalDir).recover(path)

        assertArrayEquals(original, remote.files[path])
        assertTrue(journals().isEmpty())
        assertTrue("staged copies must be removed", remote.files.keys.none { it.startsWith(transportArea) })
        assertTrue(record.journalFile.parentFile!!.exists())
    }

    @Test
    fun recoveryCleansUpWhenTheTargetAlreadyReachedTheAfterState() {
        val record = seedInterruptedJournal()
        remote.files[path] = patched.copyOf()

        CachePublisher(remote, journalDir).recover(path)

        assertArrayEquals("a fully written cache stays patched", patched, remote.files[path])
        assertTrue(journals().isEmpty())
        assertEquals("no rollback copy may be needed", 0, remote.copyIntoExistingCount)
    }

    // ------------------------------------------------------------- real bundles (no fixtures, real hashes)

    @Test
    fun realBundlePublishesThroughTheCompatibilityPathWhenTheDirectoryIsRestricted() {
        val real = realBundles() ?: return
        remote.files[path] = real.original.copyOf()
        remote.restrictBundleDirectory = true

        CachePublisher(remote, journalDir).publish(path, real.patched, LoaderInstaller.PATCHED_SHA256)

        assertArrayEquals("the game cache must hold the patched bundle", real.patched, remote.files[path])
        assertEquals("a restricted directory forbids the rename path", 0, remote.moveCount)
        assertEquals(1, remote.copyIntoExistingCount)
        assertTrue(journals().isEmpty())
        assertTrue(remote.files.keys.none { it.startsWith(transportArea) })
    }

    @Test
    fun realBundleUsesTheAtomicRenamePathWhenTheDirectoryIsWritable() {
        val real = realBundles() ?: return
        remote.files[path] = real.original.copyOf()

        CachePublisher(remote, journalDir).publish(path, real.patched, LoaderInstaller.PATCHED_SHA256)

        assertArrayEquals(real.patched, remote.files[path])
        assertEquals(1, remote.moveCount)
        assertEquals(0, remote.copyIntoExistingCount)
        assertTrue(journals().isEmpty())
    }

    @Test
    fun realBundleRefusesAnUnknownCacheVersionBeforeAnyWrite() {
        val real = realBundles() ?: return
        remote.files[path] = ByteArray(1024) { 0x42 }
        val writesBefore = remote.writeCount

        val error = assertThrows(IllegalArgumentException::class.java) {
            CachePublisher(remote, journalDir).publish(path, real.patched, LoaderInstaller.PATCHED_SHA256)
        }

        assertTrue("must refuse to overwrite an unknown bundle version: ${error.message}",
            error.message.orEmpty().contains("版本发生变化"))
        assertEquals("nothing may be written", writesBefore, remote.writeCount)
    }

    @Test
    fun realBundleRollsBackToTheByteIdenticalOriginalAfterAStreamBreak() {
        val real = realBundles() ?: return
        remote.files[path] = real.original.copyOf()
        remote.restrictBundleDirectory = true
        remote.breakAfterCopy = "写入通道失败（退出码 32）：Broken pipe"

        val error = assertThrows(IllegalStateException::class.java) {
            CachePublisher(remote, journalDir).publish(path, real.patched, LoaderInstaller.PATCHED_SHA256)
        }

        assertTrue("the broken-pipe text must survive: ${error.message}",
            error.message.orEmpty().contains("Broken pipe"))
        assertEquals("the cache must be restored to the original size",
            real.original.size, remote.files.getValue(path).size)
        assertEquals("the cache must be restored byte for byte",
            sha(real.original), sha(remote.files.getValue(path)))
        assertTrue(journals().isEmpty())
    }

    // ------------------------------------------------------------- channel sharing

    @Test
    fun rootAndShizukuShareTheSamePublisherBehaviour() {
        // Both privilege modes are only a CacheTransport; the publisher must not branch on one.
        for (label in listOf("root-like", "shizuku-like")) {
            val each = FakeRemote().apply {
                files[path] = original.copyOf()
                restrictBundleDirectory = true
            }
            val dir = Files.createTempDirectory("cache-journal-$label").toFile()
            try {
                CachePublisher(each, dir).publish(path, patched, patchedSha)
                assertArrayEquals("$label: fallback publish must work", patched, each.files[path])
                assertTrue("$label: journal cleaned", dir.listFiles().isNullOrEmpty())
                assertTrue("$label: copies cleaned", each.files.keys.none { it.startsWith(transportArea) })
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun invalidRemotePathIsRejectedOnTheCompatibilityPath() {
        // Path validation lives in area(), which only the fallback and recover() consult; a
        // non-conforming path therefore fails once the atomic rename is unavailable.
        val bad = "/sdcard/Download/__data"
        remote.files[bad] = original.copyOf()
        remote.restrictBundleDirectory = true

        val error = assertThrows(IllegalArgumentException::class.java) {
            publisher.publish(bad, patched, patchedSha)
        }

        assertTrue("expected a path refusal, got: ${error.message}",
            error.message.orEmpty().contains("缓存路径无效"))
        assertArrayEquals("the target must be untouched", original, remote.files[bad])
    }

    // ------------------------------------------------------------- helpers

    private class SeededJournal(val journalFile: File, val beforeFile: String, val afterFile: String)

    /**
     * Writes a durable journal plus both stage copies representing a publish interrupted while the
     * target holds a half-written after-copy prefix.
     */
    private fun seedInterruptedJournal(): SeededJournal {
        val id = "11111111-2222-3333-4444-555555555555"
        val beforeFile = "$transportArea/$id.before"
        val afterFile = "$transportArea/$id.after"
        remote.files[beforeFile] = original.copyOf()
        remote.files[afterFile] = patched.copyOf()
        remote.files[path] = patched.copyOf(patched.size / 2)
        val journalFile = File(journalDir, sha(path.toByteArray()) + ".properties")
        journalDir.mkdirs()
        val props = Properties().apply {
            setProperty("target", path)
            setProperty("before", originalSha)
            setProperty("after", patchedSha)
            setProperty("beforeFile", beforeFile)
            setProperty("afterFile", afterFile)
        }
        val out = ByteArrayOutputStream().also { props.store(it, "Cesium cache recovery") }
        journalFile.writeBytes(out.toByteArray())
        return SeededJournal(journalFile, beforeFile, afterFile)
    }

    private class RealBundles(val original: ByteArray, val patched: ByteArray)

    /** Rebuild from this APK's verified assets so CI cannot silently skip real-cache coverage. */
    private fun realBundles(): RealBundles? {
        val ob = InstallerFixtures.original
        val pb = InstallerFixtures.patched
        assertEquals("fixture must be the original bundle", LoaderInstaller.ORIGINAL_SHA256, sha(ob))
        assertEquals("fixture must be the patched bundle", LoaderInstaller.PATCHED_SHA256, sha(pb))
        assertNotNull(journalDir)
        return RealBundles(ob, pb)
    }

    private fun findUpward(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Remote-cache model. `restrictBundleDirectory` reproduces the Android/data limits: creating a
     * sibling inside the bundle directory and renaming onto `__data` fail with EACCES, while
     * `copyIntoExisting` on the existing `__data` inode and writes into the game's own transport
     * area still succeed.
     */
    private class FakeRemote : CacheTransport {
        val files = mutableMapOf<String, ByteArray>()
        var restrictBundleDirectory = false
        var denyTransportWrites = false
        var hiddenError: Exception? = null
        var breakAfterCopy: String? = null
        var silentTruncate = false
        var replaceTargetOnCopy: ByteArray? = null
        var throwOnCopy: Throwable? = null
        var moveCount = 0
        var writeCount = 0
        var copyIntoExistingCount = 0
        var onWrite: ((String) -> Unit)? = null
        var onCopyIntoExisting: ((String, String) -> Unit)? = null

        private fun restricted(path: String) = restrictBundleDirectory && "/__data.cesium-stage-" in path

        private fun bundleDirOf(path: String) = path.substringBeforeLast('/')

        override fun read(path: String): ByteArray =
            files[path]?.copyOf() ?: throw IOException("cat: $path: No such file or directory")

        override fun hash(path: String): String {
            val bytes = files[path] ?: throw IOException("sha256sum: $path: No such file or directory")
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }

        override fun write(path: String, bytes: ByteArray) {
            hiddenError?.let { throw it }
            if (denyTransportWrites && path.startsWith("/sdcard/") && "CesiumLoader/data/transport" in path) {
                throw IOException("cat: $path: Permission denied")
            }
            if (restricted(path)) throw IOException("cat: $path: Permission denied")
            writeCount++
            onWrite?.invoke(path)
            files[path] = bytes.copyOf()
        }

        override fun move(from: String, to: String) {
            hiddenError?.let { throw it }
            if (restrictBundleDirectory && to.endsWith("/__data")) {
                throw IOException("mv: $from: Permission denied")
            }
            val bytes = files.remove(from) ?: throw IOException("mv: $from: No such file")
            moveCount++
            files[to] = bytes
        }

        override fun copyIntoExisting(from: String, to: String) {
            val bytes = files[from] ?: throw IOException("cat: $from: No such file or directory")
            copyIntoExistingCount++
            onCopyIntoExisting?.let { it(from, to) }
            // Injected failures are one-shot events on the publish write-back: the rollback copy
            // that follows must be allowed to succeed, otherwise the test would only prove that a
            // broken transport stays broken.
            val thrown = throwOnCopy
            val replaced = replaceTargetOnCopy
            val truncate = silentTruncate
            val broken = breakAfterCopy
            throwOnCopy = null
            replaceTargetOnCopy = null
            silentTruncate = false
            breakAfterCopy = null
            if (thrown != null) {
                replaced?.let { files[to] = it.copyOf() }
                    ?: run { files[to] = bytes.copyOf(bytes.size / 2) }
                throw thrown
            }
            replaced?.let { files[to] = it.copyOf(); return }
            if (truncate) { files[to] = bytes.copyOf(bytes.size - 1); return }
            broken?.let { error ->
                files[to] = bytes.copyOf()
                throw IOException(error)
            }
            files[to] = bytes.copyOf()
        }

        override fun mkdir(path: String) { /* idempotent */ }

        override fun delete(path: String) { files.remove(path) }

        @Suppress("unused")
        private fun dirOf(path: String) = bundleDirOf(path)
    }
}
