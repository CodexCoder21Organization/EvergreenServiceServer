@file:WithArtifact("evergreenserviceserver.buildMaven()")
@file:WithArtifact("photogenerationmanager.api:photo-generation-manager-api:0.0.3")
@file:WithArtifact("photogenerationmanager.embedded:photo-generation-manager-embedded:0.0.6")
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
import photogenerationmanager.embedded.EvergreenImageGenerationModel
import photogenerationmanager.embedded.EvergreenPromptGenerationModel
import photogenerationmanager.api.GeneratedImage
import photogenerationmanager.api.GenerationState
import photogenerationmanager.api.ImageGenerationModel
import photogenerationmanager.api.ImageGenerationStatus
import photogenerationmanager.api.PromptGenerationModel
import photogenerationmanager.api.PromptGenerationStatus
import foundation.url.protocol.Libp2pRpcProtocol
import java.util.Base64
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Date
import javax.security.auth.x500.X500Principal

private fun withFakeEvergreen(contentType: String, bytes: ByteArray, block: (baseUrl: String) -> Unit) {
    fun selfSignedKeyStore(password: CharArray): KeyStore {
        val kpg = KeyPairGenerator.getInstance("RSA"); kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val dn = X500Principal("CN=localhost")
        val builder = JcaX509v3CertificateBuilder(dn, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 365L * 86_400_000L), dn, kp.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        val ks = KeyStore.getInstance("PKCS12"); ks.load(null, password); ks.setKeyEntry("server", kp.private, password, arrayOf(cert)); return ks
    }
    val password = "changeit".toCharArray()
    val sslContextFactory = SslContextFactory.Server()
    sslContextFactory.keyStore = selfSignedKeyStore(password); sslContextFactory.setKeyStorePassword(String(password))
    val server = Server()
    val httpsConfig = HttpConfiguration(); val customizer = SecureRequestCustomizer(); customizer.isSniHostCheck = false; httpsConfig.addCustomizer(customizer)
    val connector = ServerConnector(server, SslConnectionFactory(sslContextFactory, "http/1.1"), HttpConnectionFactory(httpsConfig)); connector.port = 0; server.addConnector(connector)
    server.handler = object : AbstractHandler() {
        override fun handle(target: String, baseRequest: org.eclipse.jetty.server.Request, request: jakarta.servlet.http.HttpServletRequest, response: jakarta.servlet.http.HttpServletResponse) {
            request.inputStream.readBytes()
            if (target == "/generate" && request.method == "POST") { response.status = 200; response.contentType = contentType; response.outputStream.write(bytes) }
            else { response.status = 200; response.contentType = "text/html"; response.writer.write("<html>Evergreen</html>") }
            baseRequest.isHandled = true
        }
    }
    server.start()
    val port = (server.connectors.first() as ServerConnector).localPort
    try { block("https://localhost:$port") } finally { server.stop() }
}

/**
 * Verifies the image handler: requestImageGeneration -> generationId -> poll imageGenerationStatus
 * -> DONE with a hex image that decodes to a metadata-augmented JPEG.
 */
@Test
fun testImageRequestAndStatusReturnsHexImage() {
    fun realJpeg(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", baos)
        return baos.toByteArray()
    }
    fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2); var i = 0
        while (i < hex.length) { out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte(); i += 2 }
        return out
    }
    @Suppress("UNCHECKED_CAST")
    fun awaitStatus(handler: ImageModelRpcHandler, id: String): Map<String, Any> {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val s = handler.dispatch("imageGenerationStatus", mapOf("generationId" to id)) as Map<String, Any>
            if (s["state"] != "PENDING") return s
            Thread.sleep(25)
        }
        throw AssertionError("status never left PENDING")
    }
    val jpeg = realJpeg()
    withFakeEvergreen("image/png", jpeg) { baseUrl ->
        EvergreenImageGenerationModel(serverBaseUrl = baseUrl, modelUrl = "evergreen:///img").use { model ->
            val handler = ImageModelRpcHandler(model, ByteArray(0), "c", ByteArray(0))
            @Suppress("UNCHECKED_CAST")
            val req = handler.dispatch("requestImageGeneration", mapOf("prompt" to "a cat")) as Map<String, Any>
            val status = awaitStatus(handler, req["generationId"] as String)
            assertEquals("DONE", status["state"])
            val decoded = hexToBytes(status["imageHex"] as String)
            assertTrue("decoded is a JPEG", (decoded[0].toInt() and 0xFF) == 0xFF && (decoded[1].toInt() and 0xFF) == 0xD8)
            assertTrue("decoded is augmented (larger)", decoded.size > jpeg.size)
            assertTrue("metadata carries the prompt", decoded.toString(Charsets.ISO_8859_1).contains("<pgm:prompt>a cat</pgm:prompt>"))
        }
    }
}

/**
 * Verifies a hex-encoded input image is forwarded and its SHA-256 lands in the result's metadata.
 */
@Test
fun testImageStatusForwardsInputImageSha() {
    fun realJpeg(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", baos)
        return baos.toByteArray()
    }
    fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2); var i = 0
        while (i < hex.length) { out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte(); i += 2 }
        return out
    }
    val input = byteArrayOf(3, 1, 4, 1, 5, 9)
    val inputHex = input.joinToString("") { "%02x".format(it) }
    val inputSha = MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }
    withFakeEvergreen("image/png", realJpeg()) { baseUrl ->
        EvergreenImageGenerationModel(serverBaseUrl = baseUrl, modelUrl = "evergreen:///img").use { model ->
            val handler = ImageModelRpcHandler(model, ByteArray(0), "c", ByteArray(0))
            @Suppress("UNCHECKED_CAST")
            val req = handler.dispatch("requestImageGeneration", mapOf("prompt" to "x", "images" to inputHex)) as Map<String, Any>
            val id = req["generationId"] as String
            val deadline = System.currentTimeMillis() + 15_000
            var status: Map<String, Any>? = null
            while (System.currentTimeMillis() < deadline) {
                @Suppress("UNCHECKED_CAST")
                val s = handler.dispatch("imageGenerationStatus", mapOf("generationId" to id)) as Map<String, Any>
                if (s["state"] != "PENDING") { status = s; break }; Thread.sleep(25)
            }
            assertEquals("DONE", status!!["state"])
            assertTrue("metadata includes the input image SHA-256", hexToBytes(status["imageHex"] as String).toString(Charsets.ISO_8859_1).contains(inputSha))
        }
    }
}

/**
 * Verifies the prompt handler returns the model's text via request -> poll status.
 */
@Test
fun testPromptRequestAndStatusReturnsText() {
    val newPrompt = "an improved, richer prompt"
    withFakeEvergreen("text/plain", newPrompt.toByteArray()) { baseUrl ->
        EvergreenPromptGenerationModel(serverBaseUrl = baseUrl, modelUrl = "evergreen:///txt").use { model ->
            val handler = PromptModelRpcHandler(model, ByteArray(0), "c", ByteArray(0))
            @Suppress("UNCHECKED_CAST")
            val req = handler.dispatch("requestPromptGeneration", mapOf("prompt" to "a cat")) as Map<String, Any>
            val id = req["generationId"] as String
            val deadline = System.currentTimeMillis() + 15_000
            var status: Map<String, Any>? = null
            while (System.currentTimeMillis() < deadline) {
                @Suppress("UNCHECKED_CAST")
                val s = handler.dispatch("promptGenerationStatus", mapOf("generationId" to id)) as Map<String, Any>
                if (s["state"] != "PENDING") { status = s; break }; Thread.sleep(25)
            }
            assertEquals("DONE", status!!["state"])
            assertEquals(newPrompt, status["prompt"])
        }
    }
}

/**
 * Verifies the prompt handler surfaces an ERROR status when the model returns an image.
 */
@Test
fun testPromptStatusErrorWhenImage() {
    withFakeEvergreen("image/png", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())) { baseUrl ->
        EvergreenPromptGenerationModel(serverBaseUrl = baseUrl, modelUrl = "evergreen:///txt").use { model ->
            val handler = PromptModelRpcHandler(model, ByteArray(0), "c", ByteArray(0))
            @Suppress("UNCHECKED_CAST")
            val req = handler.dispatch("requestPromptGeneration", mapOf("prompt" to "a cat")) as Map<String, Any>
            val id = req["generationId"] as String
            val deadline = System.currentTimeMillis() + 15_000
            var status: Map<String, Any>? = null
            while (System.currentTimeMillis() < deadline) {
                @Suppress("UNCHECKED_CAST")
                val s = handler.dispatch("promptGenerationStatus", mapOf("generationId" to id)) as Map<String, Any>
                if (s["state"] != "PENDING") { status = s; break }; Thread.sleep(25)
            }
            assertEquals("ERROR", status!!["state"])
            assertTrue((status["error"] as String).contains("must return text"))
        }
    }
}

/**
 * Verifies both handlers' health dispatch returns {status: "OK"}.
 */
@Test
fun testHandlersHealth() {
    withFakeEvergreen("image/png", byteArrayOf(1, 2, 3)) { baseUrl ->
        EvergreenImageGenerationModel(serverBaseUrl = baseUrl, modelUrl = "e:///i").use { image ->
            EvergreenPromptGenerationModel(serverBaseUrl = baseUrl, modelUrl = "e:///t").use { prompt ->
                val ih = ImageModelRpcHandler(image, ByteArray(0), "c", ByteArray(0))
                val ph = PromptModelRpcHandler(prompt, ByteArray(0), "c", ByteArray(0))
                @Suppress("UNCHECKED_CAST")
                assertEquals("OK", (ih.dispatch("health", emptyMap()) as Map<String, Any>)["status"])
                @Suppress("UNCHECKED_CAST")
                assertEquals("OK", (ph.dispatch("health", emptyMap()) as Map<String, Any>)["status"])
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Coverage for the handler paths the original suite skipped: image ERROR/PENDING
// status maps, the missing-parameter error text, the RpcRequest success/error
// wrapping, the unknown-method service descriptor, the __bytecode_request branch,
// and the hex / parseHexImageList edge cases — all over each handler's public API,
// against simple in-memory models (no Evergreen, no relay).
//
// NOTE: kompile test-script top-level functions must not declare a return type and
// must use a block body. The helpers below are therefore Unit-returning orchestrators
// that build the handler and hand the result (a handler, or a dispatch map) to a
// lambda, mirroring the lambda style used by the end-to-end suite.
// ---------------------------------------------------------------------------

/**
 * Builds an image handler over an in-memory model whose `imageGenerationStatus` always returns the
 * given (state, image, error), then hands the `imageGenerationStatus` result map to [assertions].
 */
private fun assertImageStatusMap(
    statusState: GenerationState,
    image: GeneratedImage?,
    errorMessage: String?,
    assertions: (Map<String, Any?>) -> Unit
) {
    val model = object : ImageGenerationModel {
        override fun requestImageGeneration(prompt: String, inputImages: List<ByteArray>): String {
            return "image-gen-id"
        }

        override fun imageGenerationStatus(generationId: String): ImageGenerationStatus {
            return object : ImageGenerationStatus {
                override val state = statusState
                override val image = image
                override val error = errorMessage
            }
        }
    }
    val handler = ImageModelRpcHandler(model, ByteArray(0), "c", ByteArray(0))
    @Suppress("UNCHECKED_CAST")
    val m = handler.dispatch("imageGenerationStatus", mapOf("generationId" to "id")) as Map<String, Any?>
    assertions(m)
}

/** Prompt-side counterpart of [assertImageStatusMap]. */
private fun assertPromptStatusMap(
    statusState: GenerationState,
    prompt: String?,
    errorMessage: String?,
    assertions: (Map<String, Any?>) -> Unit
) {
    val model = object : PromptGenerationModel {
        override fun requestPromptGeneration(prompt: String, inputImages: List<ByteArray>): String {
            return "prompt-gen-id"
        }

        override fun promptGenerationStatus(generationId: String): PromptGenerationStatus {
            return object : PromptGenerationStatus {
                override val state = statusState
                override val prompt = prompt
                override val error = errorMessage
            }
        }
    }
    val handler = PromptModelRpcHandler(model, ByteArray(0), "c", ByteArray(0))
    @Suppress("UNCHECKED_CAST")
    val m = handler.dispatch("promptGenerationStatus", mapOf("generationId" to "id")) as Map<String, Any?>
    assertions(m)
}

/**
 * Builds an image handler (over an always-PENDING in-memory model) with the given bytecode-serving
 * fields, optionally recording each request's input images into [capture], and hands it to [body].
 */
private fun withImageHandler(
    jar: ByteArray,
    className: String,
    stdlib: ByteArray,
    capture: Array<List<ByteArray>?>?,
    body: (ImageModelRpcHandler) -> Unit
) {
    val model = object : ImageGenerationModel {
        override fun requestImageGeneration(prompt: String, inputImages: List<ByteArray>): String {
            if (capture != null) capture[0] = inputImages
            return "image-gen-id"
        }

        override fun imageGenerationStatus(generationId: String): ImageGenerationStatus {
            return object : ImageGenerationStatus {
                override val state = GenerationState.PENDING
                override val image: GeneratedImage? = null
                override val error: String? = null
            }
        }
    }
    body(ImageModelRpcHandler(model, jar, className, stdlib))
}

/** Prompt-side counterpart of [withImageHandler]. */
private fun withPromptHandler(
    jar: ByteArray,
    className: String,
    stdlib: ByteArray,
    body: (PromptModelRpcHandler) -> Unit
) {
    val model = object : PromptGenerationModel {
        override fun requestPromptGeneration(prompt: String, inputImages: List<ByteArray>): String {
            return "prompt-gen-id"
        }

        override fun promptGenerationStatus(generationId: String): PromptGenerationStatus {
            return object : PromptGenerationStatus {
                override val state = GenerationState.PENDING
                override val prompt: String? = null
                override val error: String? = null
            }
        }
    }
    body(PromptModelRpcHandler(model, jar, className, stdlib))
}

// ---- ERROR / PENDING / DONE status maps -------------------------------------

/** An ERROR image status surfaces {state: ERROR, error: <message>} with no image hex. */
@Test
fun testImageStatusErrorStateReturnsErrorMap() {
    assertImageStatusMap(GenerationState.ERROR, null, "the evergreen box is down") { m ->
        assertEquals("ERROR", m["state"])
        assertEquals("the evergreen box is down", m["error"])
        assertNull("an ERROR status must not carry an image hex", m["imageHex"])
    }
}

/** A null model error message defaults to the descriptive "generation failed". */
@Test
fun testImageStatusErrorDefaultsMessageWhenNull() {
    assertImageStatusMap(GenerationState.ERROR, null, null) { m ->
        assertEquals("ERROR", m["state"])
        assertEquals("generation failed", m["error"])
    }
}

/** A PENDING image status is a bare {state: PENDING} — no error, no image hex. */
@Test
fun testImageStatusPendingReturnsBareStateMap() {
    assertImageStatusMap(GenerationState.PENDING, null, null) { m ->
        assertEquals("PENDING", m["state"])
        assertNull(m["error"])
        assertNull(m["imageHex"])
    }
}

/** A DONE image status carries the image hex, content type and url (in-memory, no Evergreen). */
@Test
fun testImageStatusDoneCarriesHexContentTypeAndUrl() {
    val bytes = ByteArray(64) { (it * 7 + 1).toByte() }
    val image = object : GeneratedImage {
        override val imageBytes = bytes
        override val contentType = "image/png"
        override val url = "url://img/done"
    }
    assertImageStatusMap(GenerationState.DONE, image, null) { m ->
        assertEquals("DONE", m["state"])
        assertEquals("image/png", m["contentType"])
        assertEquals("url://img/done", m["url"])
        assertEquals(bytes.joinToString("") { "%02x".format(it) }, m["imageHex"])
    }
}

@Test
fun testPromptStatusErrorDefaultsMessageWhenNull() {
    assertPromptStatusMap(GenerationState.ERROR, null, null) { m ->
        assertEquals("ERROR", m["state"])
        assertEquals("generation failed", m["error"])
    }
}

@Test
fun testPromptStatusPendingReturnsBareStateMap() {
    assertPromptStatusMap(GenerationState.PENDING, null, null) { m ->
        assertEquals("PENDING", m["state"])
        assertNull(m["error"])
        assertNull(m["prompt"])
    }
}

@Test
fun testPromptStatusDoneReturnsPromptText() {
    assertPromptStatusMap(GenerationState.DONE, "a richer prompt", null) { m ->
        assertEquals("DONE", m["state"])
        assertEquals("a richer prompt", m["prompt"])
    }
}

// ---- Missing-parameter error text -------------------------------------------

/** requestImageGeneration without a prompt throws a descriptive IllegalArgumentException. */
@Test
fun testRequestImageGenerationMissingPromptThrowsDescriptiveError() {
    withImageHandler(ByteArray(0), "c", ByteArray(0), null) { handler ->
        val e = try {
            handler.dispatch("requestImageGeneration", mapOf("images" to ""))
            null
        } catch (ex: IllegalArgumentException) {
            ex
        }
        assertNotNull("a missing 'prompt' must throw IllegalArgumentException", e)
        assertEquals("Missing required parameter 'prompt'. Provided parameters: [images]", e!!.message)
    }
}

@Test
fun testRequestPromptGenerationMissingPromptThrowsDescriptiveError() {
    withPromptHandler(ByteArray(0), "c", ByteArray(0)) { handler ->
        val e = try {
            handler.dispatch("requestPromptGeneration", emptyMap())
            null
        } catch (ex: IllegalArgumentException) {
            ex
        }
        assertNotNull("a missing 'prompt' must throw IllegalArgumentException", e)
        assertEquals("Missing required parameter 'prompt'. Provided parameters: []", e!!.message)
    }
}

/** imageGenerationStatus without a generationId throws a descriptive IllegalArgumentException. */
@Test
fun testImageStatusMissingGenerationIdThrowsDescriptiveError() {
    withImageHandler(ByteArray(0), "c", ByteArray(0), null) { handler ->
        val e = try {
            handler.dispatch("imageGenerationStatus", emptyMap())
            null
        } catch (ex: IllegalArgumentException) {
            ex
        }
        assertNotNull("a missing 'generationId' must throw IllegalArgumentException", e)
        assertEquals("Missing required parameter 'generationId'. Provided parameters: []", e!!.message)
    }
}

// ---- RpcRequest success / error wrapping ------------------------------------

/** handleRequest wraps a successful dispatch in an RpcResponse carrying the result and the request id. */
@Test
fun testImageHandleRequestWrapsResultInRpcResponse() {
    withImageHandler(ByteArray(0), "c", ByteArray(0), null) { handler ->
        val resp = handler.handleRequest(Libp2pRpcProtocol.RpcRequest("req-1", "health", emptyMap(), emptyMap()))
        assertEquals("req-1", resp.id)
        assertNull("a successful RPC must not carry an error", resp.error)
        @Suppress("UNCHECKED_CAST")
        val result = resp.result as Map<String, Any?>
        assertEquals("OK", result["status"])
    }
}

/** handleRequest catches a dispatch exception and wraps it as RpcResponse.error(code "-1", <message>). */
@Test
fun testImageHandleRequestWrapsExceptionInRpcError() {
    withImageHandler(ByteArray(0), "c", ByteArray(0), null) { handler ->
        val resp = handler.handleRequest(Libp2pRpcProtocol.RpcRequest("req-2", "requestImageGeneration", emptyMap(), emptyMap()))
        assertEquals("req-2", resp.id)
        assertNull("a failed RPC must not carry a result", resp.result)
        assertNotNull("a failed RPC must carry an error", resp.error)
        assertEquals("-1", resp.error!!.code)
        assertEquals("Missing required parameter 'prompt'. Provided parameters: []", resp.error!!.message)
    }
}

@Test
fun testPromptHandleRequestWrapsResultInRpcResponse() {
    withPromptHandler(ByteArray(0), "c", ByteArray(0)) { handler ->
        val resp = handler.handleRequest(Libp2pRpcProtocol.RpcRequest("p-1", "health", emptyMap(), emptyMap()))
        assertEquals("p-1", resp.id)
        assertNull(resp.error)
        @Suppress("UNCHECKED_CAST")
        val result = resp.result as Map<String, Any?>
        assertEquals("OK", result["status"])
    }
}

@Test
fun testPromptHandleRequestWrapsExceptionInRpcError() {
    withPromptHandler(ByteArray(0), "c", ByteArray(0)) { handler ->
        val resp = handler.handleRequest(Libp2pRpcProtocol.RpcRequest("p-2", "requestPromptGeneration", emptyMap(), emptyMap()))
        assertEquals("p-2", resp.id)
        assertNull(resp.result)
        assertNotNull(resp.error)
        assertEquals("-1", resp.error!!.code)
        assertEquals("Missing required parameter 'prompt'. Provided parameters: []", resp.error!!.message)
    }
}

// ---- Unknown-method service descriptor --------------------------------------

@Test
fun testImageUnknownMethodReturnsServiceDescriptor() {
    withImageHandler(ByteArray(0), "c", ByteArray(0), null) { handler ->
        @Suppress("UNCHECKED_CAST")
        val m = handler.dispatch("totallyUnknownMethod", emptyMap()) as Map<String, Any?>
        assertEquals("ImageGenerationModel", m["service"])
        assertEquals("rpc", m["type"])
        val methods = m["availableMethods"] as List<*>
        assertTrue("descriptor lists requestImageGeneration", methods.any { it.toString().contains("requestImageGeneration") })
        assertTrue("descriptor lists imageGenerationStatus", methods.any { it.toString().contains("imageGenerationStatus") })
    }
}

@Test
fun testPromptUnknownMethodReturnsServiceDescriptor() {
    withPromptHandler(ByteArray(0), "c", ByteArray(0)) { handler ->
        @Suppress("UNCHECKED_CAST")
        val m = handler.dispatch("totallyUnknownMethod", emptyMap()) as Map<String, Any?>
        assertEquals("PromptGenerationModel", m["service"])
        assertEquals("rpc", m["type"])
        val methods = m["availableMethods"] as List<*>
        assertTrue("descriptor lists requestPromptGeneration", methods.any { it.toString().contains("requestPromptGeneration") })
        assertTrue("descriptor lists promptGenerationStatus", methods.any { it.toString().contains("promptGenerationStatus") })
    }
}

// ---- __bytecode_request branch ----------------------------------------------

@Test
fun testImageBytecodeRequestReturnsBase64JarAndClassName() {
    val jar = byteArrayOf(1, 2, 3, 4, 5)
    val stdlib = byteArrayOf(9, 8, 7)
    withImageHandler(jar, "evergreenserviceserver/ImageGenerationModelClientImpl", stdlib, null) { handler ->
        @Suppress("UNCHECKED_CAST")
        val m = handler.dispatch("__bytecode_request", emptyMap()) as Map<String, Any?>
        assertEquals("evergreenserviceserver/ImageGenerationModelClientImpl", m["className"])
        assertArrayEquals("served jar must base64-decode to the exact bytes", jar, Base64.getDecoder().decode(m["jar"] as String))
        assertArrayEquals("served stdlib must base64-decode to the exact bytes", stdlib, Base64.getDecoder().decode(m["stdlibJar"] as String))
    }
}

@Test
fun testPromptBytecodeRequestReturnsBase64JarAndClassName() {
    val jar = byteArrayOf(10, 20, 30)
    val stdlib = byteArrayOf(40, 50)
    withPromptHandler(jar, "evergreenserviceserver/PromptGenerationModelClientImpl", stdlib) { handler ->
        @Suppress("UNCHECKED_CAST")
        val m = handler.dispatch("__bytecode_request", emptyMap()) as Map<String, Any?>
        assertEquals("evergreenserviceserver/PromptGenerationModelClientImpl", m["className"])
        assertArrayEquals(jar, Base64.getDecoder().decode(m["jar"] as String))
        assertArrayEquals(stdlib, Base64.getDecoder().decode(m["stdlibJar"] as String))
    }
}

// ---- hex / parseHexImageList edge cases (via requestImageGeneration) ---------

/** Multiple input images arrive as comma-separated hex and decode to the exact bytes, in order. */
@Test
fun testMultipleInputImagesParsedFromCommaSeparatedHex() {
    val captured = arrayOfNulls<List<ByteArray>>(1)
    withImageHandler(ByteArray(0), "c", ByteArray(0), captured) { handler ->
        fun hex(x: ByteArray): String {
            return x.joinToString("") { "%02x".format(it) }
        }
        val a = byteArrayOf(0x00, 0x7f, 0xff.toByte())
        val b = byteArrayOf(0x10, 0x20)
        handler.dispatch("requestImageGeneration", mapOf("prompt" to "p", "images" to "${hex(a)},${hex(b)}"))
        assertNotNull(captured[0])
        assertEquals(2, captured[0]!!.size)
        assertArrayEquals(a, captured[0]!![0])
        assertArrayEquals(b, captured[0]!![1])
    }
}

/** The images parameter may also arrive as a List of hex strings (not only a comma-joined String). */
@Test
fun testInputImagesAcceptListParam() {
    val captured = arrayOfNulls<List<ByteArray>>(1)
    withImageHandler(ByteArray(0), "c", ByteArray(0), captured) { handler ->
        fun hex(x: ByteArray): String {
            return x.joinToString("") { "%02x".format(it) }
        }
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(5, 6)
        handler.dispatch("requestImageGeneration", mapOf("prompt" to "p", "images" to listOf(hex(a), hex(b))))
        assertEquals(2, captured[0]!!.size)
        assertArrayEquals(a, captured[0]!![0])
        assertArrayEquals(b, captured[0]!![1])
    }
}

/** An empty images string yields no input images (not a single empty one). */
@Test
fun testEmptyImagesStringYieldsNoInputs() {
    val captured = arrayOfNulls<List<ByteArray>>(1)
    withImageHandler(ByteArray(0), "c", ByteArray(0), captured) { handler ->
        handler.dispatch("requestImageGeneration", mapOf("prompt" to "p", "images" to ""))
        assertEquals(0, captured[0]!!.size)
    }
}

/** A missing images parameter yields no input images. */
@Test
fun testMissingImagesParamYieldsNoInputs() {
    val captured = arrayOfNulls<List<ByteArray>>(1)
    withImageHandler(ByteArray(0), "c", ByteArray(0), captured) { handler ->
        handler.dispatch("requestImageGeneration", mapOf("prompt" to "p"))
        assertEquals(0, captured[0]!!.size)
    }
}

/** An images parameter that is neither a String nor a List (here an Int) yields no input images. */
@Test
fun testNonStringNonListImagesParamYieldsNoInputs() {
    val captured = arrayOfNulls<List<ByteArray>>(1)
    withImageHandler(ByteArray(0), "c", ByteArray(0), captured) { handler ->
        handler.dispatch("requestImageGeneration", mapOf("prompt" to "p", "images" to 12345))
        assertEquals(0, captured[0]!!.size)
    }
}

/** Odd-length hex is rejected with a descriptive IllegalArgumentException naming the bad length. */
@Test
fun testOddLengthHexThrowsDescriptiveError() {
    withImageHandler(ByteArray(0), "c", ByteArray(0), null) { handler ->
        val e = try {
            handler.dispatch("requestImageGeneration", mapOf("prompt" to "p", "images" to "abc"))
            null
        } catch (ex: IllegalArgumentException) {
            ex
        }
        assertNotNull("odd-length hex must be rejected", e)
        assertEquals("hex string must have an even length, but had 3", e!!.message)
    }
}
