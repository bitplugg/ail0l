package com.aiia.app.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class ApkIntegrity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val signerSha256: Set<String>
)

object ApkIntegrityVerifier {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(
        context: Context,
        file: File,
        expectedPackage: String? = context.packageName,
        expectedSha256: String? = null
    ): ApkIntegrity {
        require(file.isFile && file.length() > 0) { "APK file is missing" }
        expectedSha256?.takeIf { it.isNotBlank() }?.let {
            require(sha256(file).equals(it, ignoreCase = true)) { "APK SHA-256 mismatch" }
        }
        val info = context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: error("Android cannot parse this APK")
        if (expectedPackage != null) {
            require(info.packageName == expectedPackage) {
                "Unexpected package: ${info.packageName}"
            }
        }
        val signing = signingDigests(info)
        require(signing.isNotEmpty()) { "APK has no signing certificate" }
        val installed = expectedPackage?.let { packageName ->
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                }
            }.getOrNull()
        }
        if (installed != null) {
            val installedSigners = signingDigests(installed)
            require(installedSigners.isEmpty() || installedSigners == signing) {
                "APK is signed with a different key; uninstall the old package first"
            }
        }
        return ApkIntegrity(
            packageName = info.packageName,
            versionCode = info.longVersionCode,
            versionName = info.versionName.orEmpty(),
            sha256 = sha256(file),
            signerSha256 = signing
        )
    }

    private fun signingDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
