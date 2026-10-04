package com.arm.aichat.internal

/**
 * Thin JNI facade over `forkpty()` so the in-app terminal gets a real tty instead of pipes.
 *
 * Returns negative handles on failure; callers must fall back to `ProcessBuilder` then, because
 * some devices restrict `forkpty` under their own seccomp policy.
 */
object PtyBridge {

    @Volatile
    private var loaded: Boolean = try {
        System.loadLibrary("aiia-llama")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    val available: Boolean get() = loaded

    private external fun nativeAvailable(): Int

    private external fun nativeStart(argv: Array<String>, cols: Int, rows: Int): Long

    private external fun nativeWrite(handle: Long, data: ByteArray): Int

    private external fun nativeRead(handle: Long, timeoutMs: Int): ByteArray

    private external fun nativeResize(handle: Long, cols: Int, rows: Int): Int

    private external fun nativeWait(handle: Long): Int

    private external fun nativeAlive(handle: Long): Int

    private external fun nativeClose(handle: Long)

    fun isUsable(): Boolean = loaded && runCatching { nativeAvailable() == 1 }.getOrDefault(false)

    fun start(command: Array<String>, cols: Int, rows: Int): Long =
        if (isUsable()) nativeStart(command, cols, rows) else -1L

    fun write(handle: Long, data: ByteArray): Int = if (loaded) nativeWrite(handle, data) else -1

    fun read(handle: Long, timeoutMs: Int): ByteArray = if (loaded) nativeRead(handle, timeoutMs) else ByteArray(0)

    fun resize(handle: Long, cols: Int, rows: Int): Int = if (loaded) nativeResize(handle, cols, rows) else -1

    /** Exit code once the child is gone, otherwise -1 while it still runs. */
    fun wait(handle: Long): Int = if (loaded) nativeWait(handle) else -1

    fun isAlive(handle: Long): Boolean = loaded && nativeAlive(handle) == 1

    fun close(handle: Long) {
        if (loaded && handle >= 0) nativeClose(handle)
    }
}
