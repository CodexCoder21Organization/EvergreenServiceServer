@KotlinBuildScript("https://tools.kotlin.build/")
@file:WithArtifact("kompile:build-kotlin-jvm:0.0.23")
package evergreenserviceserver

import build.kotlin.withartifact.WithArtifact
import java.io.File
import build.kotlin.jvm.*
import build.kotlin.annotations.MavenArtifactCoordinates

val dependencies = resolveDependencies2(
    // PhotoGenerationManager Api (model interfaces) + Embedded (the model implementations)
    MavenPrebuilt2("photogenerationmanager.api:photo-generation-manager-api:0.0.4"),
    MavenPrebuilt2("photogenerationmanager.embedded:photo-generation-manager-embedded:0.0.7"),
    // HTTP client used by the Embedded
    MavenPrebuilt2("com.squareup.okhttp3:okhttp:4.11.0"),
    // Clock abstraction (Embedded + UrlProtocol)
    MavenPrebuilt2("community.kotlin.clocks.simple:community-kotlin-clocks-simple:0.0.3"),
    // UrlResolver + UrlProtocol — consistent version pair (see DigitalOceanDropletServiceServer)
    MavenPrebuilt2("foundation.url:resolver:0.0.504"),
    MavenPrebuilt2("foundation.url:protocol:0.0.275"),
    MavenPrebuilt2("community.kotlin.observable:core-jvm:0.3.4"),
    // SJVM runtime + stdlib
    MavenPrebuilt2("net.javadeploy.sjvm:libSJVM-jvm:0.0.38"),
    MavenPrebuilt2("net.javadeploy.sjvm:avianStdlibHelper-jvm:0.0.38"),
    MavenPrebuilt2("net.javadeploy.sjvm:stdlibHelperCommon-jvm:0.0.38"),
    MavenPrebuilt2("org.ow2.asm:asm:9.6"),
    MavenPrebuilt2("org.ow2.asm:asm-commons:9.6"),
    // libp2p stack
    MavenPrebuilt2("io.libp2p:jvm-libp2p:1.2.2-RELEASE"),
    MavenPrebuilt2("org.jetbrains.kotlin:kotlin-reflect:1.9.22"),
    MavenPrebuilt2("community.kotlin.rpc:protocol-api:0.0.2"),
    MavenPrebuilt2("community.kotlin.rpc:protocol-impl:0.0.11"),
    MavenPrebuilt2("com.google.protobuf:protobuf-java:3.25.1"),
    MavenPrebuilt2("tech.pegasys:noise-java:22.1.0"),
    MavenPrebuilt2("org.json:json:20250517"),
    // Kotlin stdlib + coroutines
    MavenPrebuilt2("org.jetbrains.kotlin:kotlin-stdlib:1.9.22"),
    MavenPrebuilt2("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22"),
    MavenPrebuilt2("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22"),
    MavenPrebuilt2("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0"),
    MavenPrebuilt2("com.squareup.okio:okio-jvm:3.4.0"),
    MavenPrebuilt2("org.slf4j:slf4j-api:1.7.36"),
    MavenPrebuilt2("org.slf4j:slf4j-simple:1.7.36"),
    // Netty (for libp2p)
    MavenPrebuilt2("io.netty:netty-buffer:4.1.101.Final"),
    MavenPrebuilt2("io.netty:netty-codec:4.1.101.Final"),
    MavenPrebuilt2("io.netty:netty-codec-http:4.1.101.Final"),
    MavenPrebuilt2("io.netty:netty-codec-http2:4.1.101.Final"),
    MavenPrebuilt2("io.netty:netty-common:4.1.101.Final"),
    MavenPrebuilt2("io.netty:netty-handler:4.1.101.Final"),
    MavenPrebuilt2("io.netty:netty-resolver:4.1.101.Final"),
    MavenPrebuilt2("io.netty:netty-transport:4.1.101.Final"),
    MavenPrebuilt2("io.netty:netty-transport-classes-epoll:4.1.101.Final"),
    MavenPrebuilt2("io.netty:netty-transport-classes-kqueue:4.1.101.Final"),
    MavenPrebuilt2("io.netty:netty-transport-native-unix-common:4.1.101.Final"),
    // BouncyCastle
    MavenPrebuilt2("org.bouncycastle:bcpkix-jdk18on:1.78.1"),
    MavenPrebuilt2("org.bouncycastle:bcprov-jdk18on:1.78.1"),
    MavenPrebuilt2("org.bouncycastle:bcutil-jdk18on:1.78.1"),
    // Guava (required by libp2p)
    MavenPrebuilt2("com.google.guava:guava:33.2.0-jre"),
    MavenPrebuilt2("com.google.guava:failureaccess:1.0.2"),
)

// Client bytecode deps (compile the duck-typed client impls against the Api + ServiceBridge stub).
val clientDependencies by lazy {
    resolveDependencies2(
        MavenPrebuilt2("org.jetbrains.kotlin:kotlin-stdlib:1.9.22"),
        MavenPrebuilt2("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22"),
        MavenPrebuilt2("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22"),
        MavenPrebuilt2("photogenerationmanager.api:photo-generation-manager-api:0.0.4"),
        MavenPrebuilt2("foundation.url:service-bridge-stub:0.0.1"),
    )
}

@MavenArtifactCoordinates("evergreenserviceserver:evergreen-service-server:")
fun buildMaven(): File {
    return buildSimpleKotlinMavenArtifact(
        // 0.0.5: Enforce a server-side 5-minute generation timeout and cancellation. Each backing
        //        model is wrapped in a TimeoutEnforcingImageModel/TimeoutEnforcingPromptModel
        //        (Clock-driven, default SystemClock): a generation still PENDING past the deadline,
        //        or one a consumer cancels, is reported as ERROR. New RPC method
        //        cancelImageGeneration/cancelPromptGeneration -> {cancelled}; bumps the Api to 0.0.4
        //        and the client jars to 0.0.3 (they gain the cancel call).
        // 0.0.1: Initial release — hosts EvergreenImageGenerationModel + EvergreenPromptGenerationModel
        //        behind url://evergreen-image-model/ and url://evergreen-prompt-model/. Image bytes
        //        cross the SJVM boundary as hex.
        // 0.0.3: Extract env/resource config into a documented, testable ServerConfig API
        //        (resolveServerConfig / loadServerResource); no behavioural change to main().
        // 0.0.4: Correct DEFAULT_PROMPT_MODEL_URL to the lyria_rewriter text model (the previous
        //        gemfuse_image_agent default returned images, never text); bump Embedded to 0.0.7
        //        (auto-prefixes prompts with "Expand this prompt: ").
        coordinates = "evergreenserviceserver:evergreen-service-server:0.0.5",
        src = File("src"),
        compileDependencies = dependencies
    )
}

fun buildSkinnyJar(): File = buildMaven().jar

private fun buildClientJar(srcDir: String, coordinates: String): File {
    return buildSimpleKotlinMavenArtifact(
        coordinates = coordinates,
        src = File(srcDir),
        compileDependencies = clientDependencies
    ).jar
}

fun buildImageClientJar(): File = buildClientJar("src-client-image", "evergreenserviceserver:evergreen-image-client:0.0.3")
fun buildPromptClientJar(): File = buildClientJar("src-client-prompt", "evergreenserviceserver:evergreen-prompt-client:0.0.3")

private fun resourceJar(entryName: String, contentJar: File): File {
    val tempFile = File.createTempFile("client-resources", ".jar")
    java.util.jar.JarOutputStream(tempFile.outputStream()).use { jos ->
        jos.putNextEntry(java.util.jar.JarEntry(entryName))
        jos.write(contentJar.readBytes())
        jos.closeEntry()
    }
    return tempFile
}

/** Fat client jar (impl + its deps), wrapped as the given resource entry. */
private fun buildClientResourcesJar(srcDir: String, coordinates: String, entryName: String): File {
    val skinny = buildClientJar(srcDir, coordinates)
    val fat = BuildJar(null, clientDependencies.map { it.jar } + skinny)
    return resourceJar(entryName, fat)
}

/** Public wrappers so e2e tests can load the client bytecode from the classpath as a resource. */
fun buildImageClientResourcesJar(): File = buildClientResourcesJar("src-client-image", "evergreenserviceserver:evergreen-image-client:0.0.3", "image-client-impl.jar")
fun buildPromptClientResourcesJar(): File = buildClientResourcesJar("src-client-prompt", "evergreenserviceserver:evergreen-prompt-client:0.0.3", "prompt-client-impl.jar")

fun buildFatJar(): File {
    val manifest = Manifest("evergreenserviceserver.MainKt")
    val imageResources = buildClientResourcesJar("src-client-image", "evergreenserviceserver:evergreen-image-client:0.0.3", "image-client-impl.jar")
    val promptResources = buildClientResourcesJar("src-client-prompt", "evergreenserviceserver:evergreen-prompt-client:0.0.3", "prompt-client-impl.jar")
    return BuildJar(manifest, dependencies.map { it.jar } + buildSkinnyJar() + imageResources + promptResources)
}
