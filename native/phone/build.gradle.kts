plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val samsungSdk = tasks.register<Copy>("extractSamsungSdk") {
    // The owner keeps Samsung's SDK archives with the Orbit project files on D:, not in Downloads.
    val archive = providers.environmentVariable("SAMSUNG_HEALTH_SDK")
        .orElse("D:/07 - Projects & Prototypes/02 - Orbit/samsung-health-data-sdk-1.1.0.zip")
    from(zipTree(archive)) {
        include("1.1.0/libs/samsung-health-data-api-1.1.0.aar")
        eachFile { path = name }
    }
    into(layout.buildDirectory.dir("samsung-sdk"))
    includeEmptyDirs = false
}

abstract class NativeBackendSources : DefaultTask() {
    @get:InputDirectory abstract val sourceDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun prepare() {
        project.sync {
            from(sourceDirectory) { exclude("**/MainActivity.java", "**/OrbitFeedback.java", "**/SamsungHealth.java",
                "**/HealthConnectReader.java", "**/HealthRecordCodec.java", "**/HealthPrivacyActivity.java") }
            into(outputDirectory)
        }
    }
}
val nativeBackend = tasks.register<NativeBackendSources>("prepareNativeBackend") {
    sourceDirectory.set(layout.projectDirectory.dir("../../android/src"))
}

android {
    namespace = "com.mani.orbit"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.mani.orbit"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main") {
            res.directories.add("../../android/res")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
androidComponents {
    onVariants { variant -> variant.sources.java?.addGeneratedSourceDirectory(nativeBackend) { it.outputDirectory } }
}

dependencies {
    constraints { implementation("androidx.fragment:fragment:1.5.4") { because("Activity Result permissions require Fragment >= 1.3; Play services otherwise selects 1.1") } }
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation(project(":sync"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation(files(samsungSdk.map { it.destinationDir.resolve("samsung-health-data-api-1.1.0.aar") }).builtBy(samsungSdk))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.3.21")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
