package evergreenserviceserver

import foundation.url.protocol.Libp2pRpcProtocol
import photogenerationmanager.api.GenerationState
import photogenerationmanager.api.ImageGenerationModel
import photogenerationmanager.api.ImageGenerationStatus
import java.util.Base64

/**
 * Routes `url://<image-domain>/` RPC calls to a backing async [ImageGenerationModel] (the Embedded).
 *
 * RPC methods (all fast, so they work over a relay):
 *   requestImageGeneration -> {generationId}
 *   imageGenerationStatus  -> {state, [imageHex, contentType, url] | [error]}
 *   cancelImageGeneration  -> {cancelled: true|false}
 *   health                 -> {status: "OK"}
 *   __bytecode_request     -> {jar, className, stdlibJar}
 *
 * The image bytes only travel on the DONE status (as a hex string); input images arrive as
 * comma-separated hex in the `images` parameter — see [ModelRpcSupport].
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

            "requestImageGeneration" -> {
                val prompt = requireParam(params, "prompt")
                val inputImages = parseHexImageList(params["images"])
                mapOf("generationId" to model.requestImageGeneration(prompt, inputImages))
            }

            "imageGenerationStatus" -> statusToMap(model.imageGenerationStatus(requireParam(params, "generationId")))

            "cancelImageGeneration" -> mapOf("cancelled" to model.cancelImageGeneration(requireParam(params, "generationId")))

            else -> mapOf(
                "service" to "ImageGenerationModel",
                "type" to "rpc",
                "availableMethods" to listOf(
                    "requestImageGeneration(prompt, images?): returns {generationId}",
                    "imageGenerationStatus(generationId): returns {state, imageHex|error}",
                    "cancelImageGeneration(generationId): returns {cancelled}",
                    "health(): returns {status: \"OK\"}"
                )
            )
        }
    }

    private fun statusToMap(s: ImageGenerationStatus): Map<String, Any> = when (s.state) {
        GenerationState.DONE -> {
            val image = s.image!!
            mapOf("state" to "DONE", "imageHex" to bytesToHex(image.imageBytes), "contentType" to image.contentType, "url" to image.url)
        }
        GenerationState.ERROR -> mapOf("state" to "ERROR", "error" to (s.error ?: "generation failed"))
        GenerationState.PENDING -> mapOf("state" to "PENDING")
    }
}
