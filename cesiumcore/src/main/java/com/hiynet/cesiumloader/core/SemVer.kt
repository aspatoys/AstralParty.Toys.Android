package com.hiynet.cesiumloader.core

/**
 * 版本号：取前导数字段（最多 4 段），其余原样作为后缀保留。
 *
 * 例：`3.7.5-android-mobile` → numbers=[3,7,5]，suffix="android-mobile"。
 * 比较只比数字段，后缀只用于展示与日志，不参与排序。
 */
data class SemVer(val numbers: List<Int>, val suffix: String? = null) : Comparable<SemVer> {

    val major: Int get() = numbers.getOrElse(0) { 0 }
    val minor: Int get() = numbers.getOrElse(1) { 0 }
    val patch: Int get() = numbers.getOrElse(2) { 0 }

    override fun compareTo(other: SemVer): Int {
        val size = maxOf(numbers.size, other.numbers.size)
        for (index in 0 until size) {
            val left = numbers.getOrElse(index) { 0 }
            val right = other.numbers.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    fun text(): String = buildString {
        append(numbers.joinToString("."))
        if (!suffix.isNullOrEmpty()) append('-').append(suffix)
    }

    override fun toString(): String = text()

    companion object {
        private val Pattern = Regex("""^[vV]?(\d+(?:\.\d+){0,3})(?:[-+_. ](.*))?$""")

        fun parse(raw: String?): SemVer? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val match = Pattern.find(text) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            val suffix = match.groupValues[2].trim().ifEmpty { null }
            return SemVer(numbers, suffix)
        }
    }
}

enum class SdkCompatibility {
    /** 加载器 SDK 满足模组声明。 */
    COMPATIBLE,

    /** 加载器 SDK 低于模组要求。 */
    INCOMPATIBLE,

    /** 有一边读不出来，无法判断（不做猜测）。 */
    UNKNOWN,
}

/** 模组 sidecar 的 `sdkVersion` 门槛，口径与桌面版 `CesiumLoader` 一致。 */
object SdkVersion {

    fun check(required: String?, installed: String?): SdkCompatibility {
        if (required.isNullOrBlank()) return SdkCompatibility.COMPATIBLE
        val need = SemVer.parse(required) ?: return SdkCompatibility.UNKNOWN
        val have = SemVer.parse(installed) ?: return SdkCompatibility.UNKNOWN
        return if (have >= need) SdkCompatibility.COMPATIBLE else SdkCompatibility.INCOMPATIBLE
    }
}
