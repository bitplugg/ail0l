package com.aiia.app.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiia.app.agent.Agent
import com.aiia.app.dm.Dependencies
import com.aiia.app.terminal.TerminalBus
import com.aiia.app.terminal.TerminalControl
import com.aiia.app.terminal.TerminalMode
import com.aiia.app.terminal.TerminalSession
import kotlinx.coroutines.launch

private val ansiPattern = Regex("\u001B\\[[;\\d]*[ -/]*[@-~]")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: (() -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { TerminalSession() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(TerminalMode.SHELL) }
    var modeMenu by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf("") }
    val settings by Dependencies.settings.settings.collectAsState(initial = null)

    LaunchedEffect(Unit) { session.start(mode) }
    LaunchedEffect(Unit) {
        launch {
            session.events.collect { value ->
                output += value
                if (output.length > 200_000) output = output.takeLast(120_000)
            }
        }
        launch {
            TerminalBus.agentOutput.collect { value ->
                output += value
            }
        }
    }
    DisposableEffect(Unit) { onDispose { session.destroy() } }
    LaunchedEffect(output) {
        if (output.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Терминал") },
                navigationIcon = {
                    IconButton(onClick = { onBack?.invoke() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    Text("AIIA", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 16.dp))
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = { modeMenu = true },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
                DropdownMenu(modeMenu, onDismissRequest = { modeMenu = false }) {
                    TerminalMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.name) },
                            onClick = {
                                mode = option
                                modeMenu = false
                                session.start(option)
                            }
                        )
                    }
                }
                Button(
                    onClick = {
                        val text = output.takeLast(12_000)
                        if (text.isBlank()) return@Button
                        scope.launch {
                            val id = Dependencies.db.dao().insertConversation(
                                com.aiia.app.data.entities.ConversationEntity(title = "Разбор терминала")
                            )
                            val prompt = "Разбери логи терминала, найди ошибки и объясни следующие безопасные действия:\n$text"
                            Dependencies.agent.send(id, prompt).collect { event ->
                                when (event) {
                                    is Agent.Event.Token -> analysis += event.text
                                    is Agent.Event.Done -> analysis = event.full
                                    is Agent.Event.Failure -> analysis = event.message
                                    is Agent.Event.ToolRequired -> analysis = "Требуется подтверждение: ${event.call.name}"
                                }
                            }
                        }
                    },
                    enabled = output.isNotBlank()
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Text(" Разобрать в AIIA")
                }
            }
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                color = Color(0xFF101116),
                shape = MaterialTheme.shapes.medium
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)
                ) {
                    item { Text(ansiAnnotated(output), color = Color(0xFFD7E0EA), fontFamily = FontFamily.Monospace, fontSize = (settings?.terminalFontSize ?: 14).sp) }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TerminalControl.entries.forEach { control ->
                            AssistChip(
                                onClick = { session.sendControl(control) },
                                label = { Text(control.name.replace('_', ' '), fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = analysis.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text("AIIA: $analysis", modifier = Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Команда") },
                    maxLines = 4
                )
                Button(
                    onClick = {
                        if (input.isNotBlank()) {
                            session.sendInput(input)
                            input = ""
                        }
                    },
                    modifier = Modifier.padding(start = 6.dp)
                ) { Text("↵") }
            }
        }
    }
}

private fun ansiAnnotated(value: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    ansiPattern.findAll(value).forEach { match ->
        append(value.substring(cursor, match.range.first))
        val code = match.value
        val color = when {
            code.contains("31") -> Color(0xFFFF6B6B)
            code.contains("32") -> Color(0xFF7EE787)
            code.contains("33") -> Color(0xFFFFD866)
            code.contains("34") -> Color(0xFF79C0FF)
            else -> Color(0xFFD7E0EA)
        }
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append("") }
        cursor = match.range.last + 1
    }
    if (cursor < value.length) append(value.substring(cursor))
}
