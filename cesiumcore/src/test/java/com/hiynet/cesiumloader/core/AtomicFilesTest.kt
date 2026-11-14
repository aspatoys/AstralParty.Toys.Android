package com.hiynet.cesiumloader.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilesTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `writes text and replaces previous content`() {
        val file = File(temp.root, "nested/dir/note.txt")
        AtomicFiles.writeText(file, "first")
        assertEquals("first", file.readText())

        AtomicFiles.writeText(file, "second")
        assertEquals("second", file.readText())
        assertFalse(File(file.parentFile, "note.txt.tmp").exists())
    }

    @Test
    fun `writes bytes and creates parents`() {
        val file = File(temp.root, "a/b/c.bin")
        AtomicFiles.writeBytes(file, byteArrayOf(1, 2, 3))
        assertTrue(file.isFile)
        assertEquals(3, file.length().toInt())
    }

    @Test
    fun `creates directories idempotently`() {
        val dir = File(temp.root, "x/y")
        AtomicFiles.ensureDirs(dir)
        AtomicFiles.ensureDirs(dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `logs append with timestamp`() {
        val file = File(temp.root, "logs/bootstrap.log")
        assertTrue(CesiumLog.append(file, "hello"))
        assertTrue(CesiumLog.append(file, "world"))

        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith(" hello"))
        assertTrue(lines[1].endsWith(" world"))
    }
}

class HashingTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `hashes known bytes`() {
        val digest = Hashing.sha256Hex("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
    }

    @Test
    fun `hashes files and matches case insensitively`() {
        val file = File(temp.root, "payload.bin")
        file.writeBytes("abc".toByteArray())

        val digest = Hashing.sha256Hex(file)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
        assertTrue(Hashing.matches(file, digest.uppercase()))
        assertFalse(Hashing.matches(file, "0".repeat(64)))
        assertFalse(Hashing.matches(file, null))
    }
}
