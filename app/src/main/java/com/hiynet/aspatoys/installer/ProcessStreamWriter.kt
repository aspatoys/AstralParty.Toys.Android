package com.hiynet.aspatoys.installer

import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

object ProcessStreamWriter {
    fun write(process: Process, source: InputStream) {
        val errors = StringBuffer()
        fun drain(input: InputStream, capture: Boolean) = Thread {
            runCatching { input.bufferedReader().use { reader ->
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (capture) synchronized(errors) {
                        val remaining = 8192 - errors.length
                        if (remaining > 0) errors.append(buffer, 0, minOf(count, remaining))
                    }
                }
            } }
        }.apply { isDaemon = true; start() }
        val stderr = drain(process.errorStream, true)
        val stdout = drain(process.inputStream, false)
        var failure: Throwable? = null
        try { process.outputStream.use { source.copyTo(it, 32 * 1024) } }
        catch (error: Exception) { failure = error }
        // Let an early-exiting child report why it closed stdin before destroying it.
        // ShizukuRemoteProcess implements waitFor(), but its timed API has a different name.
        // Android's inherited timed wait polls exitValue(), whose Binder exception can escape.
        val completed = CountDownLatch(1)
        val exit = AtomicReference<Int?>()
        val waitFailure = AtomicReference<Throwable?>()
        Thread {
            try { exit.set(process.waitFor()) }
            catch (error: Exception) { waitFailure.set(error) }
            finally { completed.countDown() }
        }.apply { isDaemon = true; start() }
        val finished = completed.await(15, TimeUnit.SECONDS)
        if (!finished) {
            runCatching { process.destroy() }
            runCatching { process.errorStream.close(); process.inputStream.close() }
        }
        stderr.join(2000); stdout.join(2000)
        val code = if (!finished) "超时" else exit.get()?.toString() ?: "等待失败"
        if (failure != null || !finished || code != "0") {
            throw IllegalStateException("写入通道失败（退出码 $code）：" +
                errors.toString().trim().ifEmpty { "远端未返回错误输出" } +
                (failure?.let { "；传输：${it.message}" } ?: "") +
                (waitFailure.get()?.let { "；等待进程：${it.message}" } ?: ""), failure ?: waitFailure.get())
        }
    }
}
