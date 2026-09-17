import groovy.json.JsonOutput
import java.security.MessageDigest

plugins {
    id("com.android.application") version "9.4.0" apply false
    id("com.android.library") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
    }
}

// Same input manifest as dev.py: embed the identity of the native source, never machine paths.
val nativeInputs = files(listOf("phone/src", "wear/src", "sync/src", "gradle", "../android/src", "../android/res").map { path ->
    fileTree(path) { exclude("**/build/**", "**/.gradle/**", "**/.git/**", "**/__pycache__/**") }
}, listOf(".", "phone", "wear", "sync").map { path ->
    fileTree(path) { include("*.kts", "*.properties", "*.lockfile", "*.py", "*.ps1", "*.bat", "gradlew") }
})
abstract class NativeIdentity : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourceFiles: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun generate() {
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifest = sourceFiles.files.associate { it.relativeTo(project.rootDir.parentFile).invariantSeparatorsPath to sha(it.readBytes()) }.toSortedMap()
        val identity = mapOf("schema" to 1, "product" to "orbit-native", "source" to sha(JsonOutput.toJson(manifest).toByteArray(Charsets.UTF_8)))
        outputDirectory.get().asFile.apply { mkdirs() }.resolve("orbit-native-build.json").writeText(JsonOutput.toJson(identity))
    }
}
val nativeIdentity = tasks.register<NativeIdentity>("nativeIdentity") {
    sourceFiles.from(nativeInputs)
    outputDirectory.set(layout.buildDirectory.dir("native-identity"))
}
subprojects {
    plugins.withId("com.android.application") {
        extensions.getByType(com.android.build.api.variant.ApplicationAndroidComponentsExtension::class.java).onVariants { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(nativeIdentity) { it.outputDirectory }
        }
    }
}
