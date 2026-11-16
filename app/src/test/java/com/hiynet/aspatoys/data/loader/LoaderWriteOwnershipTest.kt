package com.hiynet.aspatoys.data.loader

import com.hiynet.aspatoys.installer.LoaderOwnership
import org.junit.Assert.*
import org.junit.Test

/** Models the reported FUSE inode ownership, including atomic replacement of an existing DLL. */
class LoaderWriteOwnershipTest {
    private val base = "/sdcard/Android/data/com.feimo.astralparty/files"
    private val target = "$base/CesiumLoader/mods/example/example.dll"

    private inner class Device(val channel: Int = 0, val mapped: Boolean = false) {
        val bytes = mutableMapOf(target to "old")
        val owners = mutableMapOf(target to 10331)
        var failChown = false
        var moves = 0
        val commands = mutableListOf<String>()
        fun exec(command: String): String {
            commands += command
            val paths = Regex("'([^']*)'").findAll(command).map { it.groupValues[1] }.toList()
            return when {
                command == "id -u" -> "$channel"
                command.startsWith("stat -c '%u %g'") -> "10331 1078"
                command.startsWith("stat -c %u ") -> "${owners[paths.last()] ?: channel}"
                command.startsWith("chown ") -> {
                    if (failChown) error("Operation not permitted")
                    paths.forEach { owners[it] = 10331 }
                    ""
                }
                command.startsWith("mkdir -p ") -> {
                    var p = base
                    owners.putIfAbsent(p, if (mapped) 10331 else channel)
                    for (part in paths.last().removePrefix(base).split('/').filter { it.isNotEmpty() }) {
                        p += "/$part"; owners.putIfAbsent(p, if (mapped) 10331 else channel)
                    }
                    ""
                }
                command.startsWith("mv -f ") -> {
                    moves++
                    bytes[paths[1]] = bytes.remove(paths[0])!!
                    owners[paths[1]] = owners.remove(paths[0])!!
                    ""
                }
                command.startsWith("rm -f ") -> { bytes.remove(paths.last()); owners.remove(paths.last()); "" }
                else -> error("Unexpected command $command")
            }
        }
        val fs = ShizukuLoaderFs(LoaderOwnership(exec = ::exec), ::exec, { command, input ->
            val path = command.substringAfter("cat > '").removeSuffix("'")
            bytes[path] = input.readBytes().toString(Charsets.UTF_8)
            owners[path] = if (mapped) 10331 else channel
        })
    }

    @Test fun atomicReplacementRemainsReadableByGame() {
        val d = Device()
        d.fs.makeDirectories(target.substringBeforeLast('/'))
        d.fs.writeText(target, "new")
        assertEquals("new", d.bytes[target])
        assertEquals(10331, d.owners[target])
        assertTrue(d.owners.values.all { it == 10331 })
        assertEquals(1, d.moves)
        assertFalse(d.bytes.containsKey("$target.cesium-tmp"))
    }

    @Test fun chownFailurePreservesOldDllAndReportsFailure() {
        val d = Device()
        d.failChown = true
        assertThrows(IllegalStateException::class.java) { d.fs.writeText(target, "new") }
        assertEquals("old", d.bytes[target])
        assertEquals(0, d.moves)
        assertFalse(d.bytes.containsKey("$target.cesium-tmp"))
    }

    @Test fun shellOwnedStagingCannotReplaceReadableDll() {
        val d = Device(channel = 2000)
        assertThrows(IllegalStateException::class.java) { d.fs.writeText(target, "new") }
        assertEquals("old", d.bytes[target])
        assertEquals(0, d.moves)
        assertFalse(d.commands.any { it.startsWith("chown ") })
    }

    @Test fun shellCanWorkWhenStorageMapsNewFilesToGameUid() {
        val d = Device(channel = 2000, mapped = true)
        d.fs.makeDirectories(target.substringBeforeLast('/'))
        d.fs.writeText(target, "new")
        assertEquals("new", d.bytes[target])
        assertEquals(10331, d.owners[target])
        assertFalse(d.commands.any { it.startsWith("chown ") })
    }
}
