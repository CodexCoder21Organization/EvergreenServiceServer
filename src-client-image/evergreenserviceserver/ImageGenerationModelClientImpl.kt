package evergreenserviceserver

import foundation.url.sjvm.intrinsics.ServiceBridge
import photogenerationmanager.api.GeneratedImage
import photogenerationmanager.api.GenerationState
import photogenerationmanager.api.ImageGenerationStatus

/** Concrete [GeneratedImage] for use inside the SJVM sandbox (image bytes decoded from hex). */
class GeneratedImageImpl(
    override val imageBytes: ByteArray,
    override val contentType: String,
    override val url: String
) : GeneratedImage

/** Concrete [ImageGenerationStatus] for use inside the SJVM sandbox. */
class ImageGenerationStatusImpl(
    override val state: GenerationState,
    override val image: GeneratedImage?,
    override val error: String?
) : ImageGenerationStatus

/**
 * Client-side implementation of the async `ImageGenerationModel`, run inside the SJVM sandbox.
 *
 * Method signatures match [photogenerationmanager.api.ImageGenerationModel]; the host-side proxy
 * generator bridges the interface to this class by duck typing. Every call is a fast
 * [ServiceBridge.rpc]; the image bytes cross only on the DONE status (as a hex string, decoded here
 * with pure Kotlin).
 */
class ImageGenerationModelClientImpl {

    fun requestImageGeneration(prompt: String, inputImages: List<ByteArray>): String {
        val params = mutableMapOf<String, Any?>("prompt" to prompt)
        if (inputImages.isNotEmpty()) params["images"] = inputImages.joinToString(",") { bytesToHex(it) }
        return ServiceBridge.rpc("requestImageGeneration", params)["generationId"]?.toString() ?: ""
    }

    fun requestImageGeneration(prompt: String): String = requestImageGeneration(prompt, emptyList())

    fun imageGenerationStatus(generationId: String): ImageGenerationStatus {
        val r = ServiceBridge.rpc("imageGenerationStatus", mapOf("generationId" to generationId))
        return when (r["state"]?.toString()) {
            "DONE" -> {
                val hex = r["imageHex"]?.toString() ?: ""
                val image = GeneratedImageImpl(
                    if (hex.isEmpty()) ByteArray(0) else hexToBytes(hex),
                    r["contentType"]?.toString() ?: "application/octet-stream",
                    r["url"]?.toString() ?: ""
                )
                ImageGenerationStatusImpl(GenerationState.DONE, image, null)
            }
            "ERROR" -> ImageGenerationStatusImpl(GenerationState.ERROR, null, r["error"]?.toString() ?: "generation failed")
            else -> ImageGenerationStatusImpl(GenerationState.PENDING, null, null)
        }
    }

    fun cancelImageGeneration(generationId: String): Boolean {
        val r = ServiceBridge.rpc("cancelImageGeneration", mapOf("generationId" to generationId))
        return r["cancelled"]?.toString()?.toBoolean() ?: false
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) { val v = b.toInt() and 0xFF; sb.append(digits[v ushr 4]); sb.append(digits[v and 0x0F]) }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) { out[i / 2] = ((hexDigit(hex[i]) shl 4) + hexDigit(hex[i + 1])).toByte(); i += 2 }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("invalid hex digit: $c")
    }
}
