package com.hiynet.aspatoys.installer

import com.hiynet.cesiumloader.core.AndroidPaths
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChannelRuntimeCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    private val original = byteArrayOf(2, 4, 6)
    private val unknown = byteArrayOf(11, 13, 17, 19, 23)
    private val matching = byteArrayOf(37, 41, 43)
    private val closest = byteArrayOf(47, 53, 59, 61, 67)
    private val patched = byteArrayOf(71, 73, 79)
    private var read = ""
    private val references = listOf(
        ChannelRuntimeCompatibility.Reference("matched", hash(original), original.size, hash(matching)),
        ChannelRuntimeCompatibility.Reference("closest", "f".repeat(64), closest.size, hash(closest)),
    )

    @Test
    fun currentRuntimeIsPreferredAndStrictModeRejectsUnknownRuntime() {
        val policy = policy { patched }
        assertArrayEquals(patched, policy.patch(unknown, true) {})
        assertEquals("", read)
        assertThrows(IllegalArgumentException::class.java) { policy.patch(unknown, false) {} }
        assertArrayEquals(patched, policy.patch(original, false) {})
    }

    @Test
    fun exactReferenceWinsAndUnknownRuntimeUsesClosestSizeOnlyWhenEnabled() {
        val policy = policy { throw UnsupportedRuntimeEdit("test") }
        val log = mutableListOf<String>()
        assertArrayEquals(matching, policy.patch(original, false, log::add))
        assertEquals("matched", read)
        assertTrue(log.last().contains("匹配程序集指纹"))

        assertArrayEquals(closest, policy.patch(unknown, true, log::add))
        assertEquals("closest", read)
        assertTrue(log.last().contains("按程序集大小选择"))
    }

    @Test
    fun existingLoaderOrOtherImportFailureDoesNotTriggerFallback() {
        val policy = policy { error("已包含加载器入口") }
        assertThrows(IllegalStateException::class.java) { policy.patch(unknown, true) {} }
        assertEquals("", read)
    }

    @Test
    fun corruptedReferenceFailsBeforeRetargeting() {
        val policy = ChannelRuntimeCompatibility(references, { unknown },
            { throw UnsupportedRuntimeEdit("test") }, { error("unexpected retarget") })
        assertThrows(IllegalArgumentException::class.java) { policy.patch(original, true) {} }
    }

    @Test
    fun matchingOfficialDigestStillUsesChannelPatchAndChannelBackup() {
        val channel = "com.feimo.astralparty.haoyou"
        val remote = "/sdcard/Android/data/$channel/files/cache/__data"
        var live = original
        var writes = 0
        val io = object : BundleIo {
            override fun read(path: String) = live
            override fun hash(path: String) = hash(live)
            override fun stopGame() = Unit
            override fun publish(path: String, bytes: ByteArray, expectedSha256: String) {
                assertEquals(remote, path)
                assertEquals(expectedSha256, hash(bytes))
                live = bytes
                writes++
            }
        }
        val transaction = LoaderBundleTransaction(
            temporary.newFolder("channel"), io, { error("unexpected official fallback") },
            { _, _ -> error("unexpected official patch") }, {}, hash(original), hash(matching),
            { _, _ -> patched }, useLegacyProfile = false,
        )
        assertFalse(transaction.backupAvailable(hash(original), remote))
        transaction.install(remote)
        transaction.install(remote)
        assertEquals(1, writes)
        assertEquals(LoaderBundleTransaction.State.INSTALLED, transaction.classify(hash(patched), remote))
        assertEquals(LoaderBundleTransaction.State.UNKNOWN,
            transaction.classify(hash(patched), remote.replace(channel, LoaderInstaller.GAME_PACKAGE)))
        assertEquals(hash(original), transaction.restore(remote))
        assertArrayEquals(original, live)
    }

    @Test
    fun channelNamesCannotEscapeTheGameDirectory() {
        assertEquals("/sdcard/Android/data/com.feimo.jxpd.vendor/files/CesiumLoader",
            AndroidPaths.gameLoaderDir("com.feimo.jxpd.vendor").path.replace('\\', '/'))
        assertEquals("/sdcard/Android/data/com.feimo.astralparty.bilibili/files/CesiumLoader",
            AndroidPaths.gameLoaderDir("com.feimo.astralparty.bilibili").path.replace('\\', '/'))
        assertEquals("/sdcard/Android/data/com.feimo.astralparty.nearme.gamecenter/files/CesiumLoader",
            AndroidPaths.gameLoaderDir("com.feimo.astralparty.nearme.gamecenter").path.replace('\\', '/'))
        assertEquals("/sdcard/Android/data/com.jxpd.feimo.m4399/files/CesiumLoader",
            AndroidPaths.gameLoaderDir("com.jxpd.feimo.m4399").path.replace('\\', '/'))
        assertFalse(AndroidPaths.isGamePackage("com.feimo.astralparty."))
        assertFalse(AndroidPaths.isGamePackage("com.feimo.astralparty../other"))
        assertFalse(AndroidPaths.isGamePackage("com.feimo.astralparty.jpn"))
    }

    @Test
    fun installedGamePackagesFindsReversedJxpdFeimoAndChannelPackages() {
        val pmOutput = """
            package:com.android.settings
            package:com.hiynet.aspatoys
            package:com.jxpd.feimo.m4399
            package:com.feimo.astralparty.bilibili
            package:com.feimo.astralparty.jpn
            package:com.tencent.tmgp.jxpd
        """.trimIndent()
        val found = LoaderInstaller.installedGamePackages { cmd ->
            if (cmd == "pm list packages --user current") pmOutput else error("unexpected cmd: $cmd")
        }
        assertEquals(
            listOf("com.feimo.astralparty.bilibili", "com.jxpd.feimo.m4399", "com.tencent.tmgp.jxpd"),
            found,
        )
    }

    @Test
    fun installedGamePackagesFallsBackWhenCurrentUserFlagFails() {
        val found = LoaderInstaller.installedGamePackages { cmd ->
            when (cmd) {
                "pm list packages --user current" -> error("unknown option --user")
                "pm list packages" -> "package:com.jxpd.feimo.m4399\npackage:com.android.systemui\n"
                else -> error("unexpected cmd: $cmd")
            }
        }
        assertEquals(listOf("com.jxpd.feimo.m4399"), found)
    }


    @Test
    fun autoSelectPackagePrefersInstalledChannelWhenOfficialIsAbsent() {
        assertEquals(
            "com.feimo.astralparty.bilibili",
            LoaderInstaller.autoSelectPackage(
                installed = listOf("com.feimo.astralparty.bilibili"),
                current = LoaderInstaller.GAME_PACKAGE,
                selected = null,
            ),
        )
        assertEquals(
            "com.feimo.astralparty.bilibili",
            LoaderInstaller.autoSelectPackage(
                installed = listOf("com.feimo.astralparty.bilibili", "com.feimo.astralparty.haoyou"),
                current = LoaderInstaller.GAME_PACKAGE,
                selected = null,
            ),
        )
        assertEquals(
            null,
            LoaderInstaller.autoSelectPackage(
                installed = listOf(LoaderInstaller.GAME_PACKAGE, "com.feimo.astralparty.bilibili"),
                current = LoaderInstaller.GAME_PACKAGE,
                selected = null,
            ),
        )
        assertEquals(
            null,
            LoaderInstaller.autoSelectPackage(
                installed = listOf("com.feimo.astralparty.bilibili"),
                current = LoaderInstaller.GAME_PACKAGE,
                selected = LoaderInstaller.GAME_PACKAGE,
            ),
        )
    }

    private fun policy(edit: (ByteArray) -> ByteArray) = ChannelRuntimeCompatibility(references, { id ->
        read = id
        if (id == "matched") matching else closest
    }, edit, { it })

    private fun hash(bytes: ByteArray) = LoaderBundleTransaction.sha256(bytes)
}
