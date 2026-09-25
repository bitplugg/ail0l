package com.aiia.app.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiia.app.ai.Engine
import com.aiia.app.dm.Dependencies
import com.aiia.app.plugins.store.PluginStoreViewModel
import com.aiia.app.plugins.store.StorePlugin
import java.util.Locale

private enum class SettingsCategory(val title: String, val icon: ImageVector) {
    ENGINE("Движок и Модели", Icons.Filled.Memory),
    NETWORK("Сеть и локальный сервер", Icons.Filled.CloudSync),
    SYSTEM("Система и инструменты", Icons.Filled.Security),
    EXTENSIONS("Расширения и MCP", Icons.Filled.Extension),
    MEMORY("Память и RAG", Icons.Filled.Psychology),
    APPEARANCE("Интерфейс и внешний вид", Icons.Filled.Palette)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val category = selected?.let { runCatching { SettingsCategory.valueOf(it) }.getOrNull() }
    SharedTransitionLayout {
        AnimatedContent(targetState = category, label = "settings-category") { target ->
            if (target == null) {
                SettingsRoot(onOpen = { selected = it.name })
            } else {
                SettingsCategoryScreen(
                    category = target,
                    viewModel = viewModel,
                    onBack = { selected = null }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot(onOpen: (SettingsCategory) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("AIIA", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Все параметры разбиты по категориям", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(SettingsCategory.entries) { category ->
                ElevatedCard(onClick = { onOpen(category) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(category.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Text(category.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("›", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCategoryScreen(
    category: SettingsCategory,
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val s by viewModel.settings.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(category.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                when (category) {
                    SettingsCategory.ENGINE -> EngineSettings(s, viewModel)
                    SettingsCategory.NETWORK -> NetworkSettings(s, viewModel, syncStatus)
                    SettingsCategory.SYSTEM -> SystemSettings(s, viewModel)
                    SettingsCategory.EXTENSIONS -> ExtensionSettings(s, viewModel)
                    SettingsCategory.MEMORY -> MemorySettings(s, viewModel)
                    SettingsCategory.APPEARANCE -> AppearanceSettings(s, viewModel)
                }
            }
        }
    }
}

@Composable
private fun EngineSettings(s: com.aiia.app.data.Settings, vm: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Движок", style = MaterialTheme.typography.titleLarge)
        Field("Системный промпт", s.persona, { vm.setPersona(it) })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EngineChip(Engine.LOCAL, "Локально", s.engine, vm::setEngine)
            EngineChip(Engine.MISTRAL, "Mistral", s.engine, vm::setEngine)
            EngineChip(Engine.OPENAI, "OpenAI", s.engine, vm::setEngine)
            EngineChip(Engine.ANTHROPIC, "Claude", s.engine, vm::setEngine)
        }
        Field("Активная GGUF-модель", s.localModelPath, { })
        Field("mmproj-*.gguf", s.mmprojPath, { vm.setMmproj(it) })
        Field("LoRA .gguf/.bin", s.loraPath, { vm.setLora(it, s.loraScale) })
        Text("Масштаб LoRA: ${"%.2f".format(Locale.US, s.loraScale)}")
        Slider(s.loraScale, { vm.setLora(s.loraPath, it) }, valueRange = 0f..2f)
        Text("Контекст: ${s.contextLength} токенов")
        Slider(s.contextLength.toFloat(), { value ->
            vm.setSampling(s.temperature, s.topK, s.topP, value.toInt())
        }, valueRange = 128f..4096f)
        Text("Потоки CPU: ${if (s.cpuThreads == 0) "авто" else s.cpuThreads}")
        Slider(s.cpuThreads.toFloat(), { vm.setCompute(it.toInt(), s.flashAttention) }, valueRange = 0f..16f)
        Toggle("Flash attention", s.flashAttention) { vm.setCompute(s.cpuThreads, it) }
        Toggle("KV-кэш контекста", s.kvCacheEnabled, { vm.setKvCache(it) })
        Toggle("Прогревать модель", s.preloadModel, { vm.setPreloadModel(it) })
    }
}

@Composable
private fun NetworkSettings(s: com.aiia.app.data.Settings, vm: SettingsViewModel, status: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Toggle("Local OpenAI API", s.apiEnabled) { vm.setApi(it, s.apiPort, s.apiToken) }
        Field("Порт API", s.apiPort.toString(), { vm.setApi(s.apiEnabled, it.toIntOrNull() ?: 8080, s.apiToken) })
        PasswordField("Токен доступа", s.apiToken, { vm.setApi(s.apiEnabled, s.apiPort, it) })
        Toggle("P2P Sync", s.p2pEnabled) { vm.setP2p(it, s.p2pPort) }
        Field("P2P порт", s.p2pPort.toString(), { vm.setP2p(s.p2pEnabled, it.toIntOrNull() ?: 8081) })
        Toggle("Синхронизация", s.syncEnabled) { vm.setSync(it, s.syncBaseUrl, s.syncPollMinutes) }
        Field("Адрес облачного канала", s.syncBaseUrl, { vm.setSync(s.syncEnabled, it, s.syncPollMinutes) })
        Field("ID устройства", s.syncDeviceId, { vm.setAntenna(it, s.syncPassword) })
        Field("Имя устройства", s.syncDeviceName, { vm.setDeviceName(it) })
        PasswordField("Пароль AES/PBKDF2", s.syncPassword, { vm.setAntenna(s.syncDeviceId, it) })
        Field("Интервал, мин", s.syncPollMinutes.toString(), { vm.setSync(s.syncEnabled, s.syncBaseUrl, it.toIntOrNull() ?: 15) })
        Button(vm::syncNow, Modifier.fillMaxWidth()) { Text("Синхронизировать сейчас") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SystemSettings(s: com.aiia.app.data.Settings, vm: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Toggle("Shizuku", s.shizukuEnabled, { vm.setShizuku(it) })
        Toggle("Root-доступ", s.rootEnabled, { vm.setRoot(it) })
        Toggle("Подтверждать системные вызовы", s.confirmToolCalls, { vm.setConfirmTools(it) })
        Text("Режим терминала", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("shell" to "Shell", "root" to "Root", "shizuku" to "Shizuku").forEach { (value, label) ->
                FilterChip(s.terminalMode == value, { vm.setTerminalMode(value) }, { Text(label) })
            }
        }
        Text("Команды доступны из встроенного терминала и требуют подтверждения.")
    }
}

@Composable
private fun ExtensionSettings(s: com.aiia.app.data.Settings, vm: SettingsViewModel) {
    val plugins by Dependencies.plugins.plugins.collectAsState()
    val installState by Dependencies.plugins.state.collectAsState()
    val storeVm: PluginStoreViewModel = viewModel()
    val store by storeVm.state.collectAsState()
    LaunchedEffect(Unit) { storeVm.refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Плагины .dex/.jar и пакеты .aiip", style = MaterialTheme.typography.titleMedium)
        Text("Загружено плагинов: ${plugins.size}")
        Button(onClick = vm::reloadPlugins, modifier = Modifier.fillMaxWidth()) { Text("Пересканировать /sdcard/AIIA/plugins") }
        Text("Каталог разработки: /sdcard/AIIA/plugins")
        Text("Установка пакета требует подтверждения прав из manifest.json.")
        Field("MCP серверы (JSON)", s.mcpServersJson, { vm.setMcp(it) })
        Text("Поддерживаются stdio и HTTP/SSE транспорты MCP.")
        val mcpRecords by com.aiia.app.plugins.mcp.McpCallJournal.records.collectAsState()
        if (mcpRecords.isNotEmpty()) {
            Text("Журнал MCP-вызовов", style = MaterialTheme.typography.titleSmall)
            mcpRecords.take(5).forEach { record ->
                Text(
                    "${if (record.success) "✓" else "✕"} ${record.server}/${record.tool}: ${record.output.take(120)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
        Text("Магазин AIIA", style = MaterialTheme.typography.titleMedium)
        Text("bitplugg/aiia-plugin-store · SHA-256 проверяется до установки")
        OutlinedButton(
            onClick = storeVm::refresh,
            enabled = !store.loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (store.loading) "Загружаю каталог…" else "Обновить каталог") }
        store.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        store.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        store.catalog?.plugins.orEmpty().forEach { plugin ->
            PluginStoreCard(
                plugin = plugin,
                installed = plugins.any { it.manifest.id == plugin.id },
                downloading = store.downloading == plugin.slug,
                onDownload = { storeVm.download(plugin) }
            )
        }
    }
    val pending = installState as? com.aiia.app.plugins.engine.InstallState.AwaitingPermission
    pending?.let {
        AlertDialog(
            onDismissRequest = vm::rejectPlugin,
            title = { Text("Разрешить установку плагина?") },
            text = { Text("${it.manifest.name}\n${it.manifest.permissions.joinToString { p -> p.name }}") },
            confirmButton = { TextButton(onClick = vm::confirmPlugin) { Text("Установить") } },
            dismissButton = { TextButton(onClick = vm::rejectPlugin) { Text("Отмена") } }
        )
    }
}

@Composable
private fun MemorySettings(s: com.aiia.app.data.Settings, vm: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Toggle("Семантическая память", s.ragEnabled) { vm.setRag(it, s.embeddingModelPath, s.embeddingDimensions) }
        Field("ONNX embedding model", s.embeddingModelPath, { vm.setRag(s.ragEnabled, it, s.embeddingDimensions) })
        Text("Размер вектора: ${s.embeddingDimensions}")
        Slider(s.embeddingDimensions.toFloat(), { vm.setRag(s.ragEnabled, s.embeddingModelPath, it.toInt()) }, valueRange = 128f..1024f)
        Toggle("Хранить факты", s.memoryEnabled, { vm.setMemoryEnabled(it) })
        Toggle("Автообучение", s.autoLearnEnabled, { vm.setAutoLearn(it) })
    }
}

@Composable
private fun AppearanceSettings(s: com.aiia.app.data.Settings, vm: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Тема", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("auto" to "Авто", "light" to "Светлая", "dark" to "Тёмная").forEach { (value, label) ->
                FilterChip(s.systemDark == value, { vm.setSystemDark(value) }, { Text(label) })
            }
        }
        Toggle("Dynamic Color", s.dynamicColor, { vm.setDynamicColor(it) })
        Toggle("Анимации", s.animationsEnabled, { vm.setAnimations(it) })
        Text("Размер шрифта терминала: ${s.terminalFontSize}")
        Slider(s.terminalFontSize.toFloat(), { vm.setTerminalFontSize(it.toInt()) }, valueRange = 8f..32f)
    }
}

@Composable
private fun PluginStoreCard(
    plugin: StorePlugin,
    installed: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(plugin.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${plugin.format.uppercase()} · ${plugin.tool} · ${plugin.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (installed) Text("Установлен", color = MaterialTheme.colorScheme.primary)
            }
            Text(plugin.description, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = onDownload,
                enabled = !downloading && !installed,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        installed -> "Установлено"
                        downloading -> "Загрузка…"
                        else -> "Скачать и проверить"
                    }
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true
    )
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation()
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun EngineChip(value: Engine, label: String, selected: Engine, onChange: (Engine) -> Unit) {
    FilterChip(selected = value == selected, onClick = { onChange(value) }, label = { Text(label) })
}
