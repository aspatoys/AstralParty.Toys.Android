package com.hiynet.aspatoys.installer

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Wrapper wiring tests for [LoaderInstaller]: injected candidate order + mtime must be honored,
 * an unknown newest cache must never fall back to an older supported cache, and status must expose
 * the three-state classification without claiming an unknown hash is injected.
 *
 * The transaction itself (install/restore/atomic publication) is covered by
 * LoaderBundleTransactionTest and LoaderBundleTransactionRealBundleTest.
 */
class LoaderInstallerRecoveryTest {

    private lateinit var root: File
    private lateinit var io: FakeIo
    private lateinit var originalBytes: ByteArray
    private lateinit var patchedBytes: ByteArray

    private val remotePath =
        "/sdcard/Android/data/${LoaderInstaller.GAME_PACKAGE}/files/com.unity.addressables/" +
            "AssetBundles/${LoaderInstaller.BUNDLE_KEY}/0c6650c5e9eccffde61b8397d4694f72/__data"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("loader-installer-test").toFile()
        io = FakeIo()
        originalBytes = InstallerFixtures.original
        patchedBytes = InstallerFixtures.patched
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun installer(candidates: List<String> = listOf(remotePath)): LoaderInstaller =
        LoaderInstaller(
            context = FakeContext(root),
            bundleIo = io,
            originalSource = { originalBytes },
            candidatePaths = { candidates },
            serviceAvailable = { true },
            permissionGranted = { true },
        )

    private fun sha(bytes: ByteArray) = LoaderBundleTransaction.sha256(bytes)

    private fun seedNewestByMtime(names: List<String>): List<String> {
        // Real files, real mtimes; the production wrapper is expected to consume the supplied order.
        return names.mapIndexed { index, name ->
            val file = File(root, "$name/__data")
            file.parentFile.mkdirs()
            file.writeBytes(ByteArray(16) { (index + 1).toByte() })
            file.setLastModified(10_000L + index * 1_000L)
            file
        }.sortedByDescending { it.lastModified() }
            .map { "/sdcard/Android/data/${LoaderInstaller.GAME_PACKAGE}/files/com.unity.addressables/" +
                "AssetBundles/${LoaderInstaller.BUNDLE_KEY}/${it.parentFile.name}/__data" }
    }

    @Test
    fun statusMustNotCallAnUnknownHashInjected() {
        io.hashes[remotePath] = "aa".repeat(32)

        val status = installer().status()

        assertEquals("aa".repeat(32), status.bundleSha256)
        assertFalse("an unknown SHA-256 is not proof that the bundle was injected", status.injected)
        assertFalse("an unknown SHA-256 is not a supported version", status.supportedVersion)
        assertEquals("未知版本", status.cacheState)
        assertTrue(status.note.contains("未知缓存版本"))
    }

    @Test
    fun statusExposesOriginalInstalledAndUnknownThreeStates() {
        io.hashes[remotePath] = LoaderInstaller.ORIGINAL_SHA256
        assertEquals("原版", installer().status().cacheState)
        assertFalse(installer().status().injected)

        io.hashes[remotePath] = LoaderInstaller.PATCHED_SHA256
        val installed = installer().status()
        assertEquals("已安装", installed.cacheState)
        assertTrue(installed.injected)
        assertTrue(installed.supportedVersion)

        io.hashes[remotePath] = "bb".repeat(32)
        val unknown = installer().status()
        assertEquals("未知版本", unknown.cacheState)
        assertFalse(unknown.injected)
        assertFalse(unknown.supportedVersion)
    }

    @Test
    fun newestMtimeUnknownCacheIsSelectedAndDoesNotFallBackToOlderKnownCache() {
        val candidates = seedNewestByMtime(listOf("a".repeat(32), "b".repeat(32)))
        // seedNewestByMtime orders by real mtime descending, so newest is first.
        io.hashes[candidates[0]] = "cc".repeat(32)
        io.hashes[candidates[1]] = LoaderInstaller.ORIGINAL_SHA256

        val status = installer(candidates).status()

        assertEquals(candidates[0], status.bundlePath)
        assertEquals("cc".repeat(32), status.bundleSha256)
        assertEquals("未知版本", status.cacheState)
        assertFalse(status.supportedVersion)
        assertFalse(status.injected)
    }

    @Test
    fun newestMtimeKnownOriginalIsSelectedEvenWhenOlderNameSortsFirst() {
        val candidates = seedNewestByMtime(listOf("c".repeat(32), "d".repeat(32)))
        io.hashes[candidates[0]] = LoaderInstaller.ORIGINAL_SHA256
        io.hashes[candidates[1]] = LoaderInstaller.PATCHED_SHA256

        val status = installer(candidates).status()

        assertEquals(candidates[0], status.bundlePath)
        assertEquals(LoaderInstaller.ORIGINAL_SHA256, status.bundleSha256)
        assertEquals("原版", status.cacheState)
    }

    @Test
    fun statusHasBackupForKnownVersionAndPayloadFromIoRead() {
        io.hashes[remotePath] = LoaderInstaller.PATCHED_SHA256
        val canonical = File(root, "loader-installer/backups/${LoaderInstaller.ORIGINAL_SHA256}/__data.orig")
        canonical.parentFile.mkdirs()
        canonical.writeBytes(originalBytes)
        io.files["${LoaderInstaller.LOADER_ROOT}/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(1, 2, 3)

        val status = installer().status()

        assertTrue("a verified original backup is available", status.hasBackup)
        assertTrue("payload must be detected through BundleIo.read", status.loaderPayload)
    }

    @Test
    fun unknownVersionDoesNotAdvertiseBackupAvailability() {
        io.hashes[remotePath] = "dd".repeat(32)
        val canonical = File(root, "loader-installer/backups/${LoaderInstaller.ORIGINAL_SHA256}/__data.orig")
        canonical.parentFile.mkdirs()
        canonical.writeBytes(originalBytes)

        val status = installer().status()

        assertFalse("unknown versions must not expose a recovery source", status.hasBackup)
    }

    @Test
    fun serviceAndPermissionFailuresAreReportedWithoutResolvingCandidates() {
        var candidatesAsked = false
        val installer = LoaderInstaller(
            context = FakeContext(root),
            bundleIo = io,
            originalSource = { originalBytes },
            candidatePaths = { candidatesAsked = true; listOf(remotePath) },
            serviceAvailable = { false },
            permissionGranted = { true },
        )

        val status = installer.status()

        assertFalse(status.shizukuAlive)
        assertFalse(candidatesAsked)
        assertNotNull(status.note)
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        return dir
    }

    private class FakeIo : BundleIo {
        val files = mutableMapOf<String, ByteArray>()
        val hashes = mutableMapOf<String, String>()
        var stopCount = 0
        var publishCount = 0

        override fun read(path: String): ByteArray = files[path] ?: ByteArray(0)

        override fun hash(path: String): String = hashes[path] ?: sha(files[path] ?: ByteArray(0))

        override fun publish(path: String, bytes: ByteArray, expectedSha256: String) {
            require(sha(bytes) == expectedSha256)
            publishCount++
            files[path] = bytes.copyOf()
            hashes[path] = expectedSha256
        }

        override fun stopGame() {
            stopCount++
        }

        private fun sha(bytes: ByteArray) = LoaderBundleTransaction.sha256(bytes)
    }

    private class FakeContext(private val dir: File) : ContextWrapper(null as Context?) {
        override fun getExternalFilesDir(type: String?): File = dir
        override fun getFilesDir(): File = dir
    }
}
