package com.aiia.app.ai.embeddings

/** A downloadable ONNX sentence-embedding model together with the vocabulary it needs. */
data class EmbeddingModelSpec(
    val id: String,
    val label: String,
    val repo: String,
    val modelFile: String,
    val vocabFile: String,
    val sizeBytes: Long,
    val dimensions: Int
) {
    val modelUrl: String get() = "https://huggingface.co/$repo/resolve/main/$modelFile"
    val vocabUrl: String get() = "https://huggingface.co/$repo/resolve/main/$vocabFile"

    fun directoryName(): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun displaySize(): String = "%.0f МБ".format(sizeBytes / 1024.0 / 1024.0)
}

object EmbeddingModels {

    /**
     * distiluse-base-multilingual-cased-v2: WordPiece vocabulary (the tokenizer the app
     * implements), Russian included, and an arm64 int8 graph that runs acceptably on a phone.
     */
    val DISTILUSE = EmbeddingModelSpec(
        id = "distiluse-base-multilingual-cased-v2",
        label = "distiluse multilingual v2",
        repo = "sentence-transformers/distiluse-base-multilingual-cased-v2",
        modelFile = "onnx/model_qint8_arm64.onnx",
        vocabFile = "vocab.txt",
        sizeBytes = 135_336_307L,
        dimensions = 384
    )

    val ALL: List<EmbeddingModelSpec> = listOf(DISTILUSE)

    val DEFAULT: EmbeddingModelSpec = DISTILUSE
}
