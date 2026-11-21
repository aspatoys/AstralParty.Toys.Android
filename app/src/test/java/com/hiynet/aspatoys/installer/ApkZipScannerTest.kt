package com.hiynet.aspatoys.installer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkZipScannerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun buildApkBytes(entries: List<String>, comment: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            if (comment.isNotEmpty()) zip.setComment(comment)
            for (name in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("stub-$name".toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun detectsNativeLoaderViaLocalZipFile() {
        val clean = File(temp.root, "bilibili-clean.apk").apply {
            writeBytes(buildApkBytes(listOf("AndroidManifest.xml", "lib/arm64-v8a/libil2cpp.so")))
        }
        val nativeMod = File(temp.root, "native-mod.apk").apply {
            writeBytes(buildApkBytes(listOf("AndroidManifest.xml", "lib/arm64-v8a/libcesium.so")))
        }

        assertFalse(ApkZipScanner.containsNativeLoader(clean.path))
        assertTrue(ApkZipScanner.containsNativeLoader(nativeMod.path))
    }

    @Test
    fun fallsBackToCentralDirectoryParsingWithoutUnzipBinary() {
        val cleanBytes = buildApkBytes(
            listOf("AndroidManifest.xml", "assets/bin/Data/globalgamemanagers", "lib/arm64-v8a/libunity.so"),
            comment = "channel-bilibili",
        )
        val nativeBytes = buildApkBytes(
            listOf("AndroidManifest.xml", "lib/arm64-v8a/libcesium.so", "classes.dex"),
            comment = "repackaged",
        )

        fun scanRemote(bytes: ByteArray): Boolean = ApkZipScanner.containsNativeLoader(
            path = "/nonexistent/restricted/base.apk",
            statSize = { bytes.size.toLong() },
            readTail = { _, count -> bytes.copyOfRange(bytes.size - count, bytes.size) },
            readSliceFromEnd = { _, fromEnd, len ->
                val start = (bytes.size - fromEnd).toInt()
                bytes.copyOfRange(start, start + len)
            },
            checkExtractedLib = { false },
        )

        assertFalse(scanRemote(cleanBytes))
        assertTrue(scanRemote(nativeBytes))
    }
}
