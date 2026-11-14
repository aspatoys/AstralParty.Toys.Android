package com.hiynet.aspatoys.ui.market

/** 市场下载的阶段性状态：准备 → 下载 → 校验哈希 → 预检压缩包 → 写入加载器。 */
data class DownloadState(
    val modId: String,
    val modName: String,
    val version: String,
    val phase: Phase,
    val received: Long,
    val total: Long?,
    val percent: Int?,
) {
    enum class Phase { PREPARE, DOWNLOAD, VERIFY, INSPECT, INSTALL }

    val phaseText: String
        get() = when (phase) {
            Phase.PREPARE -> "正在准备下载…"
            Phase.DOWNLOAD -> "正在下载"
            Phase.VERIFY -> "正在校验 SHA-256…"
            Phase.INSPECT -> "正在检查压缩包…"
            Phase.INSTALL -> "正在写入加载器…"
        }

    /** 写加载器时不能中断（shell 命令已经发出去了），界面据此隐藏「取消」。 */
    val cancellable: Boolean get() = phase != Phase.INSTALL

    /** 进度条比例；总量未知时按不确定进度（返回 null）。 */
    val fraction: Float?
        get() = percent?.let { it / 100f }
}
