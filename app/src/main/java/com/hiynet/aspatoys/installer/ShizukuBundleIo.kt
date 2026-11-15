package com.hiynet.aspatoys.installer

import java.io.File

/** Both selected privilege modes use stdin; shell never has to read the toolbox's private files. */
class ShizukuBundleIo(journalDir: File) : BundleIo {
    private val transport = object : CacheTransport {
        override fun read(path: String) = ShizukuShell.execBytes("cat " + q(path))
        override fun hash(path: String): String {
            val value = ShizukuShell.exec("sha256sum " + q(path)).trim().substringBefore(' ').lowercase()
            require(value.matches(Regex("[a-f0-9]{64}"))) { "无法读取缓存指纹：$path" }
            return value
        }
        override fun write(path: String, bytes: ByteArray) {
            try { bytes.inputStream().use { ShizukuShell.writeStream("cat > " + q(path), it) } }
            catch (error: Exception) { throw IllegalStateException("写入目标 $path：${error.message}", error) }
        }
        override fun move(from: String, to: String) { ShizukuShell.exec("mv -f " + q(from) + " " + q(to)) }
        override fun copyIntoExisting(from: String, to: String) { ShizukuShell.exec("cat " + q(from) + " > " + q(to)) }
        override fun mkdir(path: String) { ShizukuShell.exec("mkdir -p " + q(path)) }
        override fun delete(path: String) { ShizukuShell.exec("rm -f " + q(path)) }
    }
    private val publisher = CachePublisher(transport, journalDir)
    override fun read(path: String): ByteArray { publisher.recover(path); return transport.read(path) }
    override fun hash(path: String): String { publisher.recover(path); return transport.hash(path) }
    override fun publish(path: String, bytes: ByteArray, expectedSha256: String) = publisher.publish(path, bytes, expectedSha256)
    override fun stopGame() { ShizukuShell.exec("am force-stop ${LoaderInstaller.GAME_PACKAGE}") }
    private fun q(path: String) = ShizukuShell.quote(path)
}
