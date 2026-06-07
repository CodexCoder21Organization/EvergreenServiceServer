package evergreenserviceserver

/**
 * Shared server-side RPC helpers for the model handlers. Image payloads cross the JSON / SJVM
 * sandbox boundary as hex strings (the sandbox lacks `java.util.Base64`).
 */

internal fun requireParam(params: Map<String, Any?>, name: String): String =
    params[name]?.toString()
        ?: throw IllegalArgumentException(
            "Missing required parameter '$name'. Provided parameters: ${params.keys}"
        )

/**
 * Parses the `images` parameter — a comma-separated list of hex-encoded image payloads — into raw
 * byte arrays. Accepts a String (SJVM client) or a List.
 */
internal fun parseHexImageList(value: Any?): List<ByteArray> {
    val hexes: List<String> = when (value) {
        null -> emptyList()
        is List<*> -> value.mapNotNull { it?.toString() }
        is String -> if (value.isEmpty()) emptyList() else value.split(",")
        else -> emptyList()
    }
    return hexes.map { it.trim() }.filter { it.isNotEmpty() }.map { hexToBytes(it) }
}

internal fun bytesToHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4])
        sb.append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have an even length, but had ${hex.length}" }
    val out = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
        out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        i += 2
    }
    return out
}
