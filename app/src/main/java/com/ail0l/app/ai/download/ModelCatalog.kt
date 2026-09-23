package com.ail0l.app.ai.download

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

/**
 * Каталог рекомендуемых GGUF-моделей (Qwen2.5 Instruct, квант Q4_K_M).
 */
object ModelCatalog {

    private val ENTRIES = listOf(
        CatalogEntry(
            repo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            family = "Qwen2.5", paramsLabel = "0.5B", quant = "Q4_K_M",
            filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeBytes = 0x1DCD6500L,       // ~500 МБ
            ramNeededBytes = 0x518B69B0L    // ~1.4 ГБ
        ),
        CatalogEntry(
            repo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            family = "Qwen2.5", paramsLabel = "1.5B", quant = "Q4_K_M",
            filename = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 0x41CDB4C0L,        // ~1.1 ГБ
            ramNeededBytes = 0x9F50B200L    // ~2.7 ГБ
        ),
        CatalogEntry(
            repo = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            family = "Qwen2.5", paramsLabel = "3B", quant = "Q4_K_M",
            filename = "qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 0x71B9C510L,        // ~1.9 ГБ
            ramNeededBytes = 0xF2D29A00L    // ~4.1 ГБ
        ),
        CatalogEntry(
            repo = "Qwen/Qwen2.5-7B-Instruct-GGUF",
            family = "Qwen2.5", paramsLabel = "7B", quant = "Q4_K_M",
            filename = "qwen2.5-7b-instruct-q4_k_m.gguf",
            sizeBytes = 0x1190E7E00L,       // ~4.7 ГБ
            ramNeededBytes = 0x25CEF0000L   // ~10.2 ГБ
        )
    )

    fun entries(): List<CatalogEntry> = ENTRIES

    /**
     * Рекомендует модель по ОЗУ устройства: слабые устройства получают
     * маленькую модель, мощные — 7B.
     */
    fun recommend(context: Context): CatalogEntry {
        val usableRam = DeviceProfile.usableRamBytes(context)
        return ENTRIES.lastOrNull { it.ramNeededBytes <= usableRam }
            ?: ENTRIES.first()
    }
}