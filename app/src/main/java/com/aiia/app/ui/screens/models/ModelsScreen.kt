package com.aiia.app.ui.screens.models

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ModelsScreen(viewModel: ModelsViewModel = viewModel()) {
    SharedTransitionLayout {
    val models by viewModel.models.collectAsState()
    val provision by viewModel.provisionState.collectAsState()
    val query by viewModel.query.collectAsState()
    val remoteModels by viewModel.remoteModels.collectAsState()
    val remoteSearching by viewModel.remoteSearching.collectAsState()
    val remoteProgress by viewModel.remoteProgress.collectAsState()
    val remoteError by viewModel.remoteError.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Модели") }) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {

                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Memory, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Твоё устройство", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(viewModel.deviceSummary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.searchHuggingFace() }),
                    label = { Text("Поиск GGUF / Vision на Hugging Face") },
                    placeholder = { Text("например, Qwen2.5-VL") },
                    trailingIcon = {
                        IconButton(onClick = viewModel::searchHuggingFace, enabled = !remoteSearching && query.isNotBlank()) {
                            Icon(Icons.Filled.Search, contentDescription = "Найти")
                        }
                    }
                )
                Text(
                    "Для закрытых репозиториев добавьте Hugging Face token в настройках.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            remoteError?.let { error ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            error,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = viewModel::searchHuggingFace, enabled = !remoteSearching) {
                            Text("Повторить")
                        }
                    }
                }
            }
            if (remoteSearching) {
                item { Text("Ищем модели…", style = MaterialTheme.typography.bodyMedium) }
            }
            if (remoteModels.isNotEmpty()) {
                item {
                    Text(
                        "Результаты Hugging Face",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(remoteModels, key = { it.id }) { catalogModel ->
                    HuggingFaceModelCard(
                        model = catalogModel,
                        progress = remoteProgress,
                        onDownload = viewModel::downloadRemote
                    )
                }
            }

            when (provision.status) {
                "downloading" -> item {
                    val p = provision.progress
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CloudDownload, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Скачиваем ${provision.entry?.paramsLabel ?: ""}…",
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(8.dp))
                            if (p != null && p.totalBytes > 0) {
                                LinearProgressIndicator(
                                    progress = { (p.downloadedBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "${fmt(p.downloadedBytes)} / ${fmt(p.totalBytes)} · ${fmtSpeed(p.speedBps)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                "error" -> item {
                    Text(
                        "Не удалось скачать модель. Проверь сеть и место на диске.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> Unit
            }

            item {
                Text(
                    "Модели под это устройство",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            items(models) { m ->
                ModelCard(
                    ui = m,
                    onInstall = { viewModel.install(m.entry) },
                    onUse = { viewModel.selectLocal(m.entry) },
                    onDelete = { viewModel.delete(m.entry) }
                )
            }
        }
    }
    }
}

@Composable
private fun HuggingFaceModelCard(
    model: com.aiia.app.ai.models.CatalogModel,
    progress: com.aiia.app.ai.models.ModelDownloadProgress?,
    onDownload: (com.aiia.app.ai.models.ModelArtifact) -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${model.model.downloads} загрузок · ${model.model.likes} лайков",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (model.projectors.isNotEmpty()) {
                Text(
                    "Vision: ${model.projectors.joinToString { it.filename }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            (model.ggufFiles + model.projectors).forEach { artifact ->
                val current = progress?.takeIf { it.artifact.filename == artifact.filename }
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(artifact.filename, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                "${if (artifact.isVisionProjector) "mmproj" else artifact.quantization ?: "GGUF"} · ${artifact.displaySize}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { onDownload(artifact) },
                            enabled = current?.state != com.aiia.app.ai.models.ModelDownloadProgress.State.DOWNLOADING
                        ) { Text(if (current == null) "Скачать" else "Повторить") }
                    }
                    current?.let {
                        LinearProgressIndicator(
                            progress = { it.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            if (model.ggufFiles.isEmpty() && model.projectors.isEmpty()) {
                Text("В ответе API нет GGUF-файлов", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ModelCard(ui: ModelUi, onInstall: () -> Unit, onUse: () -> Unit, onDelete: () -> Unit) {
    val colors = if (ui.active)
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    else CardDefaults.cardColors()

    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${ui.entry.family} ${ui.entry.paramsLabel}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "${ui.entry.quant} · ${fmt(ui.entry.sizeBytes)} · ОЗУ ≥ ${ui.entry.ramNeededBytes / (1024 * 1024 * 1024)} ГБ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (ui.recommended && !ui.active) {
                    Text(
                        "рекомендуем",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (ui.active) {
                    Icon(Icons.Filled.CheckCircle, "Активна", tint = MaterialTheme.colorScheme.primary)
                }
            }

            if (ui.downloading) {
                Spacer(Modifier.height(8.dp))
                val p = ui.progress
                if (p != null && p.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { (p.downloadedBytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else if (!ui.installed) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Text("Скачать с Hugging Face")
                }
            } else if (!ui.active) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onUse, modifier = Modifier.weight(1f)) {
                        Text("Использовать")
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Удалить")
                    }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Удалить (активная)")
                }
            }
        }
    }
}

private fun fmt(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f ГБ", bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f МБ", bytes / 1048576.0)
    else -> "$bytes Б"
}

private fun fmtSpeed(bps: Long): String = when {
    bps >= 1024L * 1024 -> String.format(Locale.US, "%.1f МБ/с", bps / 1048576.0)
    bps >= 1024L -> "${bps / 1024} КБ/с"
    else -> "…"
}
