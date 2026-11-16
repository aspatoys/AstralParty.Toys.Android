package com.hiynet.aspatoys.installer

import com.hiynet.cesiumloader.core.unity.TextAssetPatch
import com.hiynet.cesiumloader.core.unity.UnityFs
import com.hiynet.cesiumloader.core.unity.UnityFsWriter
import java.io.File

/** Build the exact game cache from the same verified assets shipped in this APK; never skip in CI. */
internal object InstallerFixtures {
    private val assets: File by lazy {
        var root = File(System.getProperty("user.dir")).absoluteFile
        while (!File(root, "settings.gradle.kts").isFile) root = checkNotNull(root.parentFile)
        File(root, "app/src/main/assets/cesium")
    }
    val original: ByteArray by lazy {
        File(assets, "recovery/original.bundle").readBytes().also {
            check(LoaderBundleTransaction.sha256(it) == LoaderInstaller.ORIGINAL_SHA256)
        }
    }
    val patched: ByteArray by lazy {
        val injected = File(assets, "AstralParty.Runtime.injected.dll").readBytes()
        check(LoaderBundleTransaction.sha256(injected) == LoaderInstaller.INJECTED_SHA256)
        val container = UnityFs.parse(original)
        val node = container.nodes.single()
        val bytes = TextAssetPatch.replace(container.readNode(node), LoaderInstaller.ASSEMBLY, injected)
        UnityFsWriter.writeSingleNode(
            versionPlayer = container.versionPlayer, versionEngine = container.versionEngine,
            payload = bytes, originalHash = container.blocksInfoHash, nodePath = node.path,
            nodeFlags = node.flags, containerVersion = container.version,
        ).also { check(LoaderBundleTransaction.sha256(it) == LoaderInstaller.PATCHED_SHA256) }
    }
}
