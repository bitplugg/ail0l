package com.aiia.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

object ApkInstaller {

    fun providerAuthority(context: Context): String = "${context.packageName}.fileprovider"

    fun apkUri(context: Context, apk: File): Uri =
        FileProvider.getUriForFile(context, providerAuthority(context), apk)

    fun install(context: Context, apk: File, expectedSha256: String? = null) {
        ApkIntegrityVerifier.verify(context, apk, expectedSha256 = expectedSha256)
        val uri = apkUri(context, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissions(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val uri = Uri.parse("package:${context.packageName}")
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private object Settings {
        const val ACTION_MANAGE_UNKNOWN_APP_SOURCES =
            "android.settings.MANAGE_UNKNOWN_APP_SOURCES"
    }
}
