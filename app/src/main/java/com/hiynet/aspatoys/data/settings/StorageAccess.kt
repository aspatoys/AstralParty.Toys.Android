package com.hiynet.aspatoys.data.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * 真机存储访问：安卓版 CesiumLoader 的模组在 `/sdcard/CesiumLoader/mods`，
 * App 要读写那里就必须拿到「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE）。
 *
 * 这里只做判断和跳转，不替用户点确认；没授权时界面提示，模组目录按「只读/不可用」处理。
 */
object StorageAccess {
    /** 是否已获得所有文件访问权限（API 30+ 才有这个概念）。 */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Environment.isExternalStorageManager()
            } catch (_: Exception) {
                false
            }
        } else {
            true
        }

    /** 跳到系统的「所有文件访问权限」页面，让用户自己授权。 */
    fun openAllFilesAccessSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val direct = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(direct)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallback)
            } catch (_: Exception) {
                // 系统没有这个页面：极旧或定制 ROM，交给用户手动处理。
            }
        }
    }

    /**
     * 目标目录当前能不能读 / 能不能写。
     * 只判断「一层目录能否列出 / 能否建临时文件」，不留下任何文件。
     */
    fun describe(directory: File): Access {
        val exists = directory.exists()
        val readable = try {
            exists && directory.isDirectory && directory.list() != null
        } catch (_: Exception) {
            false
        }
        val writable = try {
            if (!readable) {
                false
            } else {
                val probe = File(directory, ".aspatoys-write-probe")
                val created = probe.createNewFile()
                if (created) probe.delete()
                created || probe.exists()
            }
        } catch (_: Exception) {
            false
        }
        return Access(exists = exists, readable = readable, writable = writable)
    }

    data class Access(
        val exists: Boolean,
        val readable: Boolean,
        val writable: Boolean,
    ) {
        val usable: Boolean get() = readable
    }
}
