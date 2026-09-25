package com.aiia.app.ai.download

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs

object DeviceProfile {
    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    fun cores(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun androidVersion(): Int = Build.VERSION.SDK_INT

    fun ramBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        return mem.totalMem
    }

    fun freeBytes(): Long {
        val stat = StatFs(Environment.getDataDirectory().path)
        return stat.availableBytes
    }

    fun usableRamBytes(context: Context): Long =
        (ramBytes(context) * 0.7).toLong().coerceAtLeast(1)
}
