package evergreenserviceserver

import photogenerationmanager.embedded.EvergreenImageGenerationModel
import photogenerationmanager.embedded.EvergreenPhotoGenerationService
import java.io.File

/**
 * Default url:// of the Evergreen prompt (text) model, used when `PROMPT_MODEL_URL` is unset. This is
 * a prompt-rewriter model that returns text; `mbns`/`bns` addresses require three slashes
 * (`evergreen:///mbns/...`).
 */
const val DEFAULT_PROMPT_MODEL_URL = "evergreen:///mbns/el/home/courier/lyria_rewriter/v4p1s_whitewater"

/**
 * Immutable, resolved configuration for a single EvergreenServiceServer process: the backing
 * Evergreen server, the two Evergreen model urls, and the two `url://` domains the server registers.
 */
class EvergreenServerConfig(
    val evergreenUrl: String,
    val imageModelUrl: String,
    val promptModelUrl: String,
    val imageDomain: String,
    val promptDomain: String
)

/**
 * Resolves [EvergreenServerConfig] from the documented environment variables — `EVERGREEN_SERVER_URL`,
 * `IMAGE_MODEL_URL`, `PROMPT_MODEL_URL`, `IMAGE_SERVICE_DOMAIN`, `PROMPT_SERVICE_DOMAIN` — falling back
 * to the documented defaults when a variable is unset or blank. The environment lookup is injected via
 * [getenv] so production passes `System::getenv` while tests can supply a fixed map.
 */
fun resolveServerConfig(getenv: (String) -> String?): EvergreenServerConfig {
    fun env(name: String, default: String): String =
        getenv(name)?.takeIf { it.isNotBlank() } ?: default
    return EvergreenServerConfig(
        evergreenUrl = env("EVERGREEN_SERVER_URL", EvergreenPhotoGenerationService.DEFAULT_SERVER_BASE_URL),
        imageModelUrl = env("IMAGE_MODEL_URL", EvergreenImageGenerationModel.DEFAULT_IMAGE_MODEL_URL),
        promptModelUrl = env("PROMPT_MODEL_URL", DEFAULT_PROMPT_MODEL_URL),
        imageDomain = env("IMAGE_SERVICE_DOMAIN", "evergreen-image-model"),
        promptDomain = env("PROMPT_SERVICE_DOMAIN", "evergreen-prompt-model")
    )
}

/**
 * Loads a bundled resource by classpath [name] (e.g. `/stdlib.jar`), falling back to a same-named file
 * in the working directory. Fails with a descriptive [IllegalStateException] naming the missing
 * resource and how to provide it, so a mis-packaged fat jar is diagnosable from the message alone.
 */
fun loadServerResource(name: String): ByteArray {
    val stream = object {}.javaClass.getResourceAsStream(name)
    if (stream != null) return stream.use { it.readBytes() }
    val local = File(name.removePrefix("/"))
    if (local.exists()) return local.readBytes()
    throw IllegalStateException(
        "Cannot find resource $name. For /stdlib.jar ensure net.javadeploy.sjvm:avianStdlibHelper-jvm " +
            "is on the classpath; the client jars are bundled by buildFatJar."
    )
}
