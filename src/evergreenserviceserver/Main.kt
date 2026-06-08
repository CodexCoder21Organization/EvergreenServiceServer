package evergreenserviceserver

import foundation.url.protocol.ServiceHandler
import foundation.url.protocol.ServiceRegistrationConfig
import foundation.url.resolver.UrlProtocol2
import foundation.url.resolver.UrlResolver
import photogenerationmanager.embedded.EvergreenImageGenerationModel
import photogenerationmanager.embedded.EvergreenPromptGenerationModel

/**
 * EvergreenServiceServer — a single process that exposes two `url://` endpoints backed by a local
 * Evergreen Generator server:
 *
 *   - `url://<IMAGE_SERVICE_DOMAIN>/`  — an `ImageGenerationModel`  (default `evergreen-image-model`)
 *   - `url://<PROMPT_SERVICE_DOMAIN>/` — a `PromptGenerationModel`  (default `evergreen-prompt-model`)
 *
 * Run this on a machine with LAN access to the Evergreen server. It joins the global P2P network
 * via the default ContainerNursery relay, so a public consumer (e.g. PhotoGenerationManagerWui on
 * ContainerNursery) can reach these models by URL even though this node is behind NAT.
 *
 * Environment variables:
 *   EVERGREEN_SERVER_URL  Base URL of the Evergreen server (default https://192.168.86.243:9443)
 *   IMAGE_MODEL_URL       Evergreen image model (default the gemfuse image agent)
 *   PROMPT_MODEL_URL      Evergreen text model (default the lyria_rewriter prompt-rewriter)
 *   IMAGE_SERVICE_DOMAIN  url:// domain for the image model (default evergreen-image-model)
 *   PROMPT_SERVICE_DOMAIN url:// domain for the prompt model (default evergreen-prompt-model)
 */
fun main() {
    println("=== EvergreenServiceServer ===")

    val config = resolveServerConfig(System::getenv)
    val imageDomain = config.imageDomain
    val promptDomain = config.promptDomain
    println("Backing Evergreen server: ${config.evergreenUrl}")
    println("  image model: ${config.imageModelUrl}  ->  url://$imageDomain/")
    println("  prompt model: ${config.promptModelUrl}  ->  url://$promptDomain/")

    val imageModel = EvergreenImageGenerationModel(serverBaseUrl = config.evergreenUrl, modelUrl = config.imageModelUrl)
    val promptModel = EvergreenPromptGenerationModel(serverBaseUrl = config.evergreenUrl, modelUrl = config.promptModelUrl)

    val stdlibJar = loadServerResource("/stdlib.jar")
    val imageClientJar = loadServerResource("/image-client-impl.jar")
    val promptClientJar = loadServerResource("/prompt-client-impl.jar")
    println("Loaded SJVM stdlib (${stdlibJar.size} b), image client (${imageClientJar.size} b), prompt client (${promptClientJar.size} b)")

    val imageHandler = ImageModelRpcHandler(imageModel, imageClientJar, IMAGE_CLIENT_CLASS, stdlibJar)
    val promptHandler = PromptModelRpcHandler(promptModel, promptClientJar, PROMPT_CLIENT_CLASS, stdlibJar)

    val resolver = UrlResolver(UrlProtocol2())

    val imageReg = resolver.registerGlobalService(
        serviceUrl = "url://$imageDomain/",
        handler = serviceHandler(imageClientJar, IMAGE_CLIENT_CLASS) { path, params -> imageHandler.handleP2pRequest(path, params) },
        config = ServiceRegistrationConfig(
            metadata = mapOf("description" to "Evergreen image generation model", "type" to "rpc"),
            reannounceIntervalMs = 5 * 60 * 1000
        )
    )
    val promptReg = resolver.registerGlobalService(
        serviceUrl = "url://$promptDomain/",
        handler = serviceHandler(promptClientJar, PROMPT_CLIENT_CLASS) { path, params -> promptHandler.handleP2pRequest(path, params) },
        config = ServiceRegistrationConfig(
            metadata = mapOf("description" to "Evergreen prompt generation model", "type" to "rpc"),
            reannounceIntervalMs = 5 * 60 * 1000
        )
    )

    println()
    println("Registered services:")
    println("  url://$imageDomain/   peerId=${imageReg.peerId}")
    println("  url://$promptDomain/  peerId=${promptReg.peerId}")
    println("Press Ctrl+C to stop.")

    Runtime.getRuntime().addShutdownHook(Thread {
        println("\n[EvergreenServiceServer] Shutting down...")
        try { imageReg.unregister() } catch (_: Exception) {}
        try { promptReg.unregister() } catch (_: Exception) {}
        resolver.close()
    })

    Thread.currentThread().join()
}

private const val IMAGE_CLIENT_CLASS = "evergreenserviceserver/ImageGenerationModelClientImpl"
private const val PROMPT_CLIENT_CLASS = "evergreenserviceserver/PromptGenerationModelClientImpl"

private fun serviceHandler(
    jarBytes: ByteArray,
    implClassName: String,
    dispatch: (String, Map<String, Any?>) -> Any?
): ServiceHandler = object : ServiceHandler {
    override suspend fun handleRequest(path: String, params: Map<String, Any?>, metadata: Map<String, String>): Any? {
        println("[EvergreenServiceServer] request: path='$path'")
        return dispatch(path, params)
    }

    override fun getImplementationJar(): ByteArray = jarBytes
    override fun getImplementationClassName(): String = implClassName
    override fun onShutdown() {}
}
