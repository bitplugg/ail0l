package com.aiia.app.terminal

import com.arm.aichat.internal.PtyBridge
import java.io.File
import java.io.PrintWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TerminalMode(val command: List<String>) {
    SHELL(listOf("/system/bin/sh", "-i")),
    ROOT(listOf("/system/bin/su", "-i")),
    SHIZUKU(listOf("/system/bin/sh", "-i"))
}

data class TerminalStatus(
    val mode: TerminalMode = TerminalMode.SHELL,
    val running: Boolean = false,
    val exitCode: Int? = null,
    val error: String? = null,
    val usingPty: Boolean = false
)

class TerminalSession(
    initialMode: TerminalMode = TerminalMode.SHELL
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 512)
    private val _status = MutableStateFlow(TerminalStatus(initialMode))
    private var process: Process? = null
    private var writer: PrintWriter? = null
    private var readJob: Job? = null
    private var ptyHandle: Long = -1L

    val events: SharedFlow<String> = _events.asSharedFlow()
    val status: StateFlow<TerminalStatus> = _status.asStateFlow()

    fun start(mode: TerminalMode = _status.value.mode, cols: Int = 80, rows: Int = 24) {
        close()
        if (startPty(mode, cols, rows)) return
        _status.value = TerminalStatus(mode, running = true)
        runCatching {
            val started =
                if (mode == TerminalMode.SHIZUKU) {
                    ShizukuBridge.startProcess(listOf("/system/bin/sh", "-i"))
                } else {
                    val builder =
                        ProcessBuilder(mode.command).apply {
                            directory(File(System.getProperty("user.home") ?: "/"))
                            environment()["TERM"] = "xterm-256color"
                            redirectErrorStream(true)
                        }
                    builder.start()
                }
            process = started
            writer = PrintWriter(started.outputStream, true)
            readJob =
                scope.launch {
                    started.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            _events.emit(line + "\n")
                        }
                    }
                    val code = runCatching { started.waitFor() }.getOrDefault(-1)
                    _status.value = _status.value.copy(running = false, exitCode = code)
                }
        }.onFailure { error ->
            _status.value = _status.value.copy(running = false, error = error.message)
        }
    }

    /**
     * Starts the shell on a real pty. Shizuku is excluded: its service already owns the process,
     * and a second pty on top of it would break its own protocol.
     */
    private fun startPty(mode: TerminalMode, cols: Int, rows: Int): Boolean {
        if (mode == TerminalMode.SHIZUKU) return false
        val handle = runCatching { PtyBridge.start(mode.command.toTypedArray(), cols, rows) }.getOrDefault(-1L)
        if (handle < 0) return false

        ptyHandle = handle
        _status.value = TerminalStatus(mode, running = true, usingPty = true)
        readJob =
            scope.launch {
                while (PtyBridge.isAlive(handle)) {
                    val chunk = PtyBridge.read(handle, POLL_MS)
                    if (chunk.isEmpty()) {
                        delay(IDLE_MS.toLong())
                    } else {
                        _events.emit(String(chunk, Charsets.UTF_8))
                    }
                }
                val code = PtyBridge.wait(handle)
                _status.value = _status.value.copy(running = false, exitCode = code)
                PtyBridge.close(handle)
                ptyHandle = -1L
            }
        return true
    }

    /** Keeps the child's idea of the window size in sync so full-screen programs render. */
    fun resize(cols: Int, rows: Int) {
        if (ptyHandle >= 0) PtyBridge.resize(ptyHandle, cols, rows)
    }

    private val isPty: Boolean get() = ptyHandle >= 0

    fun sendInput(input: String) {
        if (isPty) {
            PtyBridge.write(ptyHandle, (input + if (input.endsWith("\n")) "" else "\n").toByteArray())
            return
        }
        writer?.apply {
            write(input)
            if (!input.endsWith("\n")) write("\n")
            flush()
        }
    }

    fun sendControl(control: TerminalControl) {
        when (control) {
            TerminalControl.CTRL_C -> sendInput("\u0003")
            TerminalControl.CTRL_D -> sendInput("\u0004")
            TerminalControl.TAB -> writeRaw("\t")
            TerminalControl.ESC -> writeRaw("\u001B")
            TerminalControl.UP -> writeRaw("\u001B[A")
            TerminalControl.DOWN -> writeRaw("\u001B[B")
            TerminalControl.LEFT -> writeRaw("\u001B[D")
            TerminalControl.RIGHT -> writeRaw("\u001B[C")
        }
    }

    private fun writeRaw(value: String) {
        if (isPty) {
            PtyBridge.write(ptyHandle, value.toByteArray())
            return
        }
        writer?.apply {
            write(value)
            flush()
        }
    }

    fun close() {
        readJob?.cancel()
        if (ptyHandle >= 0) {
            PtyBridge.close(ptyHandle)
            ptyHandle = -1L
        }
        writer?.close()
        process?.destroy()
        process = null
        writer = null
        _status.value = _status.value.copy(running = false)
    }

    fun destroy() {
        close()
        scope.cancel()
    }
}

enum class TerminalControl { CTRL_C, CTRL_D, TAB, ESC, UP, DOWN, LEFT, RIGHT }

object TerminalBus {
    private val _agentOutput = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val agentOutput: SharedFlow<String> = _agentOutput.asSharedFlow()

    fun publish(command: String, output: String = "") {
        _agentOutput.tryEmit("\$ $command\n$output")
    }
}

private const val POLL_MS = 120
private const val IDLE_MS = 16
