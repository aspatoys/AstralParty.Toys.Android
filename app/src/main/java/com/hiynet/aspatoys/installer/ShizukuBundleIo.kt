package com.hiynet.aspatoys.installer

import java.io.File

/** Both selected privilege modes use stdin; shell never has to read the toolbox's private files. */
class ShizukuBundleIo(
    journalDir: File,
    private val gamePackage: String = LoaderInstaller.GAME_PACKAGE,
    private val owner: LoaderOwnership = LoaderOwnership(gamePackage),
) : BundleIo {
    private val transport = object : CacheTransport {
        override fun read(path: String) = ShizukuShell.execBytes("cat " + q(path))
        override fun hash(path: String): String {
            val value = ShizukuShell.exec("sha256sum " + q(path)).trim().substringBefore(' ').lowercase()
            require(value.matches(Regex("[a-f0-9]{64}"))) { "无法读取缓存指纹：$path" }
            return value
        }
        override fun write(path: String, bytes: ByteArray) {
            try {
                bytes.inputStream().use { ShizukuShell.writeStream("cat > " + q(path), it) }
                owner.requireOwned(path)
            }
            catch (error: Exception) { throw IllegalStateException("写入目标 $path：${error.message}", error) }
        }
        override fun move(from: String, to: String) { ShizukuShell.exec("mv -f " + q(from) + " " + q(to)) }
        override fun copyIntoExisting(from: String, to: String) {
            owner.requireOwned(to)
            ShizukuShell.exec("cat " + q(from) + " > " + q(to))
            owner.requireOwned(to)
        }
        override fun mkdir(path: String) {
            ShizukuShell.exec("mkdir -p " + q(path))
            owner.requireDirectories(path)
        }
        override fun delete(path: String) { ShizukuShell.exec("rm -f " + q(path)) }
    }
    private val publisher = CachePublisher(transport, journalDir)
    override fun read(path: String): ByteArray { publisher.recover(path); return transport.read(path) }
    override fun hash(path: String): String { publisher.recover(path); return transport.hash(path) }

    /**
     * 写回缓存包之后要把属主改回游戏。
     *
     * Unity 打开的是游戏自己的 `files/` 下的缓存包，属主错了同样读不到；而 `mv -f` 与
     * `cat >` 都可能换掉 inode（前者一定换），所以这里统一补一次 chown。
     * 属主未验证通过就报错，避免把不能读取的缓存包当作安装成功。
     */
    override fun publish(path: String, bytes: ByteArray, expectedSha256: String) {
        publisher.publish(path, bytes, expectedSha256)
        owner.requireOwned(path)
    }

    override fun ensureAccessible(path: String) {
        owner.requireDirectories(path.substringBeforeLast('/'))
        owner.requireOwned(path)
    }

    override fun stopGame() { ShizukuShell.exec("am force-stop " + q(gamePackage)) }
    private fun q(path: String) = ShizukuShell.quote(path)
}
