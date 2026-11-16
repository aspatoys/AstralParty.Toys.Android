package com.hiynet.aspatoys.data.loader

import com.hiynet.aspatoys.installer.LoaderOwnership
import org.junit.Assert.*
import org.junit.Test

/**
 * Models writes into the game's `Android/data` tree.
 *
 * Device evidence (24129PN74C / Android 16): a 2000:1078 inode created by Shizuku inside the
 * game's `files/` tree is readable by the game — the boot log written by the game itself proves
 * 24/24 mods loaded from shell-owned DLLs. What actually blocks a write is the directory mode,
 * not the inode owner: 2770 trees accept creates, 2750 trees do not. So this fixture models
 * *access* (writable parent, readable path) instead of pretending the storage layer would map a
 * shell create to the game uid.
 */
class LoaderWriteOwnershipTest {
    private val base = "/sdcard/Android/data/com.feimo.astralparty/files"
    private val dir = "$base/CesiumLoader/mods/example"
    private val target = "$dir/example.dll"

    private fun sha256(text: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private inner class Device(
        val channel: Int = 0,
        /** Directories the channel may create entries in (2770) — 2750 trees stay absent. */
        val writableDirs: Set<String> = setOf("$base/CesiumLoader", "$base/CesiumLoader/mods", dir),
        /** Paths the channel can stat/read at all. */
        val readable: Boolean = true,
        val gameUid: Int = 10331,
    ) {
        val bytes = mutableMapOf(target to "old")
        val owners = mutableMapOf(target to gameUid)
        val dirs = mutableSetOf(base, "$base/CesiumLoader", "$base/CesiumLoader/mods", dir)
        var failChown = false
        var corruptOnWrite = false
        /** Storage layer hands back different bytes than the ones we published. */
        var corruptAfterMove = false
        var moves = 0
        val commands = mutableListOf<String>()

        fun exists(path: String) = bytes.containsKey(path) || dirs.contains(path)

        fun exec(command: String): String {
            commands += command
            val quoted = Regex("'([^']*)'").findAll(command).map { it.groupValues[1] }.toList()
            return when {
                command == "id -u" -> "$channel"
                command.startsWith("stat -c '%u %g'") -> "$gameUid 1078"
                command.startsWith("stat -c %u ") -> "${owners[quoted.last()] ?: channel}"
                // verifyAccess probe: `if [ -d p ]; then ...; fi && echo accessible`
                command.startsWith("if [ -d ") -> {
                    val path = quoted.first()
                    if (readable && exists(path)) "accessible" else ""
                }
                command.startsWith("sha256sum ") -> {
                    val path = quoted.last()
                    val data = bytes[path] ?: error("sha256sum: $path: No such file or directory")
                    sha256(data) + "  " + path
                }
                command.startsWith("chown ") -> {
                    if (failChown) error("Operation not permitted")
                    quoted.forEach { owners[it] = gameUid }
                    ""
                }
                command.startsWith("mkdir -p ") -> {
                    var p = base
                    dirs += p
                    for (part in quoted.last().removePrefix(base).split('/').filter { it.isNotEmpty() }) {
                        p += "/$part"
                        dirs += p
                        owners.putIfAbsent(p, channel)
                    }
                    ""
                }
                command.startsWith("mv -f ") -> {
                    moves++
                    val moved = bytes.remove(quoted[0])!!
                    bytes[quoted[1]] = if (corruptAfterMove) "$moved-tampered" else moved
                    owners[quoted[1]] = owners.remove(quoted[0])!!
                    ""
                }
                command.startsWith("rm -f ") -> {
                    bytes.remove(quoted.last())
                    owners.remove(quoted.last())
                    ""
                }
                else -> error("Unexpected command $command")
            }
        }

        val fs = ShizukuLoaderFs(LoaderOwnership(exec = ::exec), ::exec, { command, input ->
            val path = command.substringAfter("cat > '").removeSuffix("'")
            if (path.substringBeforeLast('/') !in writableDirs) {
                error("sh: can't create $path: Permission denied")
            }
            val data = input.readBytes().toString(Charsets.UTF_8)
            bytes[path] = if (corruptOnWrite) "$data-tampered" else data
            owners[path] = channel
        })
    }

    // ---- root channel: ownership repair must stay exactly as strict as before ----

    @Test
    fun rootChannelChownsStagingThenPublishes() {
        val d = Device()
        d.fs.makeDirectories(dir)
        d.fs.writeText(target, "new")
        assertEquals("new", d.bytes[target])
        assertEquals(10331, d.owners[target])
        assertTrue(d.owners.values.all { it == 10331 })
        assertTrue("root must still chown", d.commands.any { it.startsWith("chown ") })
        assertEquals(1, d.moves)
        assertFalse(d.bytes.containsKey("$target.cesium-tmp"))
    }

    @Test
    fun rootChownFailurePreservesOldDllAndReportsFailure() {
        val d = Device()
        d.failChown = true
        assertThrows(IllegalStateException::class.java) { d.fs.writeText(target, "new") }
        assertEquals("old", d.bytes[target])
        assertEquals(0, d.moves)
        assertFalse(d.bytes.containsKey("$target.cesium-tmp"))
    }

    // ---- non-root channel: accessible 2770 tree is a supported configuration ----

    @Test
    fun nonRootPublishesIntoWritableTreeAndKeepsShellOwner() {
        val d = Device(channel = 2000)
        d.fs.makeDirectories(dir)
        d.fs.writeText(target, "new")
        assertEquals("new", d.bytes[target])
        // The owner stays the executing uid; readability by the game comes from the storage
        // layer, which is exactly why the installer must not require uid == game here.
        assertEquals(2000, d.owners[target])
        assertFalse("non-root can never chown", d.commands.any { it.startsWith("chown ") })
        assertEquals(1, d.moves)
        assertFalse(d.bytes.containsKey("$target.cesium-tmp"))
    }

    @Test
    fun nonRootWriteToReadOnlyTreeFailsWithoutTouchingTheOldDll() {
        val d = Device(channel = 2000, writableDirs = emptySet())
        assertThrows(IllegalStateException::class.java) { d.fs.writeText(target, "new") }
        assertEquals("old", d.bytes[target])
        assertEquals(0, d.moves)
        assertFalse(d.bytes.containsKey("$target.cesium-tmp"))
    }

    @Test
    fun nonRootUnreachablePathFailsBeforeCreatingAnything() {
        val d = Device(channel = 2000, readable = false)
        assertThrows(IllegalStateException::class.java) { d.fs.makeDirectories(dir) }
        assertFalse(d.bytes.containsKey("$target.cesium-tmp"))
        assertEquals("old", d.bytes[target])
    }

    // ---- content integrity: a corrupted transfer must never be published ----

    @Test
    fun corruptedTransferIsNotPublished() {
        val d = Device(channel = 2000)
        d.corruptOnWrite = true
        assertThrows(IllegalStateException::class.java) { d.fs.writeText(target, "new") }
        assertEquals("old", d.bytes[target])
        assertEquals(0, d.moves)
        assertFalse(d.bytes.containsKey("$target.cesium-tmp"))
    }

    @Test
    fun corruptedPublishIsReportedInsteadOfBeingAccepted() {
        val d = Device(channel = 2000)
        d.corruptAfterMove = true
        val error = assertThrows(IllegalStateException::class.java) { d.fs.writeText(target, "new") }
        assertTrue("must name the failed path", error.message!!.contains(target))
        assertTrue("must say it is a hash failure", error.message!!.contains("文件写入校验失败"))
        assertEquals("tampered bytes must not be silently accepted", "new-tampered", d.bytes[target])
        assertFalse(d.bytes.containsKey("$target.cesium-tmp"))
    }
}
