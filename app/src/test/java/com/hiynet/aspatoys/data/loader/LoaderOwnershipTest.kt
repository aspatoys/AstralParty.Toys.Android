package com.hiynet.aspatoys.data.loader

import com.hiynet.aspatoys.installer.LoaderOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two channel shapes, two different invariants.
 *
 * Root can `chown`, so it must still prove `st_uid == game uid` everywhere: a root-owned inode
 * inside the game's tree is unreadable for the game and the failure is silent (the injected
 * `<Module>.cctor` swallows the exception), which is why that check stays strict.
 *
 * A plain Shizuku channel (uid 2000) can never chown, so `st_uid` says nothing about whether the
 * game can read the file. Device evidence from a 24129PN74C / Android 16: the boot log the game
 * itself wrote reports `mods found: 24 / done: ok=24 failed=0` while every DLL it loaded was
 * `2000 1078 660`. So on that channel the only honest checks are "the channel can reach the path"
 * (probed with `test -r` / `test -x`) and "the bytes we wrote are the bytes on disk" (SHA-256,
 * enforced by the transport). Neither of those may be reported as proof that the game loaded them.
 */
class LoaderOwnershipTest {

    @Test
    fun unreadableOwnershipMustNotPassVerification() {
        val owner = LoaderOwnership(exec = { command ->
            when {
                command == "id -u" -> "0"
                command.startsWith("stat -c '%u %g'") -> "10331 1078"
                else -> throw IllegalStateException("Permission denied")
            }
        })
        assertTrue("missing/unreadable bootstrap must fail", owner.verify(listOf("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/bootstrap/x.dll")) != null)
    }

    private class RecordingExec(private val uid: Int = 0, private val accessible: Boolean = true) {
        val commands = mutableListOf<String>()

        fun run(script: String): String {
            commands += script
            return when {
                script.startsWith("id -u") -> uid.toString()
                script.startsWith("stat -c '%u %g'") -> "10331 1078"
                script.startsWith("stat -c %u") -> "10331"
                script.startsWith("if [ -d ") -> if (accessible) "accessible" else ""
                else -> ""
            }
        }

        fun chowns() = commands.filter { it.startsWith("chown ") }
    }

    // ---- root channel: unchanged, strict ----

    @Test
    fun rootChannelResolvesGameIdsFromTheDataDirectory() {
        val exec = RecordingExec()
        val owner = LoaderOwnership(exec = exec::run)

        assertEquals(LoaderOwnership.Ids(10331, 1078), owner.ids())
        assertTrue(owner.isSupported())
        assertTrue(exec.commands.any { it.contains("/sdcard/Android/data/com.feimo.astralparty") })
    }

    @Test
    fun adoptSinglePathUsesGameUidAndGid() {
        val exec = RecordingExec()
        val owner = LoaderOwnership(exec = exec::run)

        assertTrue(owner.adopt("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/bootstrap/x.dll"))

        val chown = exec.chowns().single()
        assertTrue(chown.startsWith("chown 10331:1078 "))
        assertTrue(chown.contains("bootstrap/x.dll"))
        assertFalse("single-file adopt must not be recursive", chown.contains("-R"))
    }

    @Test
    fun adoptTreeIsRecursiveSoOldRootOwnedFilesAreFixed() {
        val exec = RecordingExec()
        val owner = LoaderOwnership(exec = exec::run)

        owner.adoptTree("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader")

        assertTrue(exec.chowns().single().startsWith("chown -R 10331:1078 "))
    }

    @Test
    fun verifyReportsWhenFilesStillBelongToAnotherUid() {
        val exec = object {
            val commands = mutableListOf<String>()
            fun run(script: String): String {
                commands += script
                return when {
                    script.startsWith("id -u") -> "0"
                    script.startsWith("stat -c '%u %g'") -> "10331 1078"
                    script.startsWith("stat -c %u") && script.contains("bootstrap") -> "0"
                    script.startsWith("stat -c %u") -> "10331"
                    else -> ""
                }
            }
        }
        val owner = LoaderOwnership(exec = exec::run)

        val problem = owner.verify(
            listOf(
                "/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/bootstrap/x.dll",
                "/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/sdk/x.dll",
            ),
        )

        assertTrue("must name the offending file", problem!!.contains("bootstrap/x.dll"))
        assertFalse("healthy files must not be reported", problem.contains("sdk/x.dll"))
    }

    @Test
    fun verifyIsSilentWhenEverythingBelongsToTheGame() {
        val exec = RecordingExec()
        val owner = LoaderOwnership(exec = exec::run)

        assertNull(owner.verify(listOf("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/bootstrap/x.dll")))
    }

    @Test
    fun chownFailureIsReportedButDoesNotThrow() {
        val owner = LoaderOwnership(exec = { script ->
            when {
                script.startsWith("id -u") -> "0"
                script.startsWith("stat -c '%u %g'") -> "10331 1078"
                script.startsWith("chown") -> throw IllegalStateException("Operation not permitted")
                else -> ""
            }
        })

        assertFalse(owner.adopt("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/x.dll"))
        assertTrue(owner.lastError!!.contains("Operation not permitted"))
    }

    @Test
    fun identityMustNeverResolveToRootOrShell() {
        for (uid in listOf(0, 2000, -1, 99999)) {
            val owner = LoaderOwnership(exec = { if (it == "id -u") "0" else "$uid 1078" })
            assertEquals(null, owner.ids())
            assertFalse(owner.adopt("/game/file"))
        }
    }

    @Test
    fun reconnectAndGameReinstallMustNotReuseCachedIdentities() {
        var channel = 2000
        var game = 10331
        val owner = LoaderOwnership(exec = { if (it == "id -u") "$channel" else "$game 1078" })
        assertFalse(owner.isSupported())
        assertEquals(10331, owner.ids()!!.uid)
        channel = 0
        game = 10400
        assertTrue(owner.isSupported())
        assertEquals(10400, owner.ids()!!.uid)
    }

    @Test
    fun successfulChownWithWrongResultMustFail() {
        val owner = LoaderOwnership(exec = {
            when {
                it == "id -u" -> "0"
                it.startsWith("stat -c '%u %g'") -> "10331 1078"
                it.startsWith("stat -c %u") -> "0"
                else -> ""
            }
        })
        assertThrows(IllegalStateException::class.java) { owner.requireOwned("/game/file") }
    }

    @Test
    fun recursiveVerificationRejectsMissedChild() {
        val owner = LoaderOwnership(exec = {
            when {
                it == "id -u" -> "0"
                it.startsWith("stat -c '%u %g'") -> "10331 1078"
                it.startsWith("stat -c %u") -> "10331"
                it.startsWith("find ") -> "10331\n0\n10331"
                else -> ""
            }
        })
        assertThrows(IllegalStateException::class.java) { owner.requireOwned("/game/tree", true) }
    }

    // ---- non-root channel: no chown, access probe instead of st_uid ----

    @Test
    fun shellChannelCannotChownAndSaysSo() {
        val exec = RecordingExec(uid = 2000)
        val owner = LoaderOwnership(exec = exec::run)

        assertFalse(owner.isSupported())
        assertEquals(LoaderOwnership.Ids(10331, 1078), owner.ids())
        assertFalse(owner.adopt("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/x.dll"))
        assertTrue(exec.chowns().isEmpty())
    }

    @Test
    fun shellOwnedFilesThatTheChannelCanReachPassVerification() {
        val exec = RecordingExec(uid = 2000)
        val owner = LoaderOwnership(exec = exec::run)

        // Devicetruth: these carry st_uid 2000 and the game still reads them.
        assertNull(
            owner.verify(
                listOf(
                    "/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/bootstrap/x.dll",
                    "/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/mods",
                ),
            ),
        )
        assertTrue("non-root must not attempt chown", exec.chowns().isEmpty())
    }

    @Test
    fun nonRootRequireOwnedPassesWithoutChownWhenPathIsReachable() {
        val exec = RecordingExec(uid = 2000)
        val owner = LoaderOwnership(exec = exec::run)

        // Must not throw: this is the exact call that used to abort every non-root install.
        owner.requireOwned("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/bootstrap/x.dll")
        owner.requireOwned("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader", recursive = true)

        assertTrue(exec.chowns().isEmpty())
        assertTrue(exec.commands.any { it.startsWith("if [ -d ") })
    }

    @Test
    fun nonRootUnreachablePathFailsVerification() {
        val exec = RecordingExec(uid = 2000, accessible = false)
        val owner = LoaderOwnership(exec = exec::run)

        val problem = owner.verify(listOf("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/mods"))
        assertTrue("unreachable path must be reported", problem!!.contains("Shizuku 无法访问路径"))
        assertThrows(IllegalStateException::class.java) {
            owner.requireOwned("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/mods")
        }
    }

    @Test
    fun nonRootMustNotClaimTheGameCanReadTheFiles() {
        val owner = LoaderOwnership(exec = RecordingExec(uid = 2000)::run)

        // A channel-side probe proves reachability only; it must never emit wording that claims
        // the game loaded anything (that is only provable from the game's own boot log).
        val problem = owner.verify(listOf("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/mods"))
        assertNull(problem)
        assertFalse(owner.isSupported())
    }
}
