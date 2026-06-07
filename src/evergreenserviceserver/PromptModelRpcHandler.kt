package evergreenserviceserver

import foundation.url.protocol.Libp2pRpcProtocol
import photogenerationmanager.api.GenerationState
import photogenerationmanager.api.PromptGenerationModel
import photogenerationmanager.api.PromptGenerationStatus
import java.util.Base64

/**
 * Routes `url://<prompt-domain>/` RPC calls to a backing async [PromptGenerationModel] (the Embedded).
 *
 * RPC methods (all fast):
 *   requestPromptGeneration -> {generationId}
 *   promptGenerationStatus  -> {state, [prompt] | [error]}
 *   health                  -> {status: "OK"}
 *   __bytecode_request      -> {jar, className, stdlibJar}
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

            "requestPromptGeneration" -> {
                val prompt = requireParam(params, "prompt")
                val inputImages = parseHexImageList(params["images"])
                mapOf("generationId" to model.requestPromptGeneration(prompt, inputImages))
            }

            "promptGenerationStatus" -> statusToMap(model.promptGenerationStatus(requireParam(params, "generationId")))

            else -> mapOf(
                "service" to "PromptGenerationModel",
                "type" to "rpc",
                "availableMethods" to listOf(
                    "requestPromptGeneration(prompt, images?): returns {generationId}",
                    "promptGenerationStatus(generationId): returns {state, prompt|error}",
                    "health(): returns {status: \"OK\"}"
                )
            )
        }
    }

    private fun statusToMap(s: PromptGenerationStatus): Map<String, Any> = when (s.state) {
        GenerationState.DONE -> mapOf("state" to "DONE", "prompt" to (s.prompt ?: ""))
        GenerationState.ERROR -> mapOf("state" to "ERROR", "error" to (s.error ?: "generation failed"))
        GenerationState.PENDING -> mapOf("state" to "PENDING")
    }
}
