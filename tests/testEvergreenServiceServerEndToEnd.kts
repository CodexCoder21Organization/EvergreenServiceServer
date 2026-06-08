@file:WithArtifact("evergreenserviceserver.buildMaven()")
@file:WithArtifact("evergreenserviceserver.buildImageClientResourcesJar()")
@file:WithArtifact("evergreenserviceserver.buildPromptClientResourcesJar()")
@file:WithArtifact("photogenerationmanager.api:photo-generation-manager-api:0.0.4")
@file:WithArtifact("community.kotlin.clocks.simple:community-kotlin-clocks-simple:0.0.3")
// UrlResolver + UrlProtocol — the exact version pair the server module is built against.
@file:WithArtifact("foundation.url:resolver:0.0.504")
@file:WithArtifact("foundation.url:protocol:0.0.275")
@file:WithArtifact("community.kotlin.observable:core-jvm:0.3.4")
// SJVM runtime + stdlib (the consumer runs the client bytecode in its own sandbox).
@file:WithArtifact("net.javadeploy.sjvm:libSJVM-jvm:0.0.38")
@file:WithArtifact("net.javadeploy.sjvm:avianStdlibHelper-jvm:0.0.38")
@file:WithArtifact("net.javadeploy.sjvm:stdlibHelperCommon-jvm:0.0.38")
@file:WithArtifact("org.ow2.asm:asm:9.6")
@file:WithArtifact("org.ow2.asm:asm-commons:9.6")
// libp2p stack
@file:WithArtifact("io.libp2p:jvm-libp2p:1.2.2-RELEASE")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-reflect:1.9.22")
@file:WithArtifact("community.kotlin.rpc:protocol-api:0.0.2")
@file:WithArtifact("community.kotlin.rpc:protocol-impl:0.0.11")
@file:WithArtifact("com.google.protobuf:protobuf-java:3.25.1")
@file:WithArtifact("tech.pegasys:noise-java:22.1.0")
@file:WithArtifact("org.json:json:20250517")
// Runtime deps of resolver/protocol not in the explicit list above (matched to the UrlResolver suite).
@file:WithArtifact("algebraic.effects.v1.runtime:algebraic-effects-v1-runtime:0.0.1")
@file:WithArtifact("w3wallet.api:w3wallet-api:0.0.1")
@file:WithArtifact("community.kotlin.logging:community-kotlin-logging:0.0.1")
@file:WithArtifact("util.stacktrace:util-stacktrace:0.0.1")
@file:WithArtifact("build.kotlin.annotations:build-kotlin-annotations:0.0.2")
// Kotlin stdlib + coroutines
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22")
@file:WithArtifact("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22")
@file:WithArtifact("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0")
@file:WithArtifact("com.squareup.okio:okio-jvm:3.4.0")
@file:WithArtifact("org.slf4j:slf4j-api:1.7.36")
@file:WithArtifact("org.slf4j:slf4j-simple:1.7.36")
// Netty (for libp2p)
@file:WithArtifact("io.netty:netty-buffer:4.1.101.Final")
@file:WithArtifact("io.netty:netty-codec:4.1.101.Final")
@file:WithArtifact("io.netty:netty-codec-http:4.1.101.Final")
@file:WithArtifact("io.netty:netty-codec-http2:4.1.101.Final")
@file:WithArtifact("io.netty:netty-common:4.1.101.Final")
@file:WithArtifact("io.netty:netty-handler:4.1.101.Final")
@file:WithArtifact("io.netty:netty-resolver:4.1.101.Final")
@file:WithArtifact("io.netty:netty-transport:4.1.101.Final")
@file:WithArtifact("io.netty:netty-transport-classes-epoll:4.1.101.Final")
@file:WithArtifact("io.netty:netty-transport-classes-kqueue:4.1.101.Final")
@file:WithArtifact("io.netty:netty-transport-native-unix-common:4.1.101.Final")
// BouncyCastle + Guava (required by libp2p)
@file:WithArtifact("org.bouncycastle:bcpkix-jdk18on:1.78.1")
@file:WithArtifact("org.bouncycastle:bcprov-jdk18on:1.78.1")
@file:WithArtifact("org.bouncycastle:bcutil-jdk18on:1.78.1")
@file:WithArtifact("com.google.guava:guava:33.2.0-jre")
@file:WithArtifact("com.google.guava:failureaccess:1.0.2")
@file:WithArtifact("junit:junit:4.13.2")
package evergreenserviceserver

import build.kotlin.withartifact.WithArtifact
import community.kotlin.clocks.simple.ManualClock
import foundation.url.protocol.BootstrapPeer
import foundation.url.protocol.Libp2pPeer
import foundation.url.protocol.PublicAddressDiscovery
import foundation.url.protocol.ServiceHandler
import foundation.url.resolver.UrlProtocol2
import foundation.url.resolver.UrlResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import photogenerationmanager.api.GeneratedImage
import photogenerationmanager.api.GenerationState
import photogenerationmanager.api.ImageGenerationModel
import photogenerationmanager.api.ImageGenerationStatus
import photogenerationmanager.api.PromptGenerationModel
import photogenerationmanager.api.PromptGenerationStatus

/**
 * True end-to-end integration tests for the full `url://` transport, with NO dependency on the real
 * Evergreen server and NO dependency on the public relay.
 *
 * Each test spins up two in-JVM [UrlProtocol2] nodes wired directly to each other over loopback:
 *
 *   server node  ── registerGlobalService(url://…/, handler over a SIMPLIFIED in-memory model)
 *        ▲                                       serves the REAL SJVM client bytecode
 *        │ direct libp2p (127.0.0.1 multiaddr bootstrap — no public relay)
 *   client node  ── UrlResolver.openSandboxedConnection(url://…/, Model::class)
 *                                       runs the client bytecode in an SJVM sandbox; every method is
 *                                       a real ServiceBridge.rpc back to the server handler.
 *
 * This exercises exactly the path the per-layer unit tests skip: openSandboxedConnection → SJVM
 * sandbox → real client bytecode → RPC → handler dispatch → model, the async request→poll flow, the
 * hex marshaling of input images (client→server), and — critically — a `ByteArray` (the generated
 * image) surviving the sandbox→host proxy return on a nested [GeneratedImage] (server→client).
 */

/**
 * Stands up the two-node loopback service, hands the client a typed proxy, and tears everything down.
 *
 * @param serviceId  the host portion of the service URL (also its discovery identifier).
 * @param clientJar  the real client implementation bytecode the server serves to the sandbox.
 * @param implClassName  the duck-typed client class the sandbox instantiates.
 * @param dispatch  routes an RPC (method, params) to the simplified in-memory model.
 * @param exercise  receives the connected [UrlResolver] and the service URL, and performs assertions.
 *
 * Note: the consumer runs the sandbox against its OWN bundled `/stdlib.jar` (from avianStdlibHelper
 * on the test classpath), so the server need not supply one.
 */
private fun runTwoNodeServiceTest(
    serviceId: String,
    clientJar: ByteArray,
    implClassName: String,
    dispatch: (String, Map<String, Any?>) -> Any?,
    exercise: (UrlResolver, String) -> Unit
) {
    val serviceUrl = "url://$serviceId/"
    val previousExplicitIp = PublicAddressDiscovery.getExplicitPublicIp()
    PublicAddressDiscovery.clear()
    PublicAddressDiscovery.setExplicitPublicIp("127.0.0.1")

    val handler = object : ServiceHandler {
        override suspend fun handleRequest(path: String, params: Map<String, Any?>, metadata: Map<String, String>): Any? =
            dispatch(path, params)

        override fun getImplementationJar(): ByteArray = clientJar
        override fun getImplementationClassName(): String = implClassName
        override fun onShutdown() {}
    }

    val serverProtocol = UrlProtocol2(emptyList<BootstrapPeer>(), eagerlyJoinNetwork = false, listenPort = 0)
    try {
        val serverJoinInfo = serverProtocol.joinNetwork(alias = "$serviceId-server")
        serverProtocol.registerGlobalService(serviceUrl = serviceUrl, handler = handler)

        // Wait until the server's own registry reflects the registration before the client connects.
        val regDeadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < regDeadline) {
            if (serverProtocol.getDiscoveredServices().any { it.serviceIdentifier == serviceId }) break
            Thread.sleep(25)
        }

        val serverPeer = Libp2pPeer.remote(
            peerId = serverJoinInfo.peerId,
            multiaddresses = serverJoinInfo.multiaddresses.map { it.replace(Regex("/ip4/[0-9.]+/"), "/ip4/127.0.0.1/") },
            advertisedServices = listOf(serviceId)
        )

        val clientProtocol = UrlProtocol2(listOf(serverPeer), eagerlyJoinNetwork = false, listenPort = 0)
        val client = UrlResolver(clientProtocol)
        try {
            clientProtocol.joinNetwork(alias = "$serviceId-client")
            val discoveryDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < discoveryDeadline) {
                if (clientProtocol.findPeersForService(serviceId).isNotEmpty()) break
                Thread.sleep(50)
            }
            assertTrue(
                "Client never discovered the loopback service '$serviceId' within 15s; the e2e path " +
                    "can't be exercised. Known client peers: ${clientProtocol.getKnownPeers().size}, " +
                    "server peerId=${serverJoinInfo.peerId}.",
                clientProtocol.findPeersForService(serviceId).isNotEmpty()
            )

            exercise(client, serviceUrl)
        } finally {
            try { client.close() } catch (_: Throwable) {}
            try { clientProtocol.close() } catch (_: Throwable) {}
        }
    } finally {
        try { serverProtocol.close() } catch (_: Throwable) {}
        PublicAddressDiscovery.clear()
        if (previousExplicitIp != null) PublicAddressDiscovery.setExplicitPublicIp(previousExplicitIp)
    }
}

/**
 * Full url:// image flow: a simplified in-memory [ImageGenerationModel] returns a fixed image. The
 * client connects over loopback, requests a generation (with an input image), polls to DONE, and the
 * generated bytes are asserted byte-for-byte after crossing the SJVM sandbox→host proxy as a nested
 * [GeneratedImage.imageBytes] `ByteArray`. The server side also asserts it received the exact prompt
 * and input image (hex marshaling client→server).
 */
@Test
fun testImageModelEndToEndOverUrl() {
    fun loadRes(name: String): ByteArray {
        val s = object {}.javaClass.getResourceAsStream(name)
            ?: throw IllegalStateException("resource $name not on the test classpath")
        return s.use { it.readBytes() }
    }
    val fixedImage = ByteArray(512) { (it * 31 + 7).toByte() }
    val inputImage = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
    val capturedPrompt = arrayOfNulls<String>(1)
    val capturedInputs = arrayOfNulls<List<ByteArray>>(1)

    val model = object : ImageGenerationModel {
        override fun requestImageGeneration(prompt: String, inputImages: List<ByteArray>): String {
            capturedPrompt[0] = prompt
            capturedInputs[0] = inputImages
            return "img-gen-1"
        }

        override fun imageGenerationStatus(generationId: String): ImageGenerationStatus =
            object : ImageGenerationStatus {
                override val state = GenerationState.DONE
                override val image: GeneratedImage = object : GeneratedImage {
                    override val imageBytes = fixedImage
                    override val contentType = "image/png"
                    override val url = "url://test-image/$generationId"
                }
                override val error: String? = null
            }
    }
    val handler = ImageModelRpcHandler(model, ByteArray(0), "x", ByteArray(0))

    runTwoNodeServiceTest(
        serviceId = "e2e.image.model.${System.nanoTime()}",
        clientJar = loadRes("/image-client-impl.jar"),
        implClassName = "evergreenserviceserver/ImageGenerationModelClientImpl",
        dispatch = { method, params -> handler.handleP2pRequest(method, params) },
        exercise = { client, url ->
            val proxy = client.openSandboxedConnection(url, ImageGenerationModel::class)
            val generationId = proxy.requestImageGeneration("a hermetic test prompt", listOf(inputImage))
            assertTrue("requestImageGeneration must return a non-empty id over url://", generationId.isNotEmpty())

            var status: ImageGenerationStatus? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val s = proxy.imageGenerationStatus(generationId)
                if (s.state != GenerationState.PENDING) { status = s; break }
                Thread.sleep(50)
            }
            assertTrue("image generation never left PENDING over url://", status != null)
            assertEquals(GenerationState.DONE, status!!.state)
            assertNull(status.error)
            val image = status.image
            assertTrue("DONE status must carry an image", image != null)
            assertEquals("image/png", image!!.contentType)
            // The critical assertion: the ByteArray survived the SJVM sandbox -> host proxy return.
            assertTrue(
                "generated image bytes must round-trip byte-for-byte through the url:// sandbox proxy",
                image.imageBytes.contentEquals(fixedImage)
            )

            // The server received exactly what the client sent (prompt + input-image hex marshaling).
            assertEquals("a hermetic test prompt", capturedPrompt[0])
            assertEquals(1, capturedInputs[0]!!.size)
            assertTrue("input image must round-trip client->server as hex", capturedInputs[0]!![0].contentEquals(inputImage))
        }
    )
}

/**
 * Full url:// prompt flow: a simplified in-memory [PromptGenerationModel] returns a fixed string. The
 * client connects over loopback, requests a generation, polls to DONE, and asserts the returned text.
 */
@Test
fun testPromptModelEndToEndOverUrl() {
    fun loadRes(name: String): ByteArray {
        val s = object {}.javaClass.getResourceAsStream(name)
            ?: throw IllegalStateException("resource $name not on the test classpath")
        return s.use { it.readBytes() }
    }
    val fixedPrompt = "a richer, hermetically-generated prompt"
    val capturedPrompt = arrayOfNulls<String>(1)

    val model = object : PromptGenerationModel {
        override fun requestPromptGeneration(prompt: String, inputImages: List<ByteArray>): String {
            capturedPrompt[0] = prompt
            return "prompt-gen-1"
        }

        override fun promptGenerationStatus(generationId: String): PromptGenerationStatus =
            object : PromptGenerationStatus {
                override val state = GenerationState.DONE
                override val prompt = fixedPrompt
                override val error: String? = null
            }
    }
    val handler = PromptModelRpcHandler(model, ByteArray(0), "x", ByteArray(0))

    runTwoNodeServiceTest(
        serviceId = "e2e.prompt.model.${System.nanoTime()}",
        clientJar = loadRes("/prompt-client-impl.jar"),
        implClassName = "evergreenserviceserver/PromptGenerationModelClientImpl",
        dispatch = { method, params -> handler.handleP2pRequest(method, params) },
        exercise = { client, url ->
            val proxy = client.openSandboxedConnection(url, PromptGenerationModel::class)
            val generationId = proxy.requestPromptGeneration("describe a cat")
            assertTrue("requestPromptGeneration must return a non-empty id over url://", generationId.isNotEmpty())

            var status: PromptGenerationStatus? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val s = proxy.promptGenerationStatus(generationId)
                if (s.state != GenerationState.PENDING) { status = s; break }
                Thread.sleep(50)
            }
            assertTrue("prompt generation never left PENDING over url://", status != null)
            assertEquals(GenerationState.DONE, status!!.state)
            assertNull(status.error)
            assertEquals(fixedPrompt, status.prompt)
            assertEquals("describe a cat", capturedPrompt[0])
        }
    )
}

/**
 * An ERROR generation status round-trips through the full url:// transport: the server-side model
 * reports [GenerationState.ERROR] with a message, and the client — running the real client bytecode
 * in the SJVM sandbox — decodes it back to an ERROR status carrying the same message and no image.
 * This exercises the client's ERROR branch and the server's ERROR status map, which the happy-path
 * test never reaches.
 */
@Test
fun testImageErrorStatusEndToEndOverUrl() {
    fun loadRes(name: String): ByteArray {
        val s = object {}.javaClass.getResourceAsStream(name)
            ?: throw IllegalStateException("resource $name not on the test classpath")
        return s.use { it.readBytes() }
    }
    val errorMessage = "the evergreen box is unreachable"
    val model = object : ImageGenerationModel {
        override fun requestImageGeneration(prompt: String, inputImages: List<ByteArray>): String {
            return "img-err-1"
        }

        override fun imageGenerationStatus(generationId: String): ImageGenerationStatus {
            return object : ImageGenerationStatus {
                override val state = GenerationState.ERROR
                override val image: GeneratedImage? = null
                override val error: String? = errorMessage
            }
        }
    }
    val handler = ImageModelRpcHandler(model, ByteArray(0), "x", ByteArray(0))

    runTwoNodeServiceTest(
        serviceId = "e2e.image.error.${System.nanoTime()}",
        clientJar = loadRes("/image-client-impl.jar"),
        implClassName = "evergreenserviceserver/ImageGenerationModelClientImpl",
        dispatch = { method, params -> handler.handleP2pRequest(method, params) },
        exercise = { client, url ->
            val proxy = client.openSandboxedConnection(url, ImageGenerationModel::class)
            val generationId = proxy.requestImageGeneration("trigger an error", emptyList())
            assertTrue("requestImageGeneration must return a non-empty id over url://", generationId.isNotEmpty())

            var status: ImageGenerationStatus? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val s = proxy.imageGenerationStatus(generationId)
                if (s.state != GenerationState.PENDING) { status = s; break }
                Thread.sleep(50)
            }
            assertTrue("image status never left PENDING over url://", status != null)
            assertEquals(GenerationState.ERROR, status!!.state)
            assertEquals("the ERROR message must round-trip server->client over url://", errorMessage, status.error)
            assertNull("an ERROR status must not carry an image", status.image)
        }
    )
}

/**
 * The async contract over url://: the server reports PENDING on the first status polls and only later
 * DONE. The sandboxed client must decode PENDING (and keep polling), then decode the eventual DONE with
 * the correct bytes — the PENDING->DONE transition the happy-path tests skip by returning DONE at once.
 * Also drives the single-argument `requestImageGeneration(prompt)` client overload.
 */
@Test
fun testImagePendingThenDoneEndToEndOverUrl() {
    fun loadRes(name: String): ByteArray {
        val s = object {}.javaClass.getResourceAsStream(name)
            ?: throw IllegalStateException("resource $name not on the test classpath")
        return s.use { it.readBytes() }
    }
    val fixedImage = ByteArray(128) { (it * 5 + 3).toByte() }
    val statusPolls = intArrayOf(0)
    val model = object : ImageGenerationModel {
        override fun requestImageGeneration(prompt: String, inputImages: List<ByteArray>): String {
            return "img-pending-1"
        }

        override fun imageGenerationStatus(generationId: String): ImageGenerationStatus {
            val n = statusPolls[0] + 1
            statusPolls[0] = n
            // Report PENDING for the first two polls, then DONE.
            if (n < 3) {
                return object : ImageGenerationStatus {
                    override val state = GenerationState.PENDING
                    override val image: GeneratedImage? = null
                    override val error: String? = null
                }
            }
            return object : ImageGenerationStatus {
                override val state = GenerationState.DONE
                override val image: GeneratedImage = object : GeneratedImage {
                    override val imageBytes = fixedImage
                    override val contentType = "image/png"
                    override val url = "url://pending/$generationId"
                }
                override val error: String? = null
            }
        }
    }
    val handler = ImageModelRpcHandler(model, ByteArray(0), "x", ByteArray(0))

    runTwoNodeServiceTest(
        serviceId = "e2e.image.pending.${System.nanoTime()}",
        clientJar = loadRes("/image-client-impl.jar"),
        implClassName = "evergreenserviceserver/ImageGenerationModelClientImpl",
        dispatch = { method, params -> handler.handleP2pRequest(method, params) },
        exercise = { client, url ->
            val proxy = client.openSandboxedConnection(url, ImageGenerationModel::class)
            val generationId = proxy.requestImageGeneration("a slowly generated image")
            assertTrue("requestImageGeneration must return a non-empty id over url://", generationId.isNotEmpty())

            var status: ImageGenerationStatus? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val s = proxy.imageGenerationStatus(generationId)
                if (s.state != GenerationState.PENDING) { status = s; break }
                Thread.sleep(50)
            }
            assertTrue("image status never left PENDING over url://", status != null)
            assertEquals(GenerationState.DONE, status!!.state)
            assertTrue(
                "the client must have observed PENDING and polled again before DONE (server saw " +
                    "${statusPolls[0]} status polls)",
                statusPolls[0] >= 3
            )
            assertTrue(
                "the eventual DONE image must round-trip byte-for-byte after the PENDING->DONE transition",
                status.image!!.imageBytes.contentEquals(fixedImage)
            )
        }
    )
}

/**
 * A server-side generation *failure* (the model throws, not a clean ERROR status) must surface to the
 * sandboxed client as a thrown exception over url:// — it must NOT hang and must NOT masquerade as a
 * successful generation id. This is the path with only manual coverage before.
 */
@Test
fun testImageGenerationFailureSurfacesAsThrowableOverUrl() {
    fun loadRes(name: String): ByteArray {
        val s = object {}.javaClass.getResourceAsStream(name)
            ?: throw IllegalStateException("resource $name not on the test classpath")
        return s.use { it.readBytes() }
    }
    val model = object : ImageGenerationModel {
        override fun requestImageGeneration(prompt: String, inputImages: List<ByteArray>): String {
            throw IllegalStateException("evergreen generation failed")
        }

        override fun imageGenerationStatus(generationId: String): ImageGenerationStatus {
            return object : ImageGenerationStatus {
                override val state = GenerationState.PENDING
                override val image: GeneratedImage? = null
                override val error: String? = null
            }
        }
    }
    val handler = ImageModelRpcHandler(model, ByteArray(0), "x", ByteArray(0))

    runTwoNodeServiceTest(
        serviceId = "e2e.image.fail.${System.nanoTime()}",
        clientJar = loadRes("/image-client-impl.jar"),
        implClassName = "evergreenserviceserver/ImageGenerationModelClientImpl",
        dispatch = { method, params -> handler.handleP2pRequest(method, params) },
        exercise = { client, url ->
            val proxy = client.openSandboxedConnection(url, ImageGenerationModel::class)
            val thrown = try {
                proxy.requestImageGeneration("boom", emptyList())
                null
            } catch (t: Throwable) {
                t
            }
            assertTrue(
                "a server-side generation failure must surface to the client as a thrown exception over " +
                    "url://, not return a fabricated id; instead nothing was thrown",
                thrown != null
            )
        }
    )
}

/**
 * The prompt path also marshals input images client->server (the happy-path prompt test sends none).
 * Two input images are sent through the typed proxy, joined to hex by the real client bytecode, split
 * and decoded server-side, and asserted byte-for-byte; the generated prompt text round-trips back.
 */
@Test
fun testPromptModelWithInputImagesEndToEndOverUrl() {
    fun loadRes(name: String): ByteArray {
        val s = object {}.javaClass.getResourceAsStream(name)
            ?: throw IllegalStateException("resource $name not on the test classpath")
        return s.use { it.readBytes() }
    }
    val fixedPrompt = "a refined prompt synthesised from two images"
    val img1 = byteArrayOf(1, 2, 3, 4, 5)
    val img2 = byteArrayOf(0x7f, 0x00, 0xff.toByte(), 0x10, 0x80.toByte())
    val capturedPrompt = arrayOfNulls<String>(1)
    val capturedInputs = arrayOfNulls<List<ByteArray>>(1)

    val model = object : PromptGenerationModel {
        override fun requestPromptGeneration(prompt: String, inputImages: List<ByteArray>): String {
            capturedPrompt[0] = prompt
            capturedInputs[0] = inputImages
            return "prompt-gen-imgs"
        }

        override fun promptGenerationStatus(generationId: String): PromptGenerationStatus {
            return object : PromptGenerationStatus {
                override val state = GenerationState.DONE
                override val prompt = fixedPrompt
                override val error: String? = null
            }
        }
    }
    val handler = PromptModelRpcHandler(model, ByteArray(0), "x", ByteArray(0))

    runTwoNodeServiceTest(
        serviceId = "e2e.prompt.images.${System.nanoTime()}",
        clientJar = loadRes("/prompt-client-impl.jar"),
        implClassName = "evergreenserviceserver/PromptGenerationModelClientImpl",
        dispatch = { method, params -> handler.handleP2pRequest(method, params) },
        exercise = { client, url ->
            val proxy = client.openSandboxedConnection(url, PromptGenerationModel::class)
            val generationId = proxy.requestPromptGeneration("describe these two images", listOf(img1, img2))
            assertTrue("requestPromptGeneration must return a non-empty id over url://", generationId.isNotEmpty())

            var status: PromptGenerationStatus? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val s = proxy.promptGenerationStatus(generationId)
                if (s.state != GenerationState.PENDING) { status = s; break }
                Thread.sleep(50)
            }
            assertTrue("prompt generation never left PENDING over url://", status != null)
            assertEquals(GenerationState.DONE, status!!.state)
            assertEquals(fixedPrompt, status.prompt)

            assertEquals("describe these two images", capturedPrompt[0])
            assertEquals("both input images must marshal client->server on the prompt path", 2, capturedInputs[0]!!.size)
            assertTrue("first input image must round-trip as hex", capturedInputs[0]!![0].contentEquals(img1))
            assertTrue("second input image must round-trip as hex", capturedInputs[0]!![1].contentEquals(img2))
        }
    )
}

/**
 * Stands up ONE server node that registers BOTH the image and the prompt service (as `main` does),
 * wires a single client to it over loopback, and verifies the two services coexist and route
 * independently: the image URL drives the image model and the prompt URL drives the prompt model.
 */
private fun runImageAndPromptNode(
    imageServiceId: String,
    promptServiceId: String,
    imageClientJar: ByteArray,
    promptClientJar: ByteArray,
    imageDispatch: (String, Map<String, Any?>) -> Any?,
    promptDispatch: (String, Map<String, Any?>) -> Any?,
    exercise: (UrlResolver, String, String) -> Unit
) {
    val previousExplicitIp = PublicAddressDiscovery.getExplicitPublicIp()
    PublicAddressDiscovery.clear()
    PublicAddressDiscovery.setExplicitPublicIp("127.0.0.1")

    val imageHandler = object : ServiceHandler {
        override suspend fun handleRequest(path: String, params: Map<String, Any?>, metadata: Map<String, String>): Any? =
            imageDispatch(path, params)

        override fun getImplementationJar(): ByteArray = imageClientJar
        override fun getImplementationClassName(): String = "evergreenserviceserver/ImageGenerationModelClientImpl"
        override fun onShutdown() {}
    }
    val promptHandler = object : ServiceHandler {
        override suspend fun handleRequest(path: String, params: Map<String, Any?>, metadata: Map<String, String>): Any? =
            promptDispatch(path, params)

        override fun getImplementationJar(): ByteArray = promptClientJar
        override fun getImplementationClassName(): String = "evergreenserviceserver/PromptGenerationModelClientImpl"
        override fun onShutdown() {}
    }

    val serverProtocol = UrlProtocol2(emptyList<BootstrapPeer>(), eagerlyJoinNetwork = false, listenPort = 0)
    try {
        val serverJoinInfo = serverProtocol.joinNetwork(alias = "multi-server")
        serverProtocol.registerGlobalService(serviceUrl = "url://$imageServiceId/", handler = imageHandler)
        serverProtocol.registerGlobalService(serviceUrl = "url://$promptServiceId/", handler = promptHandler)

        val regDeadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < regDeadline) {
            val ids = serverProtocol.getDiscoveredServices().map { it.serviceIdentifier }
            if (ids.contains(imageServiceId) && ids.contains(promptServiceId)) break
            Thread.sleep(25)
        }

        val serverPeer = Libp2pPeer.remote(
            peerId = serverJoinInfo.peerId,
            multiaddresses = serverJoinInfo.multiaddresses.map { it.replace(Regex("/ip4/[0-9.]+/"), "/ip4/127.0.0.1/") },
            advertisedServices = listOf(imageServiceId, promptServiceId)
        )

        val clientProtocol = UrlProtocol2(listOf(serverPeer), eagerlyJoinNetwork = false, listenPort = 0)
        val client = UrlResolver(clientProtocol)
        try {
            clientProtocol.joinNetwork(alias = "multi-client")
            val discoveryDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < discoveryDeadline) {
                if (clientProtocol.findPeersForService(imageServiceId).isNotEmpty() &&
                    clientProtocol.findPeersForService(promptServiceId).isNotEmpty()
                ) break
                Thread.sleep(50)
            }
            assertTrue(
                "client never discovered both loopback services within 15s (image='$imageServiceId', " +
                    "prompt='$promptServiceId'); known peers=${clientProtocol.getKnownPeers().size}",
                clientProtocol.findPeersForService(imageServiceId).isNotEmpty() &&
                    clientProtocol.findPeersForService(promptServiceId).isNotEmpty()
            )

            exercise(client, "url://$imageServiceId/", "url://$promptServiceId/")
        } finally {
            try { client.close() } catch (_: Throwable) {}
            try { clientProtocol.close() } catch (_: Throwable) {}
        }
    } finally {
        try { serverProtocol.close() } catch (_: Throwable) {}
        PublicAddressDiscovery.clear()
        if (previousExplicitIp != null) PublicAddressDiscovery.setExplicitPublicIp(previousExplicitIp)
    }
}

/**
 * Both services on one node, exercised by one client: the image URL must drive the image model
 * (returning the fixed image bytes) and the prompt URL must drive the prompt model (returning the
 * fixed text). Proves the two-domain topology `main` registers works end-to-end with correct routing.
 */
@Test
fun testImageAndPromptServicesCoexistEndToEndOverUrl() {
    fun loadRes(name: String): ByteArray {
        val s = object {}.javaClass.getResourceAsStream(name)
            ?: throw IllegalStateException("resource $name not on the test classpath")
        return s.use { it.readBytes() }
    }
    val fixedImage = ByteArray(256) { (it * 13 + 5).toByte() }
    val fixedPrompt = "coexisting prompt text"

    val imageModel = object : ImageGenerationModel {
        override fun requestImageGeneration(prompt: String, inputImages: List<ByteArray>): String {
            return "coexist-img"
        }

        override fun imageGenerationStatus(generationId: String): ImageGenerationStatus {
            return object : ImageGenerationStatus {
                override val state = GenerationState.DONE
                override val image: GeneratedImage = object : GeneratedImage {
                    override val imageBytes = fixedImage
                    override val contentType = "image/png"
                    override val url = "url://coexist/$generationId"
                }
                override val error: String? = null
            }
        }
    }
    val promptModel = object : PromptGenerationModel {
        override fun requestPromptGeneration(prompt: String, inputImages: List<ByteArray>): String {
            return "coexist-prompt"
        }

        override fun promptGenerationStatus(generationId: String): PromptGenerationStatus {
            return object : PromptGenerationStatus {
                override val state = GenerationState.DONE
                override val prompt = fixedPrompt
                override val error: String? = null
            }
        }
    }
    val imageHandler = ImageModelRpcHandler(imageModel, ByteArray(0), "x", ByteArray(0))
    val promptHandler = PromptModelRpcHandler(promptModel, ByteArray(0), "x", ByteArray(0))

    val stamp = System.nanoTime()
    runImageAndPromptNode(
        imageServiceId = "e2e.coexist.image.$stamp",
        promptServiceId = "e2e.coexist.prompt.$stamp",
        imageClientJar = loadRes("/image-client-impl.jar"),
        promptClientJar = loadRes("/prompt-client-impl.jar"),
        imageDispatch = { method, params -> imageHandler.handleP2pRequest(method, params) },
        promptDispatch = { method, params -> promptHandler.handleP2pRequest(method, params) },
        exercise = { client, imageUrl, promptUrl ->
            val imageProxy = client.openSandboxedConnection(imageUrl, ImageGenerationModel::class)
            val imageId = imageProxy.requestImageGeneration("an image please", emptyList())
            assertTrue("image id over url://", imageId.isNotEmpty())
            var imageStatus: ImageGenerationStatus? = null
            val imageDeadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < imageDeadline) {
                val s = imageProxy.imageGenerationStatus(imageId)
                if (s.state != GenerationState.PENDING) { imageStatus = s; break }
                Thread.sleep(50)
            }
            assertTrue("image status never left PENDING", imageStatus != null)
            assertEquals(GenerationState.DONE, imageStatus!!.state)
            assertTrue(
                "the image URL must route to the image model and return its exact bytes",
                imageStatus.image!!.imageBytes.contentEquals(fixedImage)
            )

            val promptProxy = client.openSandboxedConnection(promptUrl, PromptGenerationModel::class)
            val promptId = promptProxy.requestPromptGeneration("a prompt please")
            assertTrue("prompt id over url://", promptId.isNotEmpty())
            var promptStatus: PromptGenerationStatus? = null
            val promptDeadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < promptDeadline) {
                val s = promptProxy.promptGenerationStatus(promptId)
                if (s.state != GenerationState.PENDING) { promptStatus = s; break }
                Thread.sleep(50)
            }
            assertTrue("prompt status never left PENDING", promptStatus != null)
            assertEquals(GenerationState.DONE, promptStatus!!.state)
            assertEquals("the prompt URL must route to the prompt model", fixedPrompt, promptStatus.prompt)
        }
    )
}

// ===================== Timeout + cancel over the full url:// transport =====================
//
// These exercise the server-side TimeoutEnforcingImageModel/TimeoutEnforcingPromptModel — the exact
// wrapping `main` applies — through the SJVM sandbox proxy. The backing model never completes on its
// own; the enforcer (Clock-driven, advanced/triggered from the test) is what turns the generation
// terminal, proving the timeout and the cancel RPC survive the openSandboxedConnection -> client
// bytecode -> RPC -> handler -> enforcer round-trip.

/**
 * A generation stuck PENDING on the backing model is reported as a timeout ERROR to the sandboxed
 * client once the server-side enforcer's [ManualClock] passes the 5-minute deadline — verifying the
 * timeout is enforced on the SERVER and surfaces, verbatim, over url://. The clock is advanced from
 * the test thread, so no real waiting occurs.
 */
@Test
fun testImageGenerationTimesOutOverUrl() {
    fun loadRes(name: String): ByteArray {
        val s = object {}.javaClass.getResourceAsStream(name)
            ?: throw IllegalStateException("resource $name not on the test classpath")
        return s.use { it.readBytes() }
    }
    val backing = object : ImageGenerationModel {
        override fun requestImageGeneration(prompt: String, inputImages: List<ByteArray>): String = "img-stuck"
        override fun imageGenerationStatus(generationId: String): ImageGenerationStatus = object : ImageGenerationStatus {
            override val state = GenerationState.PENDING
            override val image: GeneratedImage? = null
            override val error: String? = null
        }
    }
    val clock = ManualClock(0L)
    val enforcer = TimeoutEnforcingImageModel(backing, clock, 5 * 60 * 1000L)
    val handler = ImageModelRpcHandler(enforcer, ByteArray(0), "x", ByteArray(0))

    runTwoNodeServiceTest(
        serviceId = "e2e.image.timeout.${System.nanoTime()}",
        clientJar = loadRes("/image-client-impl.jar"),
        implClassName = "evergreenserviceserver/ImageGenerationModelClientImpl",
        dispatch = { method, params -> handler.handleP2pRequest(method, params) },
        exercise = { client, url ->
            val proxy = client.openSandboxedConnection(url, ImageGenerationModel::class)
            val id = proxy.requestImageGeneration("a stuck generation", emptyList())
            assertTrue("requestImageGeneration must return a non-empty id over url://", id.isNotEmpty())
            assertEquals("before the deadline the generation is PENDING over url://",
                GenerationState.PENDING, proxy.imageGenerationStatus(id).state)

            clock.advanceBy(5 * 60 * 1000L) // cross the server-side deadline

            // Poll: the next status RPC must reflect the server-enforced timeout.
            var status: ImageGenerationStatus? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val s = proxy.imageGenerationStatus(id)
                if (s.state != GenerationState.PENDING) { status = s; break }
                Thread.sleep(50)
            }
            assertTrue("the server-enforced timeout never surfaced over url://", status != null)
            assertEquals(GenerationState.ERROR, status!!.state)
            assertNull("a timed-out generation must carry no image over url://", status.image)
            assertTrue("the timeout error must explain it timed out, but was: ${status.error}",
                status.error!!.contains("timed out after 300 seconds"))
        }
    )
}

/**
 * The cancel RPC works end-to-end: the sandboxed client's cancelImageGeneration call reaches the
 * server enforcer, returns true, and the generation's subsequent status becomes a cancellation ERROR.
 * This is the path the WUI's Cancel button drives — proving a default Api method routes through the
 * proxy to the sandbox client bytecode and back.
 */
@Test
fun testImageGenerationCancelOverUrl() {
    fun loadRes(name: String): ByteArray {
        val s = object {}.javaClass.getResourceAsStream(name)
            ?: throw IllegalStateException("resource $name not on the test classpath")
        return s.use { it.readBytes() }
    }
    val backing = object : ImageGenerationModel {
        override fun requestImageGeneration(prompt: String, inputImages: List<ByteArray>): String = "img-stuck"
        override fun imageGenerationStatus(generationId: String): ImageGenerationStatus = object : ImageGenerationStatus {
            override val state = GenerationState.PENDING
            override val image: GeneratedImage? = null
            override val error: String? = null
        }
    }
    val enforcer = TimeoutEnforcingImageModel(backing) // default 5-min timeout; cancel happens first
    val handler = ImageModelRpcHandler(enforcer, ByteArray(0), "x", ByteArray(0))

    runTwoNodeServiceTest(
        serviceId = "e2e.image.cancel.${System.nanoTime()}",
        clientJar = loadRes("/image-client-impl.jar"),
        implClassName = "evergreenserviceserver/ImageGenerationModelClientImpl",
        dispatch = { method, params -> handler.handleP2pRequest(method, params) },
        exercise = { client, url ->
            val proxy = client.openSandboxedConnection(url, ImageGenerationModel::class)
            val id = proxy.requestImageGeneration("a generation to cancel", emptyList())
            assertEquals("the generation starts PENDING over url://", GenerationState.PENDING, proxy.imageGenerationStatus(id).state)

            assertTrue("cancelImageGeneration must return true for a pending generation over url://",
                proxy.cancelImageGeneration(id))

            var status: ImageGenerationStatus? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val s = proxy.imageGenerationStatus(id)
                if (s.state != GenerationState.PENDING) { status = s; break }
                Thread.sleep(50)
            }
            assertTrue("the cancellation never surfaced over url://", status != null)
            assertEquals(GenerationState.ERROR, status!!.state)
            assertTrue("the error must explain it was cancelled, but was: ${status.error}",
                status.error!!.contains("was cancelled"))

            assertFalse("a second cancel of the same generation must return false over url://",
                proxy.cancelImageGeneration(id))
        }
    )
}

/** The prompt-side cancel RPC works end-to-end (the prompt client bytecode's cancel path over url://). */
@Test
fun testPromptGenerationCancelOverUrl() {
    fun loadRes(name: String): ByteArray {
        val s = object {}.javaClass.getResourceAsStream(name)
            ?: throw IllegalStateException("resource $name not on the test classpath")
        return s.use { it.readBytes() }
    }
    val backing = object : PromptGenerationModel {
        override fun requestPromptGeneration(prompt: String, inputImages: List<ByteArray>): String = "prompt-stuck"
        override fun promptGenerationStatus(generationId: String): PromptGenerationStatus = object : PromptGenerationStatus {
            override val state = GenerationState.PENDING
            override val prompt: String? = null
            override val error: String? = null
        }
    }
    val enforcer = TimeoutEnforcingPromptModel(backing)
    val handler = PromptModelRpcHandler(enforcer, ByteArray(0), "x", ByteArray(0))

    runTwoNodeServiceTest(
        serviceId = "e2e.prompt.cancel.${System.nanoTime()}",
        clientJar = loadRes("/prompt-client-impl.jar"),
        implClassName = "evergreenserviceserver/PromptGenerationModelClientImpl",
        dispatch = { method, params -> handler.handleP2pRequest(method, params) },
        exercise = { client, url ->
            val proxy = client.openSandboxedConnection(url, PromptGenerationModel::class)
            val id = proxy.requestPromptGeneration("a prompt to cancel", emptyList())
            assertEquals(GenerationState.PENDING, proxy.promptGenerationStatus(id).state)

            assertTrue("cancelPromptGeneration must return true for a pending generation over url://",
                proxy.cancelPromptGeneration(id))

            var status: PromptGenerationStatus? = null
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val s = proxy.promptGenerationStatus(id)
                if (s.state != GenerationState.PENDING) { status = s; break }
                Thread.sleep(50)
            }
            assertTrue("the prompt cancellation never surfaced over url://", status != null)
            assertEquals(GenerationState.ERROR, status!!.state)
            assertTrue("the error must explain it was cancelled, but was: ${status.error}",
                status.error!!.contains("was cancelled"))
        }
    )
}
