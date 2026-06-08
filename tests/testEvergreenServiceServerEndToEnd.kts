@file:WithArtifact("evergreenserviceserver.buildMaven()")
@file:WithArtifact("evergreenserviceserver.buildImageClientResourcesJar()")
@file:WithArtifact("evergreenserviceserver.buildPromptClientResourcesJar()")
@file:WithArtifact("photogenerationmanager.api:photo-generation-manager-api:0.0.3")
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
import foundation.url.protocol.BootstrapPeer
import foundation.url.protocol.Libp2pPeer
import foundation.url.protocol.PublicAddressDiscovery
import foundation.url.protocol.ServiceHandler
import foundation.url.resolver.UrlProtocol2
import foundation.url.resolver.UrlResolver
import org.junit.Assert.assertEquals
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
