package com.shilapi.xcertplay.embed

import com.shilapi.xcertplay.host.BuildConfig

/**
 * Certificates, besides RevvCarPlay's own, whose apps may use the host settings and route guidance:
 * SHA-256 digests from `revvcarplay.trustedHostCertificates` in gradle.properties, e.g. Revv's Google
 * Play app signing certificate.
 */
internal object HostCertificates {
    val trusted: List<ByteArray> by lazy { parse(BuildConfig.TRUSTED_HOST_CERTIFICATES) }

    /** Comma-separated hex digests, as the build writes them, to their bytes. */
    fun parse(digests: String): List<ByteArray> =
        digests.split(',').map(String::trim).filter(String::isNotEmpty).map { hex ->
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
}
