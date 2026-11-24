import java.io.File
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.provider.ProviderFactory

class HostSource(private val providers: ProviderFactory, private val resolve: (String) -> File) {
    fun prop(key: String): String? = providers.gradleProperty(key).orNull?.trim()?.ifEmpty { null }

    fun requiredProp(key: String): String =
        prop(key) ?: throw GradleException("'$key' is not set in gradle.properties")

    val sdkDir: File? by lazy {
        prop("host.sdk")?.let(resolve)?.also {
            if (!it.isDirectory) throw GradleException("host.sdk does not exist: $it")
        }
    }

    val hostDir: File by lazy {
        resolve(requiredProp("host.dir")).also {
            if (!it.isDirectory) throw GradleException("host.dir does not exist: $it")
        }
    }

    val variant: String by lazy { requiredProp("host.variant") }

    val buildScriptFile: File by lazy {
        sdkDir?.resolve(SDK_BUILD_SCRIPT)?.takeIf { it.isFile }
            ?: listOf("build.gradle", "build.gradle.kts")
                .map { hostDir.resolve("TMessagesProj/$it") }
                .firstOrNull { it.isFile }
            ?: throw GradleException("No TMessagesProj/build.gradle(.kts) in ${sdkDir ?: hostDir}")
    }

    val buildScript: String by lazy { buildScriptFile.readText() }

    val mavenDir: File by lazy { sdkDir?.resolve(SDK_MAVEN) ?: resolve(requiredProp("host.dir")).resolve("Tools/maven") }

    private fun intermediates(kind: String, leaf: String): List<File> =
        hostDir.resolve("TMessagesProj/build/intermediates/$kind/$variant")
            .listFiles().orEmpty()
            .map { it.resolve(leaf) }
            .filter { it.exists() }

    val classes: List<File> by lazy {
        sdkDir?.let { sdk ->
            return@lazy listOf(sdk.resolve(SDK_CLASSES).also {
                if (!it.isFile) throw GradleException("No $SDK_CLASSES in host.sdk $sdk")
            })
        }

        val classes = intermediates("javac", "classes") + intermediates("built_in_kotlinc", "classes")

        if (classes.isEmpty()) {
            val name = variant.replaceFirstChar(Char::uppercase)
            throw GradleException(
                "No compiled classes of variant '$variant' in $hostDir.\n" +
                    "Compile the host once: gradlew :TMessagesProj:compile${name}JavaWithJavac"
            )
        }

        val rJar = intermediates("compile_and_runtime_r_class_jar", "R.jar") +
            intermediates("compile_r_class_jar", "R.jar")

        classes + rJar.take(1)
    }

    // "group:name" takes the version the host declares, "group:name:version" pins it.
    fun coordinate(lib: String): String {
        if (lib.count { it == ':' } >= 2) return lib

        val version = Regex("""["']${Regex.escape(lib)}:([\w.+-]+)["']""").find(buildScript)?.groupValues?.get(1)
            ?: throw GradleException("'$lib' is not declared in the host build script, pin its version in host.libs")

        return "$lib:$version"
    }

    val libs: List<String> by lazy { requiredProp("host.libs").split(',').map(String::trim).filter(String::isNotEmpty) }

    val libGroups: List<String> by lazy { libs.map { it.substringBefore(':') }.distinct() }

    val androidJar: File by lazy {
        sdkDir?.let { sdk ->
            return@lazy sdk.resolve(SDK_ANDROID_JAR).also {
                if (!it.isFile) throw GradleException("No $SDK_ANDROID_JAR in host.sdk $sdk")
            }
        }

        val sdkRoot = prop("android.sdk.dir")
            ?: hostDir.resolve("local.properties").takeIf { it.isFile }
                ?.let { file -> Properties().apply { file.inputStream().use(::load) }.getProperty("sdk.dir") }
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: throw GradleException("Android SDK not found: set android.sdk.dir, or sdk.dir in the host local.properties")

        val compileSdk = Regex("""compileSdk(?:Version)?[\s=(]+(\d+)""").find(buildScript)?.groupValues?.get(1)
            ?: throw GradleException("compileSdk not found in the host build script")

        val platforms = resolve(sdkRoot).resolve("platforms")

        listOf("android-$compileSdk", "android-$compileSdk.0")
            .map { platforms.resolve("$it/android.jar") }
            .firstOrNull { it.isFile }
            ?: throw GradleException("android.jar of API $compileSdk not found in $platforms")
    }

    companion object {
        const val SDK_CLASSES = "host-classes.jar"
        const val SDK_ANDROID_JAR = "android.jar"
        const val SDK_BUILD_SCRIPT = "build.gradle"
        const val SDK_MAVEN = "maven"
    }
}