package evergreenserviceserver

import foundation.url.sjvm.intrinsics.ServiceBridge

/**
 * Client-side implementation of `PromptGenerationModel` that runs inside the SJVM sandbox.
 *
 * Method signatures match [photogenerationmanager.api.PromptGenerationModel]; the host-side proxy
 * generator bridges the interface to this class by duck typing. All communication goes through
 * [ServiceBridge.rpc], which the host routes to [PromptModelRpcHandler]. Input images cross as hex
 * strings.
 */
class PromptGenerationModelClientImpl {

    fun generatePrompt(prompt: String, inputImages: List<ByteArray>): String {
        val params = mutableMapOf<String, Any?>("prompt" to prompt)
        if (inputImages.isNotEmpty()) {
            params["images"] = inputImages.joinToString(",") { bytesToHex(it) }
        }
        val result = ServiceBridge.rpc("generatePrompt", params)
        return result["prompt"]?.toString() ?: ""
    }

    fun generatePrompt(prompt: String): String = generatePrompt(prompt, emptyList())

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
}
