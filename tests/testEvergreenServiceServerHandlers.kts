@file:WithArtifact("evergreenserviceserver.buildMaven()")
@file:WithArtifact("photogenerationmanager.api:photo-generation-manager-api:0.0.2")
@file:WithArtifact("photogenerationmanager.embedded:photo-generation-manager-embedded:0.0.3")
@file:WithArtifact("com.squareup.okhttp3:okhttp:4.11.0")
@file:WithArtifact("com.squareup.okio:okio-jvm:3.4.0")
@file:WithArtifact("community.kotlin.clocks.simple:community-kotlin-clocks-simple:0.0.3")
@file:WithArtifact("foundation.url:protocol:0.0.275")
@file:WithArtifact("org.json:json:20250517")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22")
@file:WithArtifact("junit:junit:4.13.2")
@file:WithArtifact("org.eclipse.jetty:jetty-server:11.0.15")
@file:WithArtifact("org.eclipse.jetty:jetty-util:11.0.15")
@file:WithArtifact("org.eclipse.jetty:jetty-http:11.0.15")
@file:WithArtifact("org.eclipse.jetty:jetty-io:11.0.15")
@file:WithArtifact("jakarta.servlet:jakarta.servlet-api:5.0.0")
@file:WithArtifact("org.bouncycastle:bcpkix-jdk18on:1.78.1")
@file:WithArtifact("org.bouncycastle:bcprov-jdk18on:1.78.1")
@file:WithArtifact("org.bouncycastle:bcutil-jdk18on:1.78.1")
package evergreenserviceserver

import build.kotlin.withartifact.WithArtifact
import org.junit.Assert.*
import org.junit.Test
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.SecureRequestCustomizer
import org.eclipse.jetty.server.SslConnectionFactory
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.util.ssl.SslContextFactory
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import photogenerationmanager.api.PhotoGenerationException
import photogenerationmanager.embedded.EvergreenImageGenerationModel
import photogenerationmanager.embedded.EvergreenPromptGenerationModel
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Date
import javax.security.auth.x500.X500Principal

private fun withFakeEvergreen(contentType: String, bytes: ByteArray, block: (baseUrl: String) -> Unit) {
    fun selfSignedKeyStore(password: CharArray): KeyStore {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val dn = X500Principal("CN=localhost")
        val builder = JcaX509v3CertificateBuilder(
            dn, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 365L * 86_400_000L), dn, kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, password)
        ks.setKeyEntry("server", kp.private, password, arrayOf(cert))
        return ks
    }

    val password = "changeit".toCharArray()
    val sslContextFactory = SslContextFactory.Server()
    sslContextFactory.keyStore = selfSignedKeyStore(password)
    sslContextFactory.setKeyStorePassword(String(password))

    val server = Server()
    val httpsConfig = HttpConfiguration()
    val customizer = SecureRequestCustomizer()
    customizer.isSniHostCheck = false
    httpsConfig.addCustomizer(customizer)
    val connector = ServerConnector(server, SslConnectionFactory(sslContextFactory, "http/1.1"), HttpConnectionFactory(httpsConfig))
    connector.port = 0
    server.addConnector(connector)

    server.handler = object : AbstractHandler() {
        override fun handle(
            target: String,
            baseRequest: org.eclipse.jetty.server.Request,
            request: jakarta.servlet.http.HttpServletRequest,
            response: jakarta.servlet.http.HttpServletResponse
        ) {
            request.inputStream.readBytes()
            if (target == "/generate" && request.method == "POST") {
                response.status = 200
                response.contentType = contentType
                response.outputStream.write(bytes)
            } else {
                response.status = 200
                response.contentType = "text/html"
                response.writer.write("<html>Evergreen</html>")
            }
            baseRequest.isHandled = true
        }
    }
    server.start()
    val port = (server.connectors.first() as ServerConnector).localPort
    try {
        block("https://localhost:$port")
    } finally {
        server.stop()
    }
}

/**
 * Verifies the image handler delegates to the model and returns the image as hex that decodes to a
 * valid (metadata-augmented) JPEG, with its content type and url.
 */
@Test
fun testImageHandlerGenerateReturnsHexImage() {
    fun realJpeg(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", baos)
        return baos.toByteArray()
    }
    fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) { out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte(); i += 2 }
        return out
    }
    val jpeg = realJpeg()
    withFakeEvergreen("image/png", jpeg) { baseUrl ->
        val model = EvergreenImageGenerationModel(serverBaseUrl = baseUrl, modelUrl = "evergreen:///img")
        val handler = ImageModelRpcHandler(model, ByteArray(0), "c", ByteArray(0))
        @Suppress("UNCHECKED_CAST")
        val result = handler.dispatch("generateImage", mapOf("prompt" to "a cat")) as Map<String, Any>
        val decoded = hexToBytes(result["imageHex"] as String)
        assertTrue("decoded should be a JPEG", (decoded[0].toInt() and 0xFF) == 0xFF && (decoded[1].toInt() and 0xFF) == 0xD8)
        assertTrue("decoded should be augmented (larger)", decoded.size > jpeg.size)
        assertEquals("image/png", result["contentType"])
        assertTrue((result["url"] as String).startsWith("url://photo-generation-manager/image"))
        assertTrue("metadata should carry the prompt", decoded.toString(Charsets.ISO_8859_1).contains("<pgm:prompt>a cat</pgm:prompt>"))
    }
}

/**
 * Verifies a hex-encoded input image is decoded, forwarded, and its SHA-256 lands in the result
 * image's metadata.
 */
@Test
fun testImageHandlerForwardsInputImageSha256() {
    fun realJpeg(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", baos)
        return baos.toByteArray()
    }
    fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) { out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte(); i += 2 }
        return out
    }
    val input = byteArrayOf(3, 1, 4, 1, 5, 9)
    val inputHex = input.joinToString("") { "%02x".format(it) }
    val inputSha = MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }
    withFakeEvergreen("image/png", realJpeg()) { baseUrl ->
        val model = EvergreenImageGenerationModel(serverBaseUrl = baseUrl, modelUrl = "evergreen:///img")
        val handler = ImageModelRpcHandler(model, ByteArray(0), "c", ByteArray(0))
        @Suppress("UNCHECKED_CAST")
        val result = handler.dispatch("generateImage", mapOf("prompt" to "x", "images" to inputHex)) as Map<String, Any>
        val xml = hexToBytes(result["imageHex"] as String).toString(Charsets.ISO_8859_1)
        assertTrue("metadata should include the forwarded input image SHA-256", xml.contains(inputSha))
    }
}

/**
 * Verifies the prompt handler returns the model's text under the "prompt" key.
 */
@Test
fun testPromptHandlerReturnsText() {
    val newPrompt = "an improved, richer prompt"
    withFakeEvergreen("text/plain", newPrompt.toByteArray()) { baseUrl ->
        val model = EvergreenPromptGenerationModel(serverBaseUrl = baseUrl, modelUrl = "evergreen:///txt")
        val handler = PromptModelRpcHandler(model, ByteArray(0), "c", ByteArray(0))
        @Suppress("UNCHECKED_CAST")
        val result = handler.dispatch("generatePrompt", mapOf("prompt" to "a cat")) as Map<String, Any>
        assertEquals(newPrompt, result["prompt"])
    }
}

/**
 * Verifies the prompt handler surfaces a PhotoGenerationException when the model returns an image.
 */
@Test
fun testPromptHandlerThrowsWhenModelReturnsImage() {
    withFakeEvergreen("image/png", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())) { baseUrl ->
        val model = EvergreenPromptGenerationModel(serverBaseUrl = baseUrl, modelUrl = "evergreen:///txt")
        val handler = PromptModelRpcHandler(model, ByteArray(0), "c", ByteArray(0))
        val ex = try {
            handler.dispatch("generatePrompt", mapOf("prompt" to "a cat"))
            null
        } catch (e: PhotoGenerationException) {
            e
        }
        assertNotNull("prompt handler should propagate the model's rejection", ex)
        assertTrue(ex!!.message!!.contains("must return text"))
    }
}

/**
 * Verifies both handlers' health dispatch returns {status: "OK"}.
 */
@Test
fun testHandlersHealth() {
    withFakeEvergreen("image/png", byteArrayOf(1, 2, 3)) { baseUrl ->
        val image = ImageModelRpcHandler(EvergreenImageGenerationModel(serverBaseUrl = baseUrl, modelUrl = "e:///i"), ByteArray(0), "c", ByteArray(0))
        val prompt = PromptModelRpcHandler(EvergreenPromptGenerationModel(serverBaseUrl = baseUrl, modelUrl = "e:///t"), ByteArray(0), "c", ByteArray(0))
        @Suppress("UNCHECKED_CAST")
        assertEquals("OK", (image.dispatch("health", emptyMap()) as Map<String, Any>)["status"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("OK", (prompt.dispatch("health", emptyMap()) as Map<String, Any>)["status"])
    }
}
