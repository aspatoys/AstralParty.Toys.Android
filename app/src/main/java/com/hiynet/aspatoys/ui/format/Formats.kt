package com.hiynet.aspatoys.ui.format

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 界面上的数字/时间格式化（纯函数，单元测试可直接跑）。 */
object Formats {
    /** 字节数 → `18.1 KB` / `2.4 MB`。 */
    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = listOf("KB", "MB", "GB")
        var size = value / 1024.0
        var index = 0
        while (size >= 1024 && index < units.lastIndex) {
            size /= 1024
            index++
        }
        return "%.1f %s".format(size, units[index])
    }

    /** 下载量 → `567` / `1.2k` / `35k`（只用于展示，不做统计）。 */
    fun count(value: Long): String = when {
        value < 1_000 -> value.toString()
        value < 10_000 -> "%.1fk".format(value / 1000.0)
        value < 1_000_000 -> "${value / 1000}k"
        else -> "%.1fM".format(value / 1_000_000.0)
    }

    /** ISO-8601（带时区偏移）→ 本地时间 `2026-11-12 22:42`；解析失败时原样返回。 */
    fun dateTime(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return try {
            val parsed = OffsetDateTime.parse(iso)
            parsed.atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        } catch (_: Exception) {
            iso
        }
    }

    /** 毫秒时间戳 → 本地时间 `2026-11-12 22:42`。 */
    fun dateTime(epochMs: Long): String {
        if (epochMs <= 0) return "—"
        return java.time.Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }

    /** 进度百分比文字；总量未知时显示已下载的字节数。 */
    fun progressText(received: Long, total: Long?, percent: Int?): String =
        if (percent != null && total != null) "$percent%（${bytes(received)} / ${bytes(total)}）"
        else "已下载 ${bytes(received)}"
}
