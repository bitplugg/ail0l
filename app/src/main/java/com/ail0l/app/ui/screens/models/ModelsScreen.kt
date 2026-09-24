package com.ail0l.app.ui.screens.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ail0l.app.ai.models.HfFile
import com.ail0l.app.ai.models.HfModel

@Composable
fun ModelsScreen(viewModel: ModelsViewModel = viewModel()) {
    val query by viewModel.query.collectAsState(); val results by viewModel.searchResults.collectAsState(); val files by viewModel.files.collectAsState(); val busy by viewModel.searchBusy.collectAsState(); val error by viewModel.searchError.collectAsState()
    val models by viewModel.models.collectAsState(); val provision by viewModel.provisionState.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Модели Hugging Face", style = MaterialTheme.typography.headlineSmall); OutlinedTextField(query, viewModel::setQuery, Modifier.fillMaxWidth(), label = { Text("Поиск GGUF-моделей") }); Button({ viewModel.search() }, enabled = query.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Ищу…" else "Найти") }; error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(results) { model -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(model.id, style = MaterialTheme.typography.titleMedium); TextButton({ viewModel.openModel(model) }) { Text("Показать GGUF-файлы") } } } }
        items(files) { file -> HfFileRow(file, viewModel::install) }
        item { HorizontalDivider(); Text("Установленные и рекомендуемые", style = MaterialTheme.typography.titleLarge) }
        items(models) { ui -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("${ui.entry.family} ${ui.entry.paramsLabel} · ${ui.entry.quant}"); if (ui.downloading) LinearProgressIndicator(Modifier.fillMaxWidth()); else if (!ui.installed) Button({ viewModel.install(ui.entry) }) { Text("Скачать") } else if (!ui.active) TextButton({ viewModel.selectLocal(ui.entry) }) { Text("Использовать") } } } }
    }
}
@Composable private fun HfFileRow(file: HfFile, install: (HfFile) -> Unit) { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(file.filename); Text(if (file.sizeBytes > 0) "${file.sizeBytes / 1_000_000} MB · ${file.quant}" else file.quant, style = MaterialTheme.typography.bodySmall) }; Button({ install(file) }) { Text("Скачать") } } } }
