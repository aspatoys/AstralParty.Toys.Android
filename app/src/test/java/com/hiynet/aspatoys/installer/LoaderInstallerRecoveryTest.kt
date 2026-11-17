package com.hiynet.aspatoys.installer

import android.content.Context
import com.hiynet.aspatoys.data.loader.LoaderFs
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

    private fun installer(
        candidates: List<String> = listOf(remotePath),
        loaderFs: LoaderFs = FakeLoaderFs(),
    ): LoaderInstaller =
        LoaderInstaller(
            context = FakeContext(root),
            bundleIo = io,
            originalSource = { originalBytes },
            candidatePaths = { candidates },
            serviceAvailable = { true },
            permissionGranted = { true },
            loaderFs = loaderFs,
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

    @Test
    fun disablingRestoresOnlyTheEntryPointAndDeletesNoPayload() {
        val fs = FakeLoaderFs().apply {
            files["${LoaderInstaller.LOADER_ROOT}/sdk/CesiumLoader.SDK.dll"] = byteArrayOf(1, 2, 3)
            files["${LoaderInstaller.LOADER_ROOT}/mods/FontMod/FontMod.dll"] = byteArrayOf(4, 5)
            files["${LoaderInstaller.LOADER_ROOT}/configs/loader.json"] = """{"theme":"dark"}""".toByteArray()
        }
        val before = fs.files.mapValues { it.value.copyOf() }
        io.files[remotePath] = patchedBytes
        io.hashes[remotePath] = LoaderInstaller.PATCHED_SHA256

        installer(loaderFs = fs).setEnabled(false) { }

        assertEquals("disable only writes the original bundle back", LoaderInstaller.ORIGINAL_SHA256, io.hashes[remotePath])
        assertEquals("the game bundle is restored exactly once", 1, io.publishCount)
        assertEquals("no loader file may be deleted by disable", 0, fs.deletes)
        assertEquals(before.keys, fs.files.keys)
        before.forEach { (path, bytes) -> assertTrue("$path must stay byte-identical", bytes.contentEquals(fs.files[path])) }
    }

    @Test
    fun enablingAnAlreadyInjectedBundleNeverPublishesTheBundledSdkOverTheOnlineOne() {
        val fs = FakeLoaderFs()
        val onlineSdk = byteArrayOf(9, 9, 9, 9)
        io.files["${LoaderInstaller.LOADER_ROOT}/sdk/CesiumLoader.SDK.dll"] = onlineSdk
        io.files[remotePath] = patchedBytes
        io.hashes[remotePath] = LoaderInstaller.PATCHED_SHA256

        installer(loaderFs = fs).setEnabled(true) { }

        assertEquals("re-enabling must not rewrite the game bundle", 0, io.publishCount)
        assertEquals(LoaderInstaller.PATCHED_SHA256, io.hashes[remotePath])
        assertTrue(
            "the online SDK must survive re-enable",
            onlineSdk.contentEquals(io.files["${LoaderInstaller.LOADER_ROOT}/sdk/CesiumLoader.SDK.dll"]),
        )
        assertEquals(0, fs.deletes)
    }

    @Test
    fun enablingWithoutPayloadIsRefusedBeforeTouchingTheBundle() {
        io.files[remotePath] = originalBytes
        io.hashes[remotePath] = LoaderInstaller.ORIGINAL_SHA256

        val failure = runCatching { installer().setEnabled(true) { } }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("请先安装内置加载器", failure?.message)
        assertEquals(0, io.publishCount)
    }

    private class FakeLoaderFs : LoaderFs {
        val files = linkedMapOf<String, ByteArray>()
        val directories = linkedSetOf<String>()
        var deletes = 0

        private fun gone(path: String) {
            files.keys.removeAll { it == path || it.startsWith("$path/") }
            directories.removeAll { it == path || it.startsWith("$path/") }
        }

        override fun isAvailable() = true
        override fun exists(path: String) = path in files || path in directories
        override fun readText(path: String) = files[path]?.toString(Charsets.UTF_8)
        override fun writeText(path: String, text: String) { files[path] = text.toByteArray() }
        override fun readBytes(path: String) = files[path]
        override fun fileSize(path: String) = files[path]?.size?.toLong() ?: 0L
        override fun listDirectories(path: String) = directories.filter { it.startsWith("$path/") }
            .map { it.removePrefix("$path/").substringBefore('/') }.distinct()
        override fun listFiles(path: String) = files.keys.filter { it.startsWith("$path/") }
            .map { it.removePrefix("$path/") }.filter { !it.contains('/') }
        override fun makeDirectories(path: String) { directories += path }
        override fun move(from: String, to: String) {
            val bytes = files.remove(from) ?: error("move 源不存在：$from")
            files[to] = bytes
        }
        override fun copyIn(localFile: File, remotePath: String) { files[remotePath] = localFile.readBytes() }
        override fun delete(path: String) { deletes++; gone(path) }
        override val lastError: String? = null
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
