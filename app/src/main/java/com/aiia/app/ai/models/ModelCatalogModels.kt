package com.aiia.app.ai.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HuggingFaceModel(
    val id: String,
    val author: String? = null,
    @SerialName("pipeline_tag") val pipelineTag: String? = null,
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList()
)

enum class ModelArtifactKind { MODEL, MMPROJ, UNKNOWN }

data class ModelArtifact(
    val repository: String,
    val filename: String,
    val kind: ModelArtifactKind,
    val quantization: String?,
    val sizeBytes: Long,
    val downloadUrl: String,
    val sha256: String? = null
) {
    val isGguf: Boolean get() = filename.endsWith(".gguf", ignoreCase = true)
    val isVisionProjector: Boolean get() = kind == ModelArtifactKind.MMPROJ
    val displaySize: String
        get() = when {
            sizeBytes >= 1_073_741_824 -> "%.2f GB".format(sizeBytes / 1_073_741_824.0)
            sizeBytes >= 1_048_576 -> "%.1f MB".format(sizeBytes / 1_048_576.0)
            else -> "$sizeBytes B"
        }
}

data class CatalogModel(
    val model: HuggingFaceModel,
    val artifacts: List<ModelArtifact>
) {
    val id: String get() = model.id
    val ggufFiles: List<ModelArtifact> get() = artifacts.filter { it.kind == ModelArtifactKind.MODEL }
    val projectors: List<ModelArtifact> get() = artifacts.filter { it.isVisionProjector }
}

enum class Quantization(val label: String) {
    Q4_K_M("Q4_K_M"), Q5_K_M("Q5_K_M"), Q8_0("Q8_0"), OTHER("Other");

    companion object {
        fun fromFileName(name: String): Quantization {
            val lower = name.lowercase()
            return when {
                "q4_k_m" in lower -> Q4_K_M
                "q5_k_m" in lower -> Q5_K_M
                "q8_0" in lower -> Q8_0
                else -> OTHER
            }
        }
    }
}
