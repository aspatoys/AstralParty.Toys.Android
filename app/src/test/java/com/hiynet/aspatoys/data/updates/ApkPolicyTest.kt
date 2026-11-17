package com.hiynet.aspatoys.data.updates

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Acceptance policy only; the installer call itself needs a real PackageManager and is untested here. */
class ApkPolicyTest {
    private val cert = ApkPolicy.CERT_SHA256
    private val other = "0".repeat(64)
    private fun meta(pkg: String = "com.hiynet.aspatoys", code: Long = 10301, name: String = "1.3.0",
                     minSdk: Int = 26, signers: Set<String> = setOf(cert)) = ApkPolicy.Metadata(pkg, code, name, minSdk, signers)
    private val current = meta(code = 10300, name = "1.2.4")
    private fun rejects(candidate: ApkPolicy.Metadata, expected: String = "1.3.0", deviceSdk: Int = 36, current: ApkPolicy.Metadata = this.current) {
        try { ApkPolicy.verify(candidate, current, expected, deviceSdk); fail("accepted ${candidate.copy(signers = candidate.signers)}") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.isNotBlank()) }
    }

    @Test fun `matching signed upgrade is accepted`() {
        ApkPolicy.verify(meta(), current, "1.3.0", 36)
        ApkPolicy.verify(meta(code = 10301, name = "1.3.0", minSdk = 36), current, "1.3.0", 36)
    }

    @Test fun `other package or version is rejected`() {
        rejects(meta(pkg = "com.example.toys"))
        rejects(meta(code = 10300, name = "1.3.0"))
        rejects(meta(code = 10299, name = "1.3.0"))
        rejects(meta(name = "1.2.9"))
    }

    @Test fun `signature must be exactly the release certificate`() {
        rejects(meta(signers = setOf(other)))
        rejects(meta(signers = setOf(cert, other)))
        rejects(meta(signers = emptySet()))
        rejects(meta(), current = current.copy(signers = setOf(other)))
        rejects(meta(), current = current.copy(signers = setOf(cert, other)))
    }

    @Test fun `min sdk above the device is rejected`() {
        rejects(meta(minSdk = 37), deviceSdk = 36)
        rejects(meta(minSdk = Int.MAX_VALUE))
    }

    @Test fun `policy constant stays the published release certificate`() {
        assertTrue(cert.matches(Regex("[0-9a-f]{64}")))
    }
}
