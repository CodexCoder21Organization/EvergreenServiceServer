package evergreenserviceserver

import foundation.url.protocol.Libp2pRpcProtocol
import photogenerationmanager.api.PromptGenerationModel
import java.util.Base64

/**
 * Routes `url://<prompt-domain>/` RPC calls to a backing [PromptGenerationModel] (the Embedded).
 *
 * RPC methods:
 *   generatePrompt     -> {prompt: <new prompt text>}
 *   health             -> {status: "OK"}
 *   __bytecode_request -> {jar, className, stdlibJar} for SJVM client execution
 *
 * Input images arrive as comma-separated hex in the `images` parameter — see [ModelRpcSupport].
 */
class PromptModelRpcHandler(
    private val model: PromptGenerationModel,
    private val jarBytes: ByteArray,
    private val implClassName: String,
    private val stdlibJarBytes: ByteArray
) {

    fun handleRequest(request: Libp2pRpcProtocol.RpcRequest): Libp2pRpcProtocol.RpcResponse {
        return try {
            Libp2pRpcProtocol.RpcResponse.success(request.id, dispatch(request.method, request.params))
        } catch (e: Exception) {
            System.err.println("[EvergreenServiceServer/prompt] Error handling '${request.method}': ${e.message}")
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

            "generatePrompt" -> {
                val prompt = requireParam(params, "prompt")
                val inputImages = parseHexImageList(params["images"])
                mapOf("prompt" to model.generatePrompt(prompt, inputImages))
            }

            else -> mapOf(
                "service" to "PromptGenerationModel",
                "type" to "rpc",
                "availableMethods" to listOf(
                    "generatePrompt(prompt, images?): returns {prompt: <new prompt>}",
                    "health(): returns {status: \"OK\"}"
                )
            )
        }
    }
}
