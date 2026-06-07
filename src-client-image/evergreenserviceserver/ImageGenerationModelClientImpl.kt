package evergreenserviceserver

import foundation.url.sjvm.intrinsics.ServiceBridge

/**
 * Concrete [photogenerationmanager.api.GeneratedImage] for use inside the SJVM sandbox, built from
 * an RPC response map (image bytes decoded from hex).
 */
class GeneratedImageImpl(
    override val imageBytes: ByteArray,
    override val contentType: String,
    override val url: String
) : photogenerationmanager.api.GeneratedImage

/**
 * Client-side implementation of `ImageGenerationModel` that runs inside the SJVM sandbox.
 *
 * Method signatures match [photogenerationmanager.api.ImageGenerationModel]; the host-side proxy
 * generator bridges the interface to this class by duck typing, so this class does not declare that
 * it implements the interface. All communication goes through [ServiceBridge.rpc], which the host
 * routes to [ImageModelRpcHandler]. Image bytes cross as hex strings (decoded here with pure
 * Kotlin, so no `java.util.Base64` is required in the sandbox).
 */
class ImageGenerationModelClientImpl {

    fun generateImage(prompt: String, inputImages: List<ByteArray>): photogenerationmanager.api.GeneratedImage {
        val params = mutableMapOf<String, Any?>("prompt" to prompt)
        if (inputImages.isNotEmpty()) {
            params["images"] = inputImages.joinToString(",") { bytesToHex(it) }
        }
        val result = ServiceBridge.rpc("generateImage", params)
        val imageHex = result["imageHex"]?.toString() ?: ""
        return GeneratedImageImpl(
            imageBytes = if (imageHex.isEmpty()) ByteArray(0) else hexToBytes(imageHex),
            contentType = result["contentType"]?.toString() ?: "application/octet-stream",
            url = result["url"]?.toString() ?: ""
        )
    }

    fun generateImage(prompt: String): photogenerationmanager.api.GeneratedImage =
        generateImage(prompt, emptyList())

    private fun bytesToHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v ushr 4])
            sb.append(digits[v and 0x0F])
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            out[i / 2] = ((hexDigit(hex[i]) shl 4) + hexDigit(hex[i + 1])).toByte()
            i += 2
        }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("invalid hex digit: $c")
    }
}
