package com.hiynet.aspatoys.data.loader

import com.hiynet.aspatoys.installer.LoaderOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加载器文件的属主必须跟着游戏的 uid 走。
 *
 * 这不是洁癖：游戏私有目录是 `drwxr-s--- u0_a331 ext_data_rw`，组没有写权限，
 * 而游戏进程的 groups 里也没有 1078。属主一旦变成 root（`mv -f` 换成新 inode
 * 就会这样），游戏就再也读不到引导 DLL，注入进 `<Module>.cctor` 的那句
 * `File.ReadAllBytes` 抛异常又被 try/catch 吞掉 —— 「装好了但一个模组都不加载，
 * 日志也没有」就是这么来的。所以这里断言：写文件之后一定要把属主改回游戏。
 */
class LoaderOwnershipTest {

    private class RecordingExec(private val uid: Int = 0) {
        val commands = mutableListOf<String>()

        fun run(script: String): String {
            commands += script
            return when {
                script.startsWith("id -u") -> uid.toString()
                script.startsWith("stat -c '%u %g'") -> "10331 1078"
                script.startsWith("stat -c %u") -> "10331"
                else -> ""
            }
        }

        fun chowns() = commands.filter { it.startsWith("chown ") }
    }

    @Test
    fun rootChannelResolvesGameIdsFromTheDataDirectory() {
        val exec = RecordingExec()
        val owner = LoaderOwnership(exec = exec::run)

        assertEquals(LoaderOwnership.Ids(10331, 1078), owner.ids())
        assertTrue(owner.isSupported())
        assertTrue(exec.commands.any { it.contains("/sdcard/Android/data/com.feimo.astralparty") })
    }

    @Test
    fun shellChannelCannotChownAndSaysSo() {
        val exec = RecordingExec(uid = 2000)
        val owner = LoaderOwnership(exec = exec::run)

        assertFalse(owner.isSupported())
        assertEquals(null, owner.ids())
        assertFalse(owner.adopt("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/x.dll"))
        assertTrue(exec.chowns().isEmpty())
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

        assertEquals(null, owner.verify(listOf("/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/bootstrap/x.dll")))
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
}
