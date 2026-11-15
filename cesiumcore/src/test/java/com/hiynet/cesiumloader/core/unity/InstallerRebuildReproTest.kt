package com.hiynet.cesiumloader.core.unity

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 用真实的 UnityFs / TextAssetPatch / UnityFsWriter 复现安装器写回后的缓存包。
 *
 * 只用于安装器恢复回归：给定原版 __data 和随包注入程序集，输出“安装完成后”的容器，
 * 便于核对用户设备上看到的 SHA-256 / 长度是否就是安装器自己产生的包。
 *
 * 环境变量：
 *  - CESIUM_REBUILD_BUNDLE：原版 __data
 *  - CESIUM_REBUILD_DLL：随包 AstralParty.Runtime.injected.dll
 *  - CESIUM_REBUILD_OUT：输出文件（可选）
 *  - CESIUM_REBUILD_EXPECT_SHA256：期望 SHA-256（可选）
 *  - CESIUM_REBUILD_EXPECT_SIZE：期望字节数（可选）
 */
class InstallerRebuildReproTest {

    private fun envFile(name: String): File? {
        val path = System.getenv(name) ?: return null
        val file = File(path)
        return if (file.isFile) file else null
    }

    @Test
    fun rebuildsTheExactContainerTheInstallerWouldWrite() {
        val bundleFile = envFile("CESIUM_REBUILD_BUNDLE")
        val dllFile = envFile("CESIUM_REBUILD_DLL")
        assumeTrue("缺少 CESIUM_REBUILD_BUNDLE / CESIUM_REBUILD_DLL，跳过", bundleFile != null && dllFile != null)

        val bundle = UnityFs.parse(bundleFile!!.readBytes())
        val injected = dllFile!!.readBytes()
        val node = bundle.nodes.first()
        val serialized = bundle.readNode(node)
        val patchedSerialized = TextAssetPatch.replace(serialized, ASSEMBLY, injected)

        val rebuilt = UnityFsWriter.writeSingleNode(
            versionPlayer = bundle.versionPlayer,
            versionEngine = bundle.versionEngine,
            payload = patchedSerialized,
            originalHash = bundle.blocksInfoHash,
            nodePath = node.path,
            nodeFlags = node.flags,
        )

        val sha = MessageDigest.getInstance("SHA-256").digest(rebuilt)
            .joinToString("") { "%02x".format(it) }
        println("rebuilt bundle: ${rebuilt.size} B sha256=$sha")

        System.getenv("CESIUM_REBUILD_OUT")?.takeIf { it.isNotBlank() }?.let { path ->
            val out = File(path)
            out.parentFile?.mkdirs()
            out.writeBytes(rebuilt)
            println("rebuilt bundle written to ${out.absolutePath}")
        }

        System.getenv("CESIUM_REBUILD_EXPECT_SIZE")?.toIntOrNull()?.let { expected ->
            assertEquals("重建产物大小", expected, rebuilt.size)
        }
        System.getenv("CESIUM_REBUILD_EXPECT_SHA256")?.takeIf { it.isNotBlank() }?.let { expected ->
            assertEquals("重建产物 SHA-256", expected.lowercase(), sha)
        }
    }

    private companion object {
        const val ASSEMBLY = "AstralParty.Runtime.dll"
    }
}
