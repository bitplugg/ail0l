package com.aiia.app.agent.tools

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.aiia.app.plugins.mcp.McpCallJournal
import com.aiia.app.plugins.mcp.McpManager
import com.aiia.app.terminal.TerminalBus
import com.aiia.app.util.ThoughtLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject

class SystemToolExecutor(private val context: Context) {
    suspend fun execute(call: ToolCall, elevated: Boolean = false): ToolResult = withContext(Dispatchers.IO) {
        when (call.name.lowercase()) {
            "exec_shell", "shell" ->
                runShell(
                    call.command,
                    elevated || call.arguments["privilege"] == "root",
                    call.arguments["privilege"] == "shizuku"
                )
            "open_app" -> openApp(call.packageName)
            "get_battery" -> battery()
            else -> ToolResult(false, "", "Неизвестный инструмент: ${call.name}")
        }
    }

    private fun runShell(command: String, elevated: Boolean, shizuku: Boolean): ToolResult {
        if (command.isBlank()) return ToolResult(false, "", "Пустая команда")
        return runCatching {
            val process =
                if (shizuku && !elevated) {
                    com.aiia.app.terminal.ShizukuBridge.startProcess(listOf("/system/bin/sh", "-c", command))
                } else {
                    val executable = if (elevated) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)
                    ProcessBuilder(*executable).redirectErrorStream(true).start()
                }
            process.waitFor(15, TimeUnit.SECONDS)
            if (!process.isAlive) {
                process.destroyForcibly()
                ToolResult(false, "", "Команда превысила лимит 15 секунд")
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.take(32_000)
                ToolResult(process.exitValue() == 0, output, if (process.exitValue() == 0) null else "Код ${process.exitValue()}")
            }
        }.getOrElse { ToolResult(false, "", it.message) }
    }

    private fun openApp(packageName: String): ToolResult {
        if (packageName.isBlank()) return ToolResult(false, "", "Не указан package")
        val intent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runCatching {
            context.startActivity(intent)
            ToolResult(true, "Запущено $packageName")
        }.getOrElse { ToolResult(false, "", it.message) }
    }

    private fun battery(): ToolResult = runCatching {
        val manager = context.getSystemService(BatteryManager::class.java)
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ToolResult(true, "Заряд батареи: $level%")
    }.getOrElse { ToolResult(false, "", it.message) }
}

class ToolConfirmationCoordinator(
    private val mcp: McpManager? = null
) {
    private val _pending = kotlinx.coroutines.flow.MutableStateFlow<ToolCall?>(null)
    val pending = _pending.asStateFlow()
    private val executor = SystemToolExecutorHolder.executor

    fun request(call: ToolCall) {
        _pending.value = call
    }

    fun dismiss() {
        _pending.value = null
    }

    suspend fun approve(elevated: Boolean = false): ToolResult? {
        val call = _pending.value ?: return null
        _pending.value = null
        if (call.isMcp) {
            val server = call.mcpServer ?: return null
            val tool = call.mcpTool ?: return null
            val result =
                runCatching {
                    val output =
                        mcp?.call(
                            server,
                            tool,
                            call.mcpArguments ?: buildJsonObject {}
                        ).toString()
                    McpCallJournal.record(server, tool, output, true)
                    ThoughtLog.add(ThoughtLog.Tag.TOOL, "MCP $server/$tool: ${output.take(240)}")
                    ToolResult(true, output)
                }.getOrElse { error ->
                    McpCallJournal.record(server, tool, error.message.orEmpty(), false)
                    ThoughtLog.add(ThoughtLog.Tag.TOOL, "MCP $server/$tool failed: ${error.message.orEmpty()}")
                    ToolResult(false, "", error.message ?: "MCP call failed")
                }
            return result
        }
        return executor?.execute(call, elevated)?.also { result ->
            if (call.name.equals("exec_shell", true)) TerminalBus.publish(call.command, result.output)
        }
    }
}

object SystemToolExecutorHolder {
    @Volatile var executor: SystemToolExecutor? = null
}
