package com.aiia.app.ui.chat

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.aiia.app.ai.ImageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object ImageStorage {
    suspend fun persist(context: Context, uri: Uri): ImageAttachment? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "image/jpeg"
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
            val target = File(context.cacheDir, "chat-images").apply { mkdirs() }
                .resolve("${UUID.randomUUID()}.$extension")
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            } ?: return@runCatching null
            ImageAttachment(target.absolutePath, mime, target.name)
        }.getOrNull()
    }
}
