package com.ail0l.app.ui.screens.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ail0l.app.ai.Engine
import com.ail0l.app.util.ApkInstaller
import com.ail0l.app.util.ReleaseInfo
import com.ail0l.app.util.UpdateChecker
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val s by viewModel.settings.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SectionCard(title = "Движок", icon = { Icon(Icons.Filled.Memory, null) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineRow(Engine.LOCAL, "Локально (llama.cpp)", s.engine) { viewModel.setEngine(it) }
                    EngineRow(Engine.MISTRAL, "Mistral API", s.engine) { viewModel.setEngine(it) }
                    EngineRow(Engine.OPENAI, "OpenAI-совместимый", s.engine) { viewModel.setEngine(it) }
                    EngineRow(Engine.ANTHROPIC, "Anthropic API", s.engine) { viewModel.setEngine(it) }
                }
            } }

            item { SectionCard(title = "Ключи API", icon = { Icon(Icons.Filled.Dns, null) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (s.engine == Engine.MISTRAL) {
                        LabeledField("API-ключ Mistral", s.mistralApiKey,
                            onText = { viewModel.setMistral(it, s.mistralModel) })
                        LabeledField("Модель Mistral", s.mistralModel,
                            onText = { viewModel.setMistral(s.mistralApiKey, it) })
                    }
                    if (s.engine == Engine.OPENAI) {
                        LabeledField("Base URL", s.openAiBaseUrl,
                            onText = { viewModel.setOpenAi(it, s.openAiApiKey, s.openAiModel) })
                        LabeledField("API-ключ", s.openAiApiKey,
                            onText = { viewModel.setOpenAi(s.openAiBaseUrl, it, s.openAiModel) })
                        LabeledField("Модель", s.openAiModel,
                            onText = { viewModel.setOpenAi(s.openAiBaseUrl, s.openAiApiKey, it) })
                    }
                    if (s.engine == Engine.ANTHROPIC) {
                        LabeledField("API-ключ Anthropic", s.anthropicApiKey,
                            onText = { viewModel.setAnthropic(it, s.anthropicModel) })
                        LabeledField("Модель", s.anthropicModel,
                            onText = { viewModel.setAnthropic(s.anthropicApiKey, it) })
                    }
                }
            } }

            item { SectionCard(title = "Генерация", icon = { Icon(Icons.Filled.Tune, null) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SamplingControls(
                        temperature = s.temperature,
                        topK = s.topK,
                        topP = s.topP,
                        contextLength = s.contextLength,
                        onChange = viewModel::setSampling
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Потоки CPU: ${if (s.cpuThreads == 0) "авто" else s.cpuThreads}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = s.cpuThreads.toFloat(),
                        onValueChange = { viewModel.setCompute(it.toInt(), s.flashAttention) },
                        valueRange = 0f..8f,
                        steps = 8
                    )
                    ToggleRow("Flash attention", s.flashAttention) { viewModel.setCompute(s.cpuThreads, it) }
                }
            } }

            item { SectionCard(title = "Голос и уведомления", icon = { Icon(Icons.Filled.VolumeUp, null) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleRow("Озвучивать ответы (TTS)", s.ttsEnabled, viewModel::setTts)
                    ToggleRow("Микрофон в чате (STT)", s.sttEnabled, viewModel::setStt)
                    ToggleRow("Уведомления о входящих", s.notifyEnabled, viewModel::setNotify)
                }
            } }

            item { SectionCard(title = "Внешний вид", icon = { Icon(Icons.Filled.Visibility, null) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip("Авто", "auto", s.systemDark, viewModel::setSystemDark)
                    ThemeChip("Светлая", "light", s.systemDark, viewModel::setSystemDark)
                    ThemeChip("Тёмная", "dark", s.systemDark, viewModel::setSystemDark)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Тема применяется сразу после выбора.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } }

            item { SectionCard(title = "Личность", icon = { Icon(Icons.Filled.Person, null) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = s.persona,
                        onValueChange = viewModel::setPersona,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Системный промпт") },
                        maxLines = 4
                    )
                    ToggleRow("Память (факты о тебе)", s.memoryEnabled, viewModel::setMemoryEnabled)
                    ToggleRow("Автообучение из реплик", s.autoLearnEnabled, viewModel::setAutoLearn)
                }
            } }

            item { SectionCard(title = "Синхронизация (сетевой канал)", icon = { Icon(Icons.Filled.Hub, null) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleRow("Включить", s.syncEnabled,
                        { viewModel.setSync(it, s.syncBaseUrl, s.syncPollMinutes) })
                    LabeledField("Адрес сервера", s.syncBaseUrl,
                        onText = { viewModel.setSync(s.syncEnabled, it, s.syncPollMinutes) })
                    LabeledField("ID устройства", s.syncDeviceId,
                        onText = { viewModel.setAntenna(it, s.syncPassword) })
                    LabeledField("Имя устройства", s.syncDeviceName,
                        onText = viewModel::setDeviceName)
                    LabeledField("Пароль канала", s.syncPassword,
                        onText = { viewModel.setAntenna(s.syncDeviceId, it) })
                    LabeledField("Контакты (имя=id в каждой строке)", contactsToText(s.contactsJson),
                        onText = { viewModel.setContacts(textToContacts(it)) })
                    LabeledNumberField("Интервал, мин (≥15)", s.syncPollMinutes.coerceAtLeast(15),
                        { viewModel.setSync(s.syncEnabled, s.syncBaseUrl, it) })
                    Button(onClick = viewModel::syncNow, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.CloudSync, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Синхронизировать сейчас")
                    }
                    syncStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("Ошибка")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } }

            item { SectionCard(title = "Поиск в интернете", icon = { Icon(Icons.Filled.Dns, null) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField("URL (SearXNG JSON; пусто = DDG+Wikipedia)", s.searchUrl,
                        onText = { viewModel.setSearch(it, s.searchKey) })
                    LabeledField("API-ключ (опционально)", s.searchKey,
                        onText = { viewModel.setSearch(s.searchUrl, it) })
                    Text("Команда в чате: «найди в интернете: запрос»",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }

            item { SectionCard(title = "Hugging Face", icon = { Icon(Icons.Filled.CloudSync, null) }) {
                LabeledField("Токен (для закрытых моделей)", s.hfToken, onText = viewModel::setHfToken)
            } }

            item { UpdateCard() }

            item { ResetCard(onReset = { viewModel.reset() }) }

            item {
                Text(viewModel.deviceSummary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: @Composable () -> Unit, content: @Composable () -> Unit) {
    ElevateCard(icon = icon, title = title, content = content)
}

@Composable
private fun ResetCard(onReset: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Сброс", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Вернуть все настройки к значениям по умолчанию. Модель и память не трогаются.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { showConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Filled.DeleteSweep, null)
                Spacer(Modifier.width(8.dp))
                Text("Сбросить настройки")
            }
        }
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Сбросить настройки?") },
            text = { Text("Все настройки вернутся к значениям по умолчанию. Модель и память не удаляются.") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onReset() }) { Text("Сбросить") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun ElevateCard(icon: @Composable () -> Unit, title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun EngineRow(engine: Engine, label: String, selected: Engine, onClick: (Engine) -> Unit) {
    FilterChip(
        selected = selected == engine,
        onClick = { onClick(engine) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var upToDate by remember { mutableStateOf(false) }

    val currentVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "?"
    }

    fun check() {
        if (checking) return
        checking = true
        upToDate = false
        release = null
        message = null
        scope.launch {
            val r = UpdateChecker.latestRelease()
            checking = false
            when {
                r == null -> message = "Не удалось проверить обновления (проверьте сеть)."
                UpdateChecker.isNewer(r.tag, currentVersion) -> release = r
                else -> upToDate = true
            }
        }
    }

    fun downloadAndInstall(r: ReleaseInfo) {
        val url = r.apkUrl
        if (url.isNullOrBlank()) {
            message = "В релизе нет APK для установки."
            return
        }
        if (downloading) return
        downloading = true
        message = "Скачиваю обновление…"
        scope.launch {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val apk = File(dir, "ail0l-${r.tag.trimStart('v')}.apk")
            apk.delete()
            val ok = UpdateChecker.downloadApk(url, apk)
            downloading = false
            if (!ok) {
                message = "Не удалось скачать обновление."
            } else if (!ApkInstaller.canRequestPackageInstalls(context)) {
                message = "Разрешите установку приложений из этого источника."
                ApkInstaller.openInstallPermissions(context)
            } else {
                message = "APK скачан. Запускаю установку…"
                ApkInstaller.install(context, apk)
            }
        }
    }

    SectionCard(title = "Обновления", icon = { Icon(Icons.Filled.SystemUpdate, null) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Установленная версия: $currentVersion", style = MaterialTheme.typography.bodyMedium)

            release?.let { r ->
                Text(
                    "Доступна версия ${r.tag}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (r.body.isNotBlank()) {
                    Text(
                        r.body.take(500),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = { downloadAndInstall(r) },
                    enabled = !downloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.SystemUpdate, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (downloading) "Скачивается…" else "Скачать и установить")
                }
            }

            if (checking) {
                Text("Проверяю обновления…", style = MaterialTheme.typography.bodySmall)
            }
            upToDate.let { u ->
                if (u) {
                    Text(
                        "У вас актуальная версия.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = { check() },
                enabled = !checking && !downloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Проверить обновления")
            }
        }
    }
}

@Composable
private fun ThemeChip(label: String, value: String, current: String, onSelect: (String) -> Unit) {
    FilterChip(
        selected = current == value,
        onClick = { onSelect(value) },
        label = { Text(label, maxLines = 1) }
    )
}

@Composable
private fun LabeledField(label: String, value: String, onText: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onText,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true
    )
}

@Composable
private fun LabeledNumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { onChange(it.toIntOrNull() ?: 15) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun contactsToText(json: String): String = runCatching {
    Json.parseToJsonElement(json).jsonObject.entries
        .joinToString("\n") { "${it.key}=${it.value.jsonPrimitive.contentOrNull.orEmpty()}" }
}.getOrDefault("")

private fun textToContacts(text: String): String = runCatching {
    val obj = buildJsonObject {
        text.lines().forEach { line ->
            val kv = line.trim().split('=', limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) {
                put(kv[0].trim(), JsonPrimitive(kv[1].trim()))
            }
        }
    }
    obj.toString()
}.getOrDefault("{}")

@Composable
private fun SamplingControls(
    temperature: Float,
    topK: Int,
    topP: Float,
    contextLength: Int,
    onChange: (Float, Int, Float, Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Temperature: ${"%.2f".format(Locale.US, temperature)}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = temperature, onValueChange = {
            kotlin.runCatching { onChange(it, topK, topP, contextLength) }
        }, valueRange = 0f..1.5f, steps = 29)
        Text("Top-K: $topK", style = MaterialTheme.typography.bodyMedium)
        Slider(value = topK.toFloat(), onValueChange = {
            onChange(temperature, it.toInt(), topP, contextLength)
        }, valueRange = 1f..100f, steps = 98)
        Text("Top-P: ${"%.2f".format(Locale.US, topP)}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = topP, onValueChange = {
            onChange(temperature, topK, it, contextLength)
        }, valueRange = 0.1f..1f, steps = 89)
        Text("Контекст: $contextLength токенов", style = MaterialTheme.typography.bodyMedium)
        Slider(value = contextLength.toFloat(), onValueChange = {
            onChange(temperature, topK, topP, it.toInt())
        }, valueRange = 128f..2048f, steps = 14)
    }
}