package com.ail0l.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ail0l.app.R
import com.ail0l.app.ui.screens.chat.ChatScreen
import com.ail0l.app.ui.screens.memory.MemoryScreen
import com.ail0l.app.ui.screens.models.ModelsScreen
import com.ail0l.app.ui.screens.settings.SettingsScreen
import com.ail0l.app.ui.screens.thoughts.ThoughtsScreen
import com.ail0l.app.ui.theme.Ail0lTheme
import com.ail0l.app.util.ApkInstaller
import com.ail0l.app.util.ReleaseInfo
import com.ail0l.app.util.UpdateChecker
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ail0lTheme {
                AppRoot()
            }
        }
    }
}

private enum class Tab(val route: String, val labelRes: Int) {
    CHAT("chat", R.string.tab_chat),
    THOUGHTS("thoughts", R.string.tab_thoughts),
    MODELS("models", R.string.tab_models),
    MEMORY("memory", R.string.tab_memory),
    SETTINGS("settings", R.string.tab_settings)
}

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    var selected by rememberSaveable { mutableIntStateOf(0) }

    AutoUpdateDialog()

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
        }
    }
}

/** Автопроверка обновлений при запуске: если есть релиз новее — предлагаем установить. */
@Composable
private fun AutoUpdateDialog() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<ReleaseInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
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
                TextButton(
                    enabled = !downloading,
                    onClick = {
                        if (downloading) return@TextButton
                        val url = r.apkUrl
                        if (url.isNullOrBlank()) {
                            update = null
                            return@TextButton
                        }
                        downloading = true
                        scope.launch {
                            val dir = File(context.cacheDir, "update").apply { mkdirs() }
                            val apk = File(dir, "ail0l-${r.tag.trimStart('v')}.apk")
                            val ok = UpdateChecker.downloadApk(url, apk)
                            downloading = false
                            if (!ok) return@launch
                            update = null
                            if (!ApkInstaller.canRequestPackageInstalls(context)) {
                                ApkInstaller.openInstallPermissions(context)
                            } else {
                                ApkInstaller.install(context, apk)
                            }
                        }
                    }
                ) { Text(if (downloading) "Скачиваю…" else "Скачать и установить") }
            },
            dismissButton = {
                TextButton(onClick = { update = null }) { Text("Позже") }
            }
        )
    }
}