package com.aiia.app.ai.download

import android.content.Context

data class CatalogEntry(
    val repo: String,
    val family: String,
    val paramsLabel: String,
    val quant: String,
    val filename: String,
    val sizeBytes: Long,
    val ramNeededBytes: Long
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repo/resolve/main/$filename"
}

object ModelCatalog {

    private val ENTRIES = listOf(
        CatalogEntry(
            repo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            family = "Qwen2.5", paramsLabel = "0.5B", quant = "Q4_K_M",
            filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 0x1DCD6500L,
            ramNeededBytes = 0x518B69B0L
        ),
        CatalogEntry(
            repo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            family = "Qwen2.5", paramsLabel = "1.5B", quant = "Q4_K_M",
            filename = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 0x41CDB4C0L,
            ramNeededBytes = 0x9F50B200L
        ),
        CatalogEntry(
            repo = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            family = "Qwen2.5", paramsLabel = "3B", quant = "Q4_K_M",
            filename = "qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 0x71B9C510L,
            ramNeededBytes = 0xF2D29A00L
        ),
        CatalogEntry(
            repo = "Qwen/Qwen2.5-7B-Instruct-GGUF",
            family = "Qwen2.5", paramsLabel = "7B", quant = "Q4_K_M",
            filename = "qwen2.5-7b-instruct-q4_k_m.gguf",
            sizeBytes = 0x1190E7E00L,
            ramNeededBytes = 0x25CEF0000L
        )
    )

    fun entries(): List<CatalogEntry> = ENTRIES

    fun recommend(context: Context): CatalogEntry {
        val usableRam = DeviceProfile.usableRamBytes(context)
        return ENTRIES.lastOrNull { it.ramNeededBytes <= usableRam }
            ?: ENTRIES.first()
    }
}
