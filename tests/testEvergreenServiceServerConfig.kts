@file:WithArtifact("evergreenserviceserver.buildMaven()")
@file:WithArtifact("photogenerationmanager.api:photo-generation-manager-api:0.0.4")
@file:WithArtifact("photogenerationmanager.embedded:photo-generation-manager-embedded:0.0.7")
@file:WithArtifact("com.squareup.okhttp3:okhttp:4.11.0")
@file:WithArtifact("com.squareup.okio:okio-jvm:3.4.0")
@file:WithArtifact("community.kotlin.clocks.simple:community-kotlin-clocks-simple:0.0.3")
@file:WithArtifact("foundation.url:protocol:0.0.275")
@file:WithArtifact("org.json:json:20250517")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22")
@file:WithArtifact("junit:junit:4.13.2")
package evergreenserviceserver

import build.kotlin.withartifact.WithArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import photogenerationmanager.embedded.EvergreenImageGenerationModel
import photogenerationmanager.embedded.EvergreenPhotoGenerationService

/**
 * Tests the documented public configuration API — [resolveServerConfig] and [loadServerResource] —
 * that `main` uses to turn the documented environment variables into an [EvergreenServerConfig] and
 * to load the bundled SJVM/client jars. The environment lookup is injected, so these are hermetic
 * (no real process environment is read or mutated).
 */

/** With no environment set, every field falls back to its documented default. */
@Test
fun testResolveServerConfigUsesDefaultsWhenEnvUnset() {
    val config = resolveServerConfig { null }
    assertEquals(EvergreenPhotoGenerationService.DEFAULT_SERVER_BASE_URL, config.evergreenUrl)
    assertEquals(EvergreenImageGenerationModel.DEFAULT_IMAGE_MODEL_URL, config.imageModelUrl)
    assertEquals("evergreen:///mbns/el/home/courier/lyria_rewriter/v4p1s_whitewater", config.promptModelUrl)
    assertEquals(DEFAULT_PROMPT_MODEL_URL, config.promptModelUrl)
    assertEquals("evergreen-image-model", config.imageDomain)
    assertEquals("evergreen-prompt-model", config.promptDomain)
}

/** Every environment variable, when set, overrides its corresponding default. */
@Test
fun testResolveServerConfigAppliesOverrides() {
    val env = mapOf(
        "EVERGREEN_SERVER_URL" to "https://example.test:9443",
        "IMAGE_MODEL_URL" to "evergreen:///img-override",
        "PROMPT_MODEL_URL" to "evergreen:///prompt-override",
        "IMAGE_SERVICE_DOMAIN" to "my-image-domain",
        "PROMPT_SERVICE_DOMAIN" to "my-prompt-domain"
    )
    val config = resolveServerConfig { env[it] }
    assertEquals("https://example.test:9443", config.evergreenUrl)
    assertEquals("evergreen:///img-override", config.imageModelUrl)
    assertEquals("evergreen:///prompt-override", config.promptModelUrl)
    assertEquals("my-image-domain", config.imageDomain)
    assertEquals("my-prompt-domain", config.promptDomain)
}

/** A blank (whitespace-only) value is treated as unset and falls back to the default. */
@Test
fun testResolveServerConfigTreatsBlankEnvAsUnset() {
    val config = resolveServerConfig { "   " }
    assertEquals(EvergreenPhotoGenerationService.DEFAULT_SERVER_BASE_URL, config.evergreenUrl)
    assertEquals(EvergreenImageGenerationModel.DEFAULT_IMAGE_MODEL_URL, config.imageModelUrl)
    assertEquals("evergreen-image-model", config.imageDomain)
    assertEquals("evergreen-prompt-model", config.promptDomain)
}

/** A missing resource fails with a descriptive message naming the resource and how to provide it. */
@Test
fun testLoadServerResourceMissingThrowsDescriptiveError() {
    val e = try {
        loadServerResource("/definitely-not-present-evergreen-xyz.jar")
        null
    } catch (ex: IllegalStateException) {
        ex
    }
    assertNotNull("a missing resource must throw IllegalStateException", e)
    assertEquals(
        "Cannot find resource /definitely-not-present-evergreen-xyz.jar. For /stdlib.jar ensure " +
            "net.javadeploy.sjvm:avianStdlibHelper-jvm is on the classpath; the client jars are bundled by buildFatJar.",
        e!!.message
    )
}
