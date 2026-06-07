package evergreenserviceserver

import foundation.url.protocol.Libp2pRpcProtocol
import photogenerationmanager.api.ImageGenerationModel
import java.util.Base64

/**
 * Routes `url://<image-domain>/` RPC calls to a backing [ImageGenerationModel] (the Embedded).
 *
 * RPC methods:
 *   generateImage      -> {imageHex, contentType, url}
 *   health             -> {status: "OK"}
 *   __bytecode_request -> {jar, className, stdlibJar} for SJVM client execution
 *
 * Image bytes are carried as a hex string (`imageHex`); input images arrive as comma-separated hex
 * in the `images` parameter — see [ModelRpcSupport].
 */
class ImageModelRpcHandler(
    private val model: ImageGenerationModel,
    private val jarBytes: ByteArray,
    private val implClassName: String,
    private val stdlibJarBytes: ByteArray
) {

    fun handleRequest(request: Libp2pRpcProtocol.RpcRequest): Libp2pRpcProtocol.RpcResponse {
        return try {
            Libp2pRpcProtocol.RpcResponse.success(request.id, dispatch(request.method, request.params))
        } catch (e: Exception) {
            System.err.println("[EvergreenServiceServer/image] Error handling '${request.method}': ${e.message}")
            e.printStackTrace()
            Libp2pRpcProtocol.RpcResponse.error(request.id, "-1", e.message ?: "Unknown error")
        }
    }

    fun handleP2pRequest(path: String, params: Map<String, Any?>): Any? = dispatch(path, params)

    fun dispatch(method: String, params: Map<String, Any?>): Any? {
        return when (method) {
            "health" -> mapOf("status" to "OK")

            "__bytecode_request" -> mapOf(
                "jar" to Base64.getEncoder().encodeToString(jarBytes),
                "className" to implClassName,
                "stdlibJar" to Base64.getEncoder().encodeToString(stdlibJarBytes)
            )

            "generateImage" -> {
                val prompt = requireParam(params, "prompt")
                val inputImages = parseHexImageList(params["images"])
                val image = model.generateImage(prompt, inputImages)
                mapOf(
                    "imageHex" to bytesToHex(image.imageBytes),
                    "contentType" to image.contentType,
                    "url" to image.url
                )
            }

            else -> mapOf(
                "service" to "ImageGenerationModel",
                "type" to "rpc",
                "availableMethods" to listOf(
                    "generateImage(prompt, images?): returns {imageHex, contentType, url}",
                    "health(): returns {status: \"OK\"}"
                )
            )
        }
    }
}
