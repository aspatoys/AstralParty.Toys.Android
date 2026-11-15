package com.hiynet.aspatoys.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Real-`java.lang.Process` regressions for [ProcessStreamWriter].
 *
 * These tests start an actual child process (python, no shell) on the host and push a real 19 MB
 * payload through stdin, so they exercise the same failure modes as the on-device `sh -c 'cat > …'`
 * channel: a child that exits early closing stdin, a child that floods both output pipes while stdin
 * is still being written, and a source stream that dies mid-transfer.
 *
 * The pre-fix behaviour is modelled by [legacySequentialWrite] (write stdin to completion, then wait,
 * never capture stderr). Contrast tests show the old shape loses the remote error text and deadlocks;
 * the production writer must keep the real exit code, the child's stderr, and the transfer cause.
 *
 * On-device verification is still required: these tests prove the process/pipe handling only, not
 * the Android/Shizuku binder behaviour.
 */
class ProcessStreamWriterProcessTest {

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

    private fun tempPath(): File = Files.createTempFile("epipe-child", ".txt").toFile().apply { delete() }

    @Test
    fun largeTransferCompletesWithoutDeadlockAndChildReceivesEveryByte() {
        val report = tempPath()
        // Child drains stdin completely, reports how much it got, then exits normally.
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
            sys.stderr.write('received=%d' % n)
            sys.stdout.write('ok')
            open(sys.argv[1], 'w').write('%d %s' % (n, h.hexdigest()))
            sys.exit(0)
            """.trimIndent(),
            report.absolutePath,
        )

        val started = System.nanoTime()
        ProcessStreamWriter.write(process, payload.inputStream())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("19 MB transfer took ${elapsedMs}ms; a stalled pipe is the bug under test", elapsedMs < 15_000)
        assertTrue("child exited with ${process.exitValue()}", process.waitFor(1, TimeUnit.SECONDS))
        assertTrue("child must see EOF, not a killed pipe", report.isFile)
        val fields = report.readText().trim().split(" ")
        assertEquals("child received the wrong byte count", payload.size.toString(), fields[0])
        assertEquals("payload was corrupted in transit", sha256(payload), fields[1])
        report.delete()
    }

    @Test
    fun earlyExitThatClosesStdinKeepsRealExitCodeAndPermissionDeniedStderr() {
        // Mirrors the reported failure: `cat > …/__data` cannot open the target and dies with 13,
        // closing stdin while the 19 MB payload is still being written.
        val process = child(
            """
            import sys
            sys.stderr.write('cat: /sdcard/Android/data/com.feimo.astralparty/files/com.unity.addressables/AssetBundles/x/__data: Permission denied\n')
            sys.stderr.flush()
            try:
                sys.stdin.close()
            except Exception:
                pass
            sys.exit(13)
            """.trimIndent(),
        )

        val started = System.nanoTime()
        val error = runCatching { ProcessStreamWriter.write(process, payload.inputStream()) }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNotNull("the write must fail when the child exits 13", error)
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        val message = error!!.message.orEmpty()
        assertTrue("exit code must survive, got: $message", message.contains("13"))
        assertTrue("child stderr must survive, got: $message", message.contains("Permission denied"))
        assertNotNull("the transfer failure must be kept as cause", error.cause)
        assertTrue("a broken pipe is the expected cause, got ${error.cause}", error.cause is IOException)
        assertTrue("early exit must not stall the writer (${elapsedMs}ms)", elapsedMs < 15_000)
    }

    @Test
    fun fullTransferWithNonZeroExitStillReportsStderrInsteadOfBareExitCode() {
        // The other real shape: the whole payload is consumed, but the command fails afterwards.
        val process = child(
            """
            import sys
            while sys.stdin.buffer.read(65536):
                pass
            sys.stderr.write('sha256sum: Permission denied\n')
            sys.stderr.flush()
            sys.exit(13)
            """.trimIndent(),
        )

        val error = runCatching { ProcessStreamWriter.write(process, payload.inputStream()) }.exceptionOrNull()

        assertTrue("non-zero exit must throw, got $error", error is IllegalStateException)
        val message = error!!.message.orEmpty()
        assertTrue("message lacked exit code: $message", message.contains("13"))
        assertTrue("message dropped remote stderr: $message", message.contains("Permission denied"))
        assertTrue("a completed transfer must not report a transfer cause: $message", message.contains("远端") || !message.contains("传输"))
    }

    @Test
    fun largeStdoutAndStderrWhileWritingStdinDoNotDeadlock() {
        // Child floods both pipes (16 MB) *and* drains stdin. Sequential readers deadlock here.
        val process = child(
            """
            import sys, threading
            def drain():
                while sys.stdin.buffer.read(65536):
                    pass
            worker = threading.Thread(target=drain)
            worker.start()
            chunk = b'x' * 65536
            for _ in range(128):
                sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()
            for _ in range(128):
                sys.stderr.buffer.write(chunk)
            sys.stderr.buffer.flush()
            worker.join()
            sys.exit(0)
            """.trimIndent(),
        )

        val started = System.nanoTime()
        ProcessStreamWriter.write(process, payload.inputStream())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("full-duplex transfer deadlocked (${elapsedMs}ms)", elapsedMs < 15_000)
        assertTrue(process.waitFor(1, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
    }

    @Test
    fun sourceFailureIsPreservedAsCauseEvenWhenChildExitsZero() {
        val failure = IOException("local source read failed: boom")
        val source = object : InputStream() {
            private var served = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                // Once the 1 MiB allowance is spent, fail instead of returning 0 (a 0-length read
                // would make copyTo spin forever and hang the test JVM).
                if (served >= 1 * 1024 * 1024) throw failure
                val count = minOf(length, 1 * 1024 * 1024 - served)
                served += count
                return count
            }
        }
        val process = child(
            """
            import sys
            while sys.stdin.buffer.read(65536):
                pass
            sys.exit(0)
            """.trimIndent(),
        )

        val error = runCatching { ProcessStreamWriter.write(process, source) }.exceptionOrNull()

        assertTrue("a source failure must throw, got $error", error is IllegalStateException)
        assertTrue("the original transfer error must be the cause", error!!.cause === failure)
        assertTrue("message must name the transfer failure: ${error.message}",
            error.message.orEmpty().contains("local source read failed: boom"))
    }

    @Test
    fun legacySequentialModelLosesStderrForTheSameEarlyExit() {
        // Red regression: the pre-fix shape never drained/captured stderr and waited without it.
        val process = child(
            """
            import sys
            sys.stderr.write('cat: Permission denied\n')
            sys.stderr.flush()
            sys.exit(13)
            """.trimIndent(),
        )

        val error = runCatching { legacySequentialWrite(process, payload.inputStream()) }.exceptionOrNull()

        assertNotNull("legacy model must also fail", error)
        val message = error!!.message.orEmpty()
        assertTrue("legacy model did report the exit code: $message", message.contains("13"))
        assertTrue("RED: legacy model dropped the remote stderr, got: $message", !message.contains("Permission denied"))
    }

    @Test
    fun legacySequentialModelDeadlocksWhenChildFloodsStdoutBeforeReadingStdin() {
        val process = child(
            """
            import sys
            chunk = b'y' * 65536
            for _ in range(128):
                sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()
            while sys.stdin.buffer.read(65536):
                pass
            sys.exit(0)
            """.trimIndent(),
        )

        val thrown = AtomicReference<Throwable?>()
        val worker = thread(name = "legacy-write") {
            runCatching { legacySequentialWrite(process, payload.inputStream()) }.onFailure { thrown.set(it) }
        }
        worker.join(8_000)
        val stalled = worker.isAlive

        // Clean up the deadlocked child so the test JVM is not left with a stuck pipe.
        runCatching { process.destroyForcibly() }
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        worker.join(10_000)

        assertTrue("RED: sequential model must stall on a flooding child", stalled)
        assertTrue("worker must terminate after the child is destroyed", !worker.isAlive)
    }

    /**
     * Model of the pre-fix write path: copy stdin to completion, then wait, never reading stderr
     * concurrently. It is a reconstruction used only to demonstrate the red state.
     */
    private fun legacySequentialWrite(process: Process, source: InputStream) {
        runCatching { process.outputStream.use { source.copyTo(it) } }
        val code = process.waitFor()
        if (code != 0) throw IllegalStateException("命令失败（退出码 $code）")
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
