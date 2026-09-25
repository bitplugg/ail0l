package com.aiia.app.ui.chat

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiia.app.data.entities.MessageEntity
import com.aiia.app.dm.Dependencies
import com.aiia.app.util.Markdown
import com.aiia.app.util.Stt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val conversations by viewModel.conversations.collectAsState()
    val current by viewModel.currentConversation.collectAsState()
    val input by viewModel.input.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val personas by viewModel.personas.collectAsState()
    val pendingToolCall by viewModel.pendingToolCall.collectAsState()
    val appSettings by Dependencies.settings.settings.collectAsState(initial = null)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var menuOpen by remember { mutableStateOf(false) }
    var personaMenuOpen by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::addAttachment)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCameraUri
        if (saved && uri != null) viewModel.addAttachment(uri)
    }
    fun openCamera() {
        val file = java.io.File(context.cacheDir, "chat-images").apply { mkdirs() }
            .resolve("camera-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    fun beginStt() {
        scope.launch {
            val text = Stt.recognize(context)
            if (text != null) {

                if (input.isBlank() && text.startsWithAnyCommand()) {
                    viewModel.sendVoice(text)
                } else {
                    viewModel.setInput(if (input.isBlank()) text else "$input $text")
                }
            }
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginStt()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            viewModel.importConversation(text ?: "")
        }
    }

    fun exportShare() {
        scope.launch {
            val text = viewModel.exportConversation()
            if (text.isNullOrBlank()) {
                viewModel.showNotice("Нет сообщений для экспорта.")
            } else {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                runCatching {
                    context.startActivity(Intent.createChooser(send, "Экспорт беседы"))
                }.onFailure { viewModel.showNotice("Нет приложения для экспорта") }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(current?.title ?: "Новый диалог", maxLines = 1)
                        TextButton(onClick = { personaMenuOpen = true }) {
                            Text(
                                personas.firstOrNull { it.id == appSettings?.activePersonaId }?.name ?: "Персона",
                                maxLines = 1
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = personaMenuOpen,
                        onDismissRequest = { personaMenuOpen = false }
                    ) {
                        personas.forEach { persona ->
                            DropdownMenuItem(
                                text = { Text(persona.name) },
                                onClick = {
                                    personaMenuOpen = false
                                    viewModel.selectPersona(persona.id)
                                }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Ещё")
                    }
                    IconButton(onClick = { viewModel.newConversation() }) {
                        Icon(Icons.Filled.Add, contentDescription = "Новый диалог")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Экспорт беседы") },
                    leadingIcon = { Icon(Icons.Filled.IosShare, null) },
                    onClick = { menuOpen = false; exportShare() }
                )
                DropdownMenuItem(
                    text = { Text("Импорт беседы из файла") },
                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                    onClick = { menuOpen = false; importLauncher.launch(arrayOf("text/*", "text/plain")) }
                )
                DropdownMenuItem(
                    text = { Text("Отправить всё в сетевой канал") },
                    leadingIcon = { Icon(Icons.Filled.Sync, null) },
                    onClick = { menuOpen = false; viewModel.outboxConversation() }
                )
            }

            ChatTab(
                viewModel = viewModel,
                input = input,
                sending = sending,
                quickMode = viewModel.quickMode.collectAsState().value,
                onToggleQuick = viewModel::toggleQuickMode,
                attachments = viewModel.attachments.collectAsState().value,
                onAddGallery = { galleryLauncher.launch("image/*") },
                onAddCamera = ::openCamera,
                onRemoveAttachment = viewModel::removeAttachment,
                onMic = {
                    if (it) beginStt() else micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                },
                clipboardText = { text -> clipboard.setText(AnnotatedString(text)) }
            )

            notice?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    pendingToolCall?.let { call ->
        AlertDialog(
            onDismissRequest = viewModel::rejectTool,
            title = { Text(if (call.isMcp) "Подтвердите MCP-вызов" else "Подтвердите инструмент") },
            text = {
                Text(
                    if (call.isMcp) {
                        "${call.mcpServer}/${call.mcpTool}\n${call.mcpArguments}"
                    } else {
                        "${call.name}\n${call.arguments.entries.joinToString("\n") { "${it.key}=${it.value}" }}"
                    }
                )
            },
            confirmButton = { TextButton(onClick = viewModel::approveTool) { Text("Выполнить") } },
            dismissButton = { TextButton(onClick = viewModel::rejectTool) { Text("Отмена") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTab(
    viewModel: ChatViewModel,
    input: String,
    sending: Boolean,
    quickMode: Boolean,
    onToggleQuick: () -> Unit,
    attachments: List<com.aiia.app.ai.ImageAttachment>,
    onAddGallery: () -> Unit,
    onAddCamera: () -> Unit,
    onRemoveAttachment: (com.aiia.app.ai.ImageAttachment) -> Unit,
    onMic: (Boolean) -> Unit,
    clipboardText: (String) -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val streaming by viewModel.streaming.collectAsState()
    val error by viewModel.error.collectAsState()
    val conversations by viewModel.conversations.collectAsState()

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, streaming, error) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (conversations.size > 1) {
            LazyColumn(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        conversations.forEach { c ->
                            AssistChip(
                                onClick = { viewModel.selectConversation(c.id) },
                                label = { Text(c.title.take(14), maxLines = 1) }
                            )
                        }
                    }
                }
                item { HorizontalDivider() }
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, clipboardText, viewModel::regenerate)
            }
            if (streaming.isNotEmpty()) {
                item { StreamingBubble(streaming) }
            }
            error?.let {
                item {
                    Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (attachments.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 72.dp).padding(horizontal = 12.dp)) {
                items(attachments) { image ->
                    AssistChip(
                        onClick = { onRemoveAttachment(image) },
                        label = { Text(image.displayName ?: "Изображение", maxLines = 1) },
                        trailingIcon = { Icon(Icons.Filled.Add, contentDescription = "Удалить") }
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { viewModel.setInput(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Спроси о чём-нибудь…") },
                shape = MaterialTheme.shapes.extraLarge,
                maxLines = 5
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = quickMode,
                onClick = onToggleQuick,
                enabled = !sending,
                label = { Text("Быстрый", maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.Bolt, null, modifier = Modifier.height(16.dp)) }
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onAddGallery) {
                Icon(Icons.Filled.AddAPhoto, contentDescription = "Изображение из галереи")
            }
            IconButton(onClick = onAddCamera) {
                Icon(Icons.Filled.AddAPhoto, contentDescription = "Снимок камеры")
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { onMic(false) }) {
                Icon(Icons.Filled.Mic, contentDescription = "Голос")
            }
            Spacer(Modifier.width(4.dp))
            FilledIconButton(onClick = { if (!sending) viewModel.send() }, modifier = Modifier.align(Alignment.Bottom)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: MessageEntity, clipboardText: (String) -> Unit, onRegenerate: () -> Unit) {
    val isUser = msg.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Surface(
                modifier = Modifier.animateContentSize(spring(stiffness = Spring.StiffnessLow)),
                color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = if (isUser)
                    RoundedCornerShape(topStart = 24.dp, topEnd = 8.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                else
                    RoundedCornerShape(topStart = 8.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
            ) {
                val accent = MaterialTheme.colorScheme.primary
                Column {
                    msg.attachments.split('\n').filter { it.isNotBlank() }.forEach { path ->
                        val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                        bitmap?.let {
                            Image(
                                bitmap = it,
                                contentDescription = "Прикреплённое изображение",
                                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Text(
                        text = if (isUser) AnnotatedString(msg.content)
                        else Markdown.render(msg.content, accent),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 320.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            if (!isUser && msg.status == "done" && msg.content.isNotBlank()) {
                Row(Modifier.padding(top = 2.dp)) {
                    IconButton(onClick = { clipboardText(msg.content) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать", modifier = Modifier.height(18.dp))
                    }
                    IconButton(onClick = onRegenerate) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Перегенерировать", modifier = Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}

private fun String.startsWithAnyCommand(): Boolean {
    val t = trim().lowercase()
    return listOf("запомни", "забудь", "что знаешь", "найди в интернете", "позови", "помоги")
        .any { t.startsWith(it) || t == it }
}

@Composable
private fun StreamingBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            modifier = Modifier.animateContentSize(spring(stiffness = Spring.StiffnessLow)),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
        ) {
            Text(
                text = Markdown.render(text + " ▌", MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 320.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
