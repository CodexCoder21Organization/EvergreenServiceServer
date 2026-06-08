package evergreenserviceserver

import foundation.url.sjvm.intrinsics.ServiceBridge
import photogenerationmanager.api.GenerationState
import photogenerationmanager.api.PromptGenerationStatus

/** Concrete [PromptGenerationStatus] for use inside the SJVM sandbox. */
class PromptGenerationStatusImpl(
    override val state: GenerationState,
    override val prompt: String?,
    override val error: String?
) : PromptGenerationStatus

/**
 * Client-side implementation of the async `PromptGenerationModel`, run inside the SJVM sandbox.
 * Every call is a fast [ServiceBridge.rpc].
 */
class PromptGenerationModelClientImpl {

    fun requestPromptGeneration(prompt: String, inputImages: List<ByteArray>): String {
        val params = mutableMapOf<String, Any?>("prompt" to prompt)
        if (inputImages.isNotEmpty()) params["images"] = inputImages.joinToString(",") { bytesToHex(it) }
        return ServiceBridge.rpc("requestPromptGeneration", params)["generationId"]?.toString() ?: ""
    }

    fun requestPromptGeneration(prompt: String): String = requestPromptGeneration(prompt, emptyList())

    fun promptGenerationStatus(generationId: String): PromptGenerationStatus {
        val r = ServiceBridge.rpc("promptGenerationStatus", mapOf("generationId" to generationId))
        return when (r["state"]?.toString()) {
            "DONE" -> PromptGenerationStatusImpl(GenerationState.DONE, r["prompt"]?.toString() ?: "", null)
            "ERROR" -> PromptGenerationStatusImpl(GenerationState.ERROR, null, r["error"]?.toString() ?: "generation failed")
            else -> PromptGenerationStatusImpl(GenerationState.PENDING, null, null)
        }
    }

    fun cancelPromptGeneration(generationId: String): Boolean {
        val r = ServiceBridge.rpc("cancelPromptGeneration", mapOf("generationId" to generationId))
        return r["cancelled"]?.toString()?.toBoolean() ?: false
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) { val v = b.toInt() and 0xFF; sb.append(digits[v ushr 4]); sb.append(digits[v and 0x0F]) }
        return sb.toString()
    }
}
