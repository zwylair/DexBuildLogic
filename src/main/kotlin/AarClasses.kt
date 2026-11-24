import java.util.zip.ZipFile
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

// Android libraries are AARs, which a JVM project cannot consume directly.
abstract class AarClasses : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val aar: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = aar.get().asFile

        ZipFile(input).use { zip ->
            zip.entries().asSequence()
                .filter { it.name == "classes.jar" || (it.name.startsWith("libs/") && it.name.endsWith(".jar")) }
                .forEach { entry ->
                    val output = outputs.file("${input.nameWithoutExtension}-${entry.name.substringAfterLast('/')}")
                    zip.getInputStream(entry).use { source -> output.outputStream().use { source.copyTo(it) } }
                }
        }
    }
}