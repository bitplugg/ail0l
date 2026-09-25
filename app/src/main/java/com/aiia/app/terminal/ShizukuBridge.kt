package com.aiia.app.terminal

import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.File

object ShizukuBridge {
    private const val REQUEST_CODE = 0x51A
    private const val PERMISSION_GRANTED = PackageManager.PERMISSION_GRANTED

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PERMISSION_GRANTED
    }.getOrDefault(false)

    suspend fun awaitBinder(timeoutMs: Long = 5_000): Boolean {
        if (isRunning()) return true
        val ready = CompletableDeferred<Unit>()
        val listener = Shizuku.OnBinderReceivedListener { ready.complete(Unit) }
        return try {
            Shizuku.addBinderReceivedListenerSticky(listener)
            if (isRunning()) true else withTimeout(timeoutMs) {
                ready.await()
                isRunning()
            }
        } catch (_: Exception) {
            false
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
    }

    suspend fun requestPermission(): Boolean {
        if (!awaitBinder() || !isRunning()) return false
        if (hasPermission()) return true
        val result = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) result.complete(grantResult == PERMISSION_GRANTED)
        }
        Shizuku.addRequestPermissionResultListener(listener)
        return try {
            Shizuku.requestPermission(REQUEST_CODE)
            withTimeout(30_000) { result.await() }
        } catch (_: Exception) {
            false
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    fun startProcess(command: List<String>, workingDirectory: File? = null): Process {
        check(isRunning()) { "Shizuku service is not running" }
        check(hasPermission()) { "Shizuku permission is not granted" }
        val method = runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        }.getOrNull()
        if (method != null) {
            return method.invoke(
                null,
                command.toTypedArray(),
                null,
                workingDirectory?.absolutePath
            ) as Process
        }

        val rishCommand = if (command.size >= 3 && command[1] == "-c") {
            listOf("rish", "-c", command.drop(2).joinToString(" "))
        } else {
            listOf("rish", "-i")
        }
        return ProcessBuilder(*rishCommand.toTypedArray()).apply {
            workingDirectory?.let { directory(it) }
            redirectErrorStream(true)
        }.start()
    }
}
