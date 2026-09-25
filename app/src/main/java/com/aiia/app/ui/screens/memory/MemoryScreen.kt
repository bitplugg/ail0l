package com.aiia.app.ui.screens.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiia.app.data.entities.FactEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(viewModel: MemoryViewModel = viewModel()) {
    val facts by viewModel.facts.collectAsState()
    val inbox by viewModel.inbox.collectAsState()
    val query by viewModel.queryText.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Память") }) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.setQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Найти в памяти…") },
                    shape = MaterialTheme.shapes.extraLarge,
                    singleLine = true
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Psychology, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Факты о тебе (${facts.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }

            if (facts.isEmpty()) {
                item { Text("Пока пусто. Напиши мне «запомни: …» в чате.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(facts, key = { it.id }) { fact ->
                    FactCard(fact, onToggle = { viewModel.toggleFavorite(fact) }, onDelete = { viewModel.deleteFact(fact) })
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Inbox, null, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                    Text("Входящие с других устройств (${inbox.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }

            if (inbox.isEmpty()) {
                item { Text("Сетевой канал пуст. Настрой синхронизацию в Настройках.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(inbox, key = { it.id }) { m ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(m.sender, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text(m.content, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FactCard(fact: FactEntity, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                fact.fact,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onToggle) {
                Icon(
                    if (fact.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (fact.favorite) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
