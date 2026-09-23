package com.ail0l.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/** Установка скачанного APK через системный инсталлятор. */
object ApkInstaller {

    fun providerAuthority(context: Context): String = "${context.packageName}.fileprovider"

    fun apkUri(context: Context, apk: File): Uri =
        FileProvider.getUriForFile(context, providerAuthority(context), apk)

    /** Открывает системный экран установки (API 26+: требует разрешения на такие установки). */
    fun install(context: Context, apk: File) {
        val uri = apkUri(context, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Открывает настройки «разрешить установку из этого источника». */
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