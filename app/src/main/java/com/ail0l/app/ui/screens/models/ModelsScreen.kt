package com.ail0l.app.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ail0l.app.ai.models.HfFile

@Composable
fun ModelsScreen(viewModel: ModelsViewModel = viewModel()) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val files by viewModel.files.collectAsState()
    val busy by viewModel.searchBusy.collectAsState()
    val error by viewModel.searchError.collectAsState()
    val models by viewModel.models.collectAsState()
    val provision by viewModel.provisionState.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Поиск GGUF-моделей", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Например: qwen gguf") }
            )
            Button(
                onClick = { viewModel.search() },
                enabled = query.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Ищу…" else "Найти")
            }
            if (error != null) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error)
            }
        }

        items(results) { model ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(model.id, style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { viewModel.openModel(model) }) {
                        Text("Показать .gguf-файлы")
                    }
                }
            }
        }

        items(files) { file ->
            HfFileRow(file = file, onInstall = { viewModel.install(file) })
        }

        item {
            HorizontalDivider()
            Text("Установленные и рекомендуемые", style = MaterialTheme.typography.titleLarge)
        }

        items(models) { ui ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("${ui.entry.family} ${ui.entry.paramsLabel} · ${ui.entry.quant}")
                    if (ui.downloading) {
                        LinearProgressIndicator(
                            progress = { ((ui.progress?.downloadedBytes ?: 0L).toFloat() / (ui.progress?.totalBytes?.takeIf { it > 0 } ?: 1L).toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (!ui.installed) {
                        Button(onClick = { viewModel.install(ui.entry) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Скачать")
                        }
                    } else if (!ui.active) {
                        Button(onClick = { viewModel.selectLocal(ui.entry) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Использовать")
                        }
                    }
                }
            }
        }

        if (provision.status == "downloading") {
            item {
                val p = provision.progress
                if (p != null && p.totalBytes > 0) {
                    Text("Скачивание: ${p.downloadedBytes / 1024 / 1024} / ${p.totalBytes / 1024 / 1024} МБ")
                }
            }
        }
    }
}

@Composable
private fun HfFileRow(
    file: HfFile,
    onInstall: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(file.filename, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${formatBytes(file.sizeBytes)} · ${file.quant}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(onClick = onInstall) {
                Text("Скачать")
            }
        }
    }
}

private fun formatBytes(value: Long): String {
    return when {
        value >= 1024 * 1024 * 1024 -> "%.2f ГБ".format(value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024 * 1024 -> "%.1f МБ".format(value / (1024.0 * 1024.0))
        value >= 1024 -> "%.1f КБ".format(value / 1024.0)
        else -> "$value Б"
    }
}
