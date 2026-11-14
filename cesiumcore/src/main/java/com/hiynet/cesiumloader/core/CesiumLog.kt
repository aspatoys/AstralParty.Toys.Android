package com.hiynet.cesiumloader.core

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 加载器/安装器写日志的统一入口，格式与现网实现保持一致，方便用户直接看。 */
object CesiumLog {

    private val timestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun append(file: File, message: String?): Boolean {
        val body = message ?: return false
        return runCatching {
            val parent = file.parentFile
            if (parent != null) AtomicFiles.ensureDirs(parent)
            file.appendText("${LocalDateTime.now().format(timestamp)} $body\n")
            true
        }.getOrDefault(false)
    }

    fun truncate(file: File): Boolean = runCatching {
        AtomicFiles.writeText(file, "")
        true
    }.getOrDefault(false)
}
