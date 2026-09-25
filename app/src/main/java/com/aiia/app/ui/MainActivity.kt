package com.aiia.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aiia.app.R
import com.aiia.app.dm.Dependencies
import com.aiia.app.ui.chat.ChatScreen
import com.aiia.app.ui.chat.ChatViewModel
import com.aiia.app.ui.screens.memory.MemoryScreen
import com.aiia.app.ui.screens.models.ModelsScreen
import com.aiia.app.ui.settings.SettingsScreen
import com.aiia.app.terminal.TerminalScreen
import com.aiia.app.ui.screens.thoughts.ThoughtsScreen
import com.aiia.app.ui.theme.AiiaTheme
import com.aiia.app.util.ApkInstaller
import com.aiia.app.util.ReleaseInfo
import com.aiia.app.util.UpdateChecker
import com.aiia.app.widget.QuickCommandWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val widgetCommand = MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(QuickCommandWidget.EXTRA_COMMAND)?.let { widgetCommand.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(QuickCommandWidget.EXTRA_COMMAND)?.let { widgetCommand.value = it }
        enableEdgeToEdge()
        setContent {

            val settings by Dependencies.settings.settings.collectAsState(initial = null)
            val darkTheme = when (settings?.systemDark) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            AiiaTheme(
                darkTheme = darkTheme,
                dynamicColor = settings?.dynamicColor ?: true
            ) {
                AppRoot(widgetCommand = widgetCommand)
            }
        }
    }
}

private enum class Tab(val route: String, val labelRes: Int) {
    CHAT("chat", R.string.tab_chat),
    THOUGHTS("thoughts", R.string.tab_thoughts),
    MODELS("models", R.string.tab_models),
    MEMORY("memory", R.string.tab_memory),
    SETTINGS("settings", R.string.tab_settings),
    TERMINAL("terminal", R.string.tab_terminal)
}

@Composable
private fun AppRoot(widgetCommand: StateFlow<String?>) {
    val navController = rememberNavController()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val chatViewModel: ChatViewModel = viewModel()
    val command by widgetCommand.collectAsState()

    AutoUpdateDialog()

    LaunchedEffect(command) {
        val prefill = QuickCommandWidget.prefillFor(command)
        if (command != null && prefill.isNotBlank()) {
            selected = Tab.CHAT.ordinal
            navController.navigate(Tab.CHAT.route) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            chatViewModel.setInput(prefill)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = {
                            selected = index
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            val selected = selected == index
                            when (tab) {
                                Tab.CHAT -> Icon(
                                    if (selected) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline,
                                    contentDescription = null
                                )

                                Tab.MODELS -> Icon(
                                    if (selected) Icons.Filled.Memory else Icons.Outlined.Download,
                                    contentDescription = null
                                )

                                Tab.THOUGHTS -> Icon(
                                    if (selected) Icons.Filled.Psychology else Icons.Outlined.Psychology,
                                    contentDescription = null
                                )

                                Tab.MEMORY -> Icon(
                                    if (selected) Icons.Filled.Memory else Icons.Outlined.Memory,
                                    contentDescription = null
                                )

                                Tab.SETTINGS -> Icon(
                                    if (selected) Icons.Filled.Settings else Icons.Outlined.Settings,
                                    contentDescription = null
                                )

                                Tab.TERMINAL -> Icon(
                                    Icons.Filled.Terminal,
                                    contentDescription = null
                                )
                            }
                        },
                        label = { Text(stringResource(tab.labelRes)) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.CHAT.route,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            composable(Tab.CHAT.route) { ChatScreen() }
            composable(Tab.THOUGHTS.route) { ThoughtsScreen() }
            composable(Tab.MODELS.route) { ModelsScreen() }
            composable(Tab.MEMORY.route) { MemoryScreen() }
            composable(Tab.SETTINGS.route) { SettingsScreen() }
            composable(Tab.TERMINAL.route) {
                TerminalScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun AutoUpdateDialog() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<ReleaseInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (checked) return@LaunchedEffect
        checked = true
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val r = UpdateChecker.latestRelease()
        if (r != null && UpdateChecker.isNewer(r.tag, current)) update = r
    }

    update?.let { r ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text("Доступно обновление") },
            text = {
                Text(
                    "Версия ${r.tag}" +
                        if (r.body.isNotBlank()) "\n\n${r.body.take(500)}" else ""
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        val url = r.apkUrl ?: return@Button
                        busy = true
                        scope.launch {
                            val dir = File(context.cacheDir, "update").apply { mkdirs() }
                            val apk = File(dir, "aiia-${r.tag.trimStart('v')}.apk")
                            val ok = UpdateChecker.downloadApk(url, apk, r.apkSha256)
                            busy = false
                            update = null
                            if (!ok) return@launch
                            if (!ApkInstaller.canRequestPackageInstalls(context)) {
                                ApkInstaller.openInstallPermissions(context)
                            } else {
                                runCatching { ApkInstaller.install(context, apk, r.apkSha256) }
                                    .onFailure {
                                        Toast.makeText(context, it.message ?: "APK повреждён", Toast.LENGTH_LONG).show()
                                    }
                            }
                        }
                    }
                ) { Text(if (busy) "Скачиваю…" else "Загрузить и обновить") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            val url = r.apkUrl ?: return@TextButton
                            busy = true
                            scope.launch {
                                val uri = UpdateChecker.downloadApkToDownloads(context, url, r.tag)
                                busy = false
                                update = null
                                if (uri != null) {
                                    Toast.makeText(
                                        context,
                                        "APK сохранён в «Загрузки»",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(context, "Не удалось скачать", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) { Text("Загрузить") }
                    TextButton(onClick = { update = null }) { Text("Отмена") }
                }
            }
        )
    }
}
