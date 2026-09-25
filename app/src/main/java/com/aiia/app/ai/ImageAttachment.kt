package com.aiia.app.ai

data class ImageAttachment(
    val uri: String,
    val mimeType: String = "image/*",
    val displayName: String? = null
) {
    val isContentUri: Boolean get() = uri.startsWith("content://")
}
