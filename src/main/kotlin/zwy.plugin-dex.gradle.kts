import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// ---------------------------------------------------------------------------
// Config: gradle.properties holds paths/names, API_KEYS holds secrets.
// Everything is resolved lazily, so `gradlew tasks` and IDE sync keep working
// even when the host checkout or the device is not there.
// ---------------------------------------------------------------------------

val host = HostSource(providers) { file(it) }

fun prop(key: String): String? = host.prop(key)

fun requiredProp(key: String): String = host.requiredProp(key)

val apiKeys: Properties by lazy {
    Properties().apply {
        val file = rootProject.file("API_KEYS")
        if (file.exists()) file.inputStream().use(::load) else logger.warn("API_KEYS file not found")
    }
}

fun secret(key: String): String? = apiKeys.getProperty(key)?.trim()?.ifEmpty { null }

// ---------------------------------------------------------------------------
// Compilation. Nothing is bundled into the DEX except the plugin's own
// classes: Kotlin stdlib, androidx and Xposed are provided by the host.
// ---------------------------------------------------------------------------

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val artifactType: Attribute<String> = Attribute.of("artifactType", String::class.java)

val hostLibs: Configuration = configurations.create("hostLibs") {
    isCanBeConsumed = false

    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.ANDROID))
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.androidJvm)
    }

    dependencies.addAllLater(provider {
        host.libs.map { project.dependencies.create(host.coordinate(it)) }
    })
}

val r8: Configuration = configurations.create("r8")

dependencies {
    registerTransform(AarClasses::class) {
        from.attribute(artifactType, "aar")
        to.attribute(artifactType, "jar")
    }

    "compileOnly"(kotlin("stdlib"))
    "compileOnly"(files(provider { listOf(host.androidJar) + host.classes }))
    "compileOnly"(hostLibs.incoming.artifactView { attributes.attribute(artifactType, "jar") }.files)

    r8("com.android.tools:r8:${requiredProp("r8.version")}")
}

// ---------------------------------------------------------------------------
// classes -> classes.dex
// ---------------------------------------------------------------------------

val dexDir = layout.buildDirectory.dir("dex")
val dexFile = dexDir.map { it.file("classes.dex") }

val buildDex = tasks.register<JavaExec>("buildDex") {
    group = "plugin"
    description = "Compiles the plugin into classes.dex"

    val classesJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val compileClasspath = configurations.named("compileClasspath")
    val minApi = requiredProp("dex.min.api")

    inputs.file(classesJar)
    inputs.files(compileClasspath)
    inputs.property("minApi", minApi)
    outputs.dir(dexDir)

    classpath = r8
    mainClass.set("com.android.tools.r8.D8")

    doFirst {
        val output = dexDir.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        args("--release", "--min-api", minApi, "--lib", host.androidJar.absolutePath)
        compileClasspath.get().filter { it != host.androidJar }.forEach { args("--classpath", it.absolutePath) }
        args("--output", output.absolutePath, classesJar.get().asFile.absolutePath)
    }
}

// ---------------------------------------------------------------------------
// classes.dex -> xz -> base64 -> string constant in the python plugin
// ---------------------------------------------------------------------------

val pluginFile: File by lazy {
    file(requiredProp("plugin.path")).also {
        if (!it.isFile) throw GradleException("plugin.path does not exist: $it")
    }
}

// Unpacked on the device with the stdlib alone: lzma.decompress(base64.b64decode(...)).
// The dictionary is sized to the input: an xz preset would make the decoder
// allocate up to 64 MiB to unpack a ~100 KiB file.
fun xz(data: ByteArray): ByteArray {
    val options = LZMA2Options(LZMA2Options.PRESET_MAX).apply {
        dictSize = maxOf(LZMA2Options.DICT_SIZE_MIN, Integer.highestOneBit(data.size) shl 1)
        niceLen = LZMA2Options.NICE_LEN_MAX
    }

    return ByteArrayOutputStream().also { output ->
        XZOutputStream(output, options).use { it.write(data) }
    }.toByteArray()
}

val packedDexFile = layout.buildDirectory.file("packed/classes.dex.xz.b64")

val packDex = tasks.register("packDex") {
    group = "plugin"
    description = "Packs classes.dex into the base64 string the python plugin embeds"

    inputs.file(dexFile).withPropertyName("dex")
    outputs.file(packedDexFile)
    dependsOn(buildDex)

    doLast {
        val dex = dexFile.get().asFile.readBytes()
        val packed = xz(dex)
        val encoded = Base64.getEncoder().encodeToString(packed)

        packedDexFile.get().asFile.apply { parentFile.mkdirs() }.writeText(encoded)
        logger.lifecycle("dex ${dex.size} B -> xz ${packed.size} B -> base64 ${encoded.length} chars")
    }
}

val embedDex = tasks.register("embedDex") {
    group = "plugin"
    description = "Packs classes.dex into the python plugin"
    dependsOn(packDex)

    doLast {
        val variable = requiredProp("plugin.dex.var")
        val pattern = Regex("""(?m)^${Regex.escape(variable)}\s*=\s*"[^"]*"""")
        val encoded = packedDexFile.get().asFile.readText()

        val text = pluginFile.readText()
        val matches = pattern.findAll(text).count()
        if (matches != 1) {
            throw GradleException("Expected exactly one `$variable = \"...\"` in ${pluginFile.name}, found $matches")
        }

        val updated = pattern.replace(text) { "$variable = \"$encoded\"" }
        if (updated != text) pluginFile.writeText(updated)

        logger.lifecycle("${pluginFile.name}" + if (updated == text) " (unchanged)" else " updated")
    }
}

// ---------------------------------------------------------------------------
// Host SDK for CI: everything the build takes from the host checkout, packed
// into one archive that `-Phost.sdk=<unpacked dir>` builds against.
// ---------------------------------------------------------------------------

val hostClassesJar = tasks.register<Jar>("hostClassesJar") {
    archiveFileName.set(HostSource.SDK_CLASSES)
    destinationDirectory.set(layout.buildDirectory.dir("host-sdk"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(provider { host.classes.map { if (it.isFile) zipTree(it) else it } })
}

tasks.register<Zip>("exportHostSdk") {
    group = "plugin"
    description = "Packs the host classes, android.jar and maven mirror into build/host-sdk.zip"

    archiveFileName.set("host-sdk.zip")
    destinationDirectory.set(layout.buildDirectory)

    from(hostClassesJar)
    from(provider { host.androidJar })
    from(provider { host.buildScriptFile }) { rename { HostSource.SDK_BUILD_SCRIPT } }
    into(HostSource.SDK_MAVEN) { from(provider { host.mavenDir }) }
}

// ---------------------------------------------------------------------------
// Install over ADB through the dev server of the app (plugin settings ->
// developer mode). The server installs, reloads and enables the plugin itself.
// ---------------------------------------------------------------------------

val adbPath: String by lazy {
    val executable = if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb"

    prop("adb.path")
        ?: host.androidJar.resolve("../../../platform-tools/$executable").normalize().takeIf { it.isFile }?.absolutePath
        ?: "adb"
}

fun adb(vararg args: String): String {
    val command = listOf(adbPath) + listOfNotNull(prop("adb.serial")).flatMap { listOf("-s", it) } + args

    val process = try {
        ProcessBuilder(command).redirectErrorStream(true).start()
    } catch (e: IOException) {
        throw GradleException("Cannot run adb ($adbPath), set adb.path in gradle.properties", e)
    }

    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() != 0) throw GradleException("adb ${args.joinToString(" ")}: $output")

    return output
}

fun devServerRequest(port: Int, action: String, payload: Map<String, Any?>): Map<*, *> {
    val request = JsonOutput.toJson(mapOf("@" to action, "#" to 1) + payload).toByteArray()
    val unreachable = "Dev server did not answer on port $port. " +
        "Is the app running with developer mode enabled in the plugin settings?"

    try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 15_000
            socket.getOutputStream().apply { write(request); flush() }

            val response = ByteArrayOutputStream()
            val buffer = ByteArray(4096)

            while (true) {
                val read = socket.getInputStream().read(buffer)
                if (read < 0) throw GradleException(unreachable)

                response.write(buffer, 0, read)
                runCatching { JsonSlurper().parseText(response.toString(Charsets.UTF_8)) as Map<*, *> }
                    .getOrNull()
                    ?.let { return it }
            }
        }
    } catch (e: IOException) {
        throw GradleException(unreachable, e)
    }
}

val installPlugin = tasks.register("installPlugin") {
    group = "plugin"
    description = "Installs the python plugin on the connected device"
    dependsOn(embedDex)

    doLast {
        val port = requiredProp("devserver.port").toInt()
        val text = pluginFile.readText()
        val pluginId = Regex("""(?m)^__id__\s*=\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
            ?: throw GradleException("No __id__ in ${pluginFile.name}")

        adb("get-state")

        // The modem reserves random ports for itself and the kernel then refuses to bind them,
        // so the dev server silently fails to start when its port happens to be one of them.
        val reserved = adb("shell", "cat", "/proc/sys/net/ipv4/ip_local_reserved_ports").split(',').any { range ->
            val bounds = range.trim().split('-').mapNotNull(String::toIntOrNull)
            bounds.isNotEmpty() && port in bounds.first()..bounds.last()
        }
        if (reserved) {
            throw GradleException(
                "Port $port is currently reserved by the device itself, the dev server cannot listen on it. " +
                    "Toggle airplane mode (or reboot) so the modem picks other ports, then restart the app."
            )
        }

        adb("forward", "tcp:$port", "tcp:$port")

        val response = devServerRequest(port, "write_plugin", mapOf("plugin_id" to pluginId, "content" to text))
        response["error"]?.let { throw GradleException("Dev server rejected $pluginId: $it") }

        logger.lifecycle("$pluginId installed on the device")
    }
}

tasks.register("deploy") {
    group = "plugin"
    description = "Builds the DEX, packs it into the plugin and installs it on the device"
    dependsOn(installPlugin)
}

// ---------------------------------------------------------------------------
// Optional: share the built plugin file through a Telegram bot.
// ---------------------------------------------------------------------------

tasks.register("sendToTelegram") {
    group = "plugin"
    description = "Sends the python plugin to the chat configured in API_KEYS"
    dependsOn(embedDex)

    doLast {
        val token = secret("telegram.bot.token") ?: throw GradleException("telegram.bot.token is not set in API_KEYS")
        val fields = mapOf(
            "chat_id" to (secret("telegram.send.target")
                ?: throw GradleException("telegram.send.target is not set in API_KEYS")),
            "message_thread_id" to secret("telegram.send.target.topic"),
            "caption" to "${pluginFile.name}, dex ${dexFile.get().asFile.length()} B",
        )

        val boundary = "gradle-${System.nanoTime()}"
        val body = ByteArrayOutputStream().apply {
            fields.forEach { (name, value) ->
                if (value == null) return@forEach
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }

            write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"${pluginFile.name}\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
            )
            write(pluginFile.readBytes())
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()

        val request = HttpRequest.newBuilder(URI("https://api.telegram.org/bot$token/sendDocument"))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw GradleException("Telegram answered ${response.statusCode()}: ${response.body()}")
        }

        logger.lifecycle("${pluginFile.name} sent to Telegram")
    }
}