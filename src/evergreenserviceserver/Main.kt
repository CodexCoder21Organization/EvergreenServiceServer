package evergreenserviceserver

import foundation.url.protocol.ServiceHandler
import foundation.url.protocol.ServiceRegistrationConfig
import foundation.url.resolver.UrlProtocol2
import foundation.url.resolver.UrlResolver
import photogenerationmanager.embedded.EvergreenImageGenerationModel
import photogenerationmanager.embedded.EvergreenPhotoGenerationService
import photogenerationmanager.embedded.EvergreenPromptGenerationModel
import java.io.File

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
 *   PROMPT_MODEL_URL      Evergreen text model (default the rakicevic agent)
 *   IMAGE_SERVICE_DOMAIN  url:// domain for the image model (default evergreen-image-model)
 *   PROMPT_SERVICE_DOMAIN url:// domain for the prompt model (default evergreen-prompt-model)
 */
fun main() {
    println("=== EvergreenServiceServer ===")

    val evergreenUrl = env("EVERGREEN_SERVER_URL", EvergreenPhotoGenerationService.DEFAULT_SERVER_BASE_URL)
    val imageModelUrl = env("IMAGE_MODEL_URL", EvergreenImageGenerationModel.DEFAULT_IMAGE_MODEL_URL)
    val promptModelUrl = env("PROMPT_MODEL_URL", DEFAULT_PROMPT_MODEL_URL)
    val imageDomain = env("IMAGE_SERVICE_DOMAIN", "evergreen-image-model")
    val promptDomain = env("PROMPT_SERVICE_DOMAIN", "evergreen-prompt-model")
    println("Backing Evergreen server: $evergreenUrl")
    println("  image model: $imageModelUrl  ->  url://$imageDomain/")
    println("  prompt model: $promptModelUrl  ->  url://$promptDomain/")

    val imageModel = EvergreenImageGenerationModel(serverBaseUrl = evergreenUrl, modelUrl = imageModelUrl)
    val promptModel = EvergreenPromptGenerationModel(serverBaseUrl = evergreenUrl, modelUrl = promptModelUrl)

    val stdlibJar = loadResource("/stdlib.jar")
    val imageClientJar = loadResource("/image-client-impl.jar")
    val promptClientJar = loadResource("/prompt-client-impl.jar")
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
private const val DEFAULT_PROMPT_MODEL_URL = "evergreen:///mbns/vz/home/courier/rakicevic/gemfuse_image_agent"

private fun env(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

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

private fun loadResource(name: String): ByteArray {
    val stream = object {}.javaClass.getResourceAsStream(name)
    if (stream != null) return stream.use { it.readBytes() }
    val local = File(name.removePrefix("/"))
    if (local.exists()) return local.readBytes()
    throw IllegalStateException(
        "Cannot find resource $name. For /stdlib.jar ensure net.javadeploy.sjvm:avianStdlibHelper-jvm " +
            "is on the classpath; the client jars are bundled by buildFatJar."
    )
}
