package com.hiynet.aspatoys.ui.market

/** 市场下载的阶段性状态：准备 → 下载 → 校验哈希 → 预检压缩包。 */
data class DownloadState(
    val modId: String,
    val modName: String,
    val version: String,
    val phase: Phase,
    val received: Long,
    val total: Long?,
    val percent: Int?,
) {
    enum class Phase { PREPARE, DOWNLOAD, VERIFY, INSPECT }

    val phaseText: String
        get() = when (phase) {
            Phase.PREPARE -> "正在准备下载…"
            Phase.DOWNLOAD -> "正在下载"
            Phase.VERIFY -> "正在校验 SHA-256…"
            Phase.INSPECT -> "正在检查压缩包…"
        }

    /** 进度条比例；总量未知时按不确定进度（返回 null）。 */
    val fraction: Float?
        get() = percent?.let { it / 100f }
}
