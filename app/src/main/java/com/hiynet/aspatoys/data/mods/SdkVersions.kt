package com.hiynet.aspatoys.data.mods

/**
 * SemVer（主.次.修订）比较与 SDK 版本兼容判断。
 *
 * 规则与 CesiumLoader / Toys 完全一致，不能改：
 * 加载器用 `doorstop_config.json` 的 `sdkVersion` 校验每个模组声明的 `sdkVersion`，
 * 声明更高的会被直接拒绝加载（游戏里看不到这个模组，也不报错）。所以预检必须在安装前
 * 用同一套比较逻辑拦下来。
 */
object SdkVersions {
    /** a&gt;b 返回正数、相等 0、a&lt;b 负数；解析不出的段按 0 算。 */
    fun compare(left: String?, right: String?): Int {
        val a = parts(left)
        val b = parts(right)
        for (index in 0..2) {
            if (a[index] != b[index]) return if (a[index] > b[index]) 1 else -1
        }
        return 0
    }

    private fun parts(value: String?): IntArray {
        val result = IntArray(3)
        val segments = (value ?: "").split('.')
        for (index in 0..2) {
            if (index >= segments.size) break
            result[index] = segments[index].trim().toIntOrNull() ?: 0
        }
        return result
    }

    /** 模组声明的 SDK 是否高于当前加载器可提供的版本（任一为空时不拦）。 */
    fun isIncompatible(requiredSdk: String?, installedSdk: String?): Boolean =
        !requiredSdk.isNullOrBlank() && !installedSdk.isNullOrBlank() && compare(requiredSdk, installedSdk) > 0

    fun describeMismatch(modName: String, requiredSdk: String, installedSdk: String): String =
        "$modName 要求 SDK $requiredSdk，当前加载器为 $installedSdk。请先更新加载器，或安装支持当前 SDK 的旧版本。"

    /** 把 `3.2.0.0` 这类文件版本规范化成三段；无法解析时保留原值（与 Toys 的 NormalizeSdkVersion 一致）。 */
    fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val trimmed = value.trim()
        val segments = trimmed.split('.')
        if (segments.size < 3) return trimmed
        for (index in 0..2) {
            if (segments[index].toIntOrNull() == null) return trimmed
        }
        return "${segments[0]}.${segments[1]}.${segments[2]}"
    }
}
