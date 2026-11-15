package com.hiynet.aspatoys.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-child regressions for the Shizuku binder shape behind
 * `失败：process hasn't exited` on the Xiaomi 15 (Android 16 / SDK 36).
 *
 * `javap` on the real `../cesium-android-min/tools/shizuku-api/classes.jar` shows
 * `ShizukuRemoteProcess` overrides only `waitFor()` (no-arg) and `exitValue()`; the standard
 * `waitFor(long, TimeUnit)` is inherited from `java.lang.Process`, whose default implementation
 * polls `exitValue()`. On device, `exitValue()` is a Binder call that throws
 * `IllegalStateException("process hasn't exited")` while the remote process is alive, and that
 * exception escapes the poll loop instead of being turned into a boolean.
 *
 * [ShizukuLikeProcess] models exactly that: no-arg `waitFor()` delegates to a real python child,
 * while the timed wait and `exitValue()` throw the device message and are counted. The production
 * writer must never touch either (counts must stay 0) and must still return the real exit code.
 *
 * The old shape is reconstructed as [legacyTimedWaitWrite] and must fail on the same wrapper.
 * These tests run on the host JVM: they prove the seam/API contract, not Binder or device I/O.
 */
class ProcessStreamWriterShizukuWaitTest {

    private val payload: ByteArray by lazy {
        ByteArray(19 * 1024 * 1024 + 4096) { ((it * 31 + 7) and 0xff).toByte() }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun child(vararg args: String): Process {
        val command = mutableListOf(python(), "-c")
        command.addAll(args)
        return ProcessBuilder(command).start()
    }

    private fun tempPath(): File = Files.createTempFile("epipe-shizuku", ".txt").toFile().apply { delete() }

    /**
     * Models the on-device `ShizukuRemoteProcess` wrapper around a real local child.
     *
     * Only the no-arg wait can observe completion; the inherited timed wait and the Binder
     * `exitValue()` both escape with the exact device message.
     */
    private class ShizukuLikeProcess(
        private val delegate: Process,
        private val noargWaitFailure: IllegalStateException? = null,
    ) : Process() {
        val noargWaitCalls = AtomicInteger()
        val timedWaitCalls = AtomicInteger()
        val exitValueCalls = AtomicInteger()

        override fun getOutputStream() = delegate.outputStream

        override fun getInputStream() = delegate.inputStream

        override fun getErrorStream() = delegate.errorStream

        override fun waitFor(): Int {
            noargWaitCalls.incrementAndGet()
            noargWaitFailure?.let { throw it }
            return delegate.waitFor()
        }

        // Deliberately mirrors the inherited java.lang.Process timed wait: it must never be used
        // by the writer, because on device it polls the Binder exitValue() and escapes.
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            timedWaitCalls.incrementAndGet()
            throw IllegalStateException("process hasn't exited")
        }

        override fun exitValue(): Int {
            exitValueCalls.incrementAndGet()
            throw IllegalStateException("process hasn't exited")
        }

        override fun destroy() = delegate.destroy()
    }

    /** The pre-fix shape: copy stdin, then the standard timed wait plus exitValue(). */
    private fun legacyTimedWaitWrite(process: Process, source: InputStream) {
        runCatching { process.outputStream.use { source.copyTo(it, 32 * 1024) } }
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        val code = if (finished) process.exitValue() else -1
        if (code != 0) throw IllegalStateException("写入通道失败（退出码 $code）")
    }

    @Test
    fun legacyTimedWaitEscapesAsProcessHasNotExitedOnTheShizukuWrapper() {
        // The child finishes cleanly and exits 0; only the wait API is Shizuku-shaped.
        val wrapped = ShizukuLikeProcess(
            child(
                """
                import sys
                while sys.stdin.buffer.read(65536):
                    pass
                sys.exit(0)
                """.trimIndent(),
            ),
        )

        val error = runCatching { legacyTimedWaitWrite(wrapped, payload.inputStream()) }.exceptionOrNull()

        assertTrue("RED: the timed wait must reproduce the device symptom, got $error",
            error is IllegalStateException && error.message.orEmpty().contains("process hasn't exited"))
        assertEquals("RED: the legacy shape must have used the timed wait", 1, wrapped.timedWaitCalls.get())
    }

    @Test
    fun productionWriterCompletes19MbOnShizukuWrapperWithoutTimedWaitOrExitValue() {
        val report = tempPath()
        val wrapped = ShizukuLikeProcess(
            child(
                """
                import sys, hashlib
                h = hashlib.sha256()
                n = 0
                while True:
                    b = sys.stdin.buffer.read(65536)
                    if not b:
                        break
                    n += len(b)
                    h.update(b)
                sys.stderr.write('received=%d' % n)
                open(sys.argv[1], 'w').write('%d %s' % (n, h.hexdigest()))
                sys.exit(0)
                """.trimIndent(),
                report.absolutePath,
            ),
        )

        val started = System.nanoTime()
        ProcessStreamWriter.write(wrapped, payload.inputStream())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("full 19 MB transfer stalled (${elapsedMs}ms)", elapsedMs < 15_000)
        assertTrue("the child must have seen EOF", report.isFile)
        val fields = report.readText().trim().split(" ")
        assertEquals("child received the wrong byte count", payload.size.toString(), fields[0])
        assertEquals("payload was corrupted in transit", sha256(payload), fields[1])
        assertEquals("the writer must wait through no-arg waitFor only", 1, wrapped.noargWaitCalls.get())
        assertEquals("GREEN: timed wait must never be called", 0, wrapped.timedWaitCalls.get())
        assertEquals("GREEN: exitValue must never be called", 0, wrapped.exitValueCalls.get())
        report.delete()
    }

    @Test
    fun productionWriterKeepsRemoteExit13AndStderrInsteadOfProcessHasNotExited() {
        val wrapped = ShizukuLikeProcess(
            child(
                """
                import sys
                sys.stderr.write('cat: /sdcard/Android/data/…/__data: Permission denied\n')
                sys.stderr.flush()
                try:
                    sys.stdin.close()
                except Exception:
                    pass
                sys.exit(13)
                """.trimIndent(),
            ),
        )

        val error = runCatching { ProcessStreamWriter.write(wrapped, payload.inputStream()) }.exceptionOrNull()

        assertNotNull("a child exit 13 must fail the write", error)
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        val message = error!!.message.orEmpty()
        assertTrue("real exit code must survive, got: $message", message.contains("13"))
        assertTrue("remote stderr must survive, got: $message", message.contains("Permission denied"))
        assertTrue("the device symptom must not replace the real diagnosis: $message",
            !message.contains("process hasn't exited"))
        assertEquals("GREEN: timed wait must never be called", 0, wrapped.timedWaitCalls.get())
        assertEquals("GREEN: exitValue must never be called", 0, wrapped.exitValueCalls.get())
    }

    @Test
    fun noargWaitForFailureIsKeptInTheDiagnostics() {
        val waitFailure = IllegalStateException("remote wait exploded")
        val wrapped = ShizukuLikeProcess(
            child(
                """
                import sys
                while sys.stdin.buffer.read(65536):
                    pass
                sys.exit(0)
                """.trimIndent(),
            ),
            noargWaitFailure = waitFailure,
        )

        val error = runCatching { ProcessStreamWriter.write(wrapped, payload.inputStream()) }.exceptionOrNull()

        assertTrue("a failed wait must throw, got $error", error is IllegalStateException)
        val message = error!!.message.orEmpty()
        assertTrue("the wait failure text must be kept, got: $message", message.contains("remote wait exploded"))
        assertTrue("the wait failure must be labelled, got: $message", message.contains("等待进程"))
        assertSame("the wait failure must be the cause", waitFailure, error.cause)
        assertEquals("GREEN: timed wait must never be called", 0, wrapped.timedWaitCalls.get())
        assertEquals("GREEN: exitValue must never be called", 0, wrapped.exitValueCalls.get())
    }

    @Test
    fun plainRootProcessStillWorksThroughTheSameWriter() {
        val report = tempPath()
        val process = child(
            """
            import sys, hashlib
            h = hashlib.sha256()
            n = 0
            while True:
                b = sys.stdin.buffer.read(65536)
                if not b:
                    break
                n += len(b)
                h.update(b)
            open(sys.argv[1], 'w').write('%d %s' % (n, h.hexdigest()))
            sys.exit(0)
            """.trimIndent(),
            report.absolutePath,
        )

        ProcessStreamWriter.write(process, payload.inputStream())

        assertTrue("the real child must have exited", process.waitFor(10, TimeUnit.SECONDS))
        assertEquals("the Root (normal Process) path must still see exit 0", 0, process.exitValue())
        val fields = report.readText().trim().split(" ")
        assertEquals("Root path corrupted the payload", sha256(payload), fields[1])
        report.delete()
    }

    private fun python(): String = pythonPath

    private companion object {
        val pythonPath: String by lazy {
            for (candidate in listOf("python", "python3", "py")) {
                val ok = runCatching {
                    val probe = ProcessBuilder(candidate, "-c", "pass").start()
                    probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0
                }.getOrDefault(false)
                if (ok) return@lazy candidate
            }
            fail("these regressions need a real python child process on PATH")
            error("unreachable")
        }
    }
}
