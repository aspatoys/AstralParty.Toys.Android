package com.hiynet.aspatoys.data.updates

/** Package-manager-independent acceptance policy; the installer calls it with real archive metadata. */
object ApkPolicy {
    const val CERT_SHA256 = "7cdb0644cd2bc4370152643950dde7b8cbf99ba4f770e094e9ecb775ca392bcc"
    data class Metadata(val packageName: String, val versionCode: Long, val versionName: String, val minSdk: Int, val signers: Set<String>)
    fun verify(candidate: Metadata, current: Metadata, expectedVersion: String, deviceSdk: Int) {
        require(candidate.packageName == current.packageName && candidate.versionCode > current.versionCode && candidate.versionName == expectedVersion) { "APK 包名或版本不匹配，拒绝安装。" }
        require(candidate.minSdk <= deviceSdk) { "此更新不支持当前 Android 版本。" }
        require(candidate.signers == setOf(CERT_SHA256) && current.signers == setOf(CERT_SHA256)) { "APK 签名与正式发行证书不一致，拒绝安装。" }
    }
}
