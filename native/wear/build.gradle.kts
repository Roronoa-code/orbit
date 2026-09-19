import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val samsungSensorSdk = tasks.register<Copy>("extractSamsungSensorSdk") {
    // The owner keeps Samsung's SDK archives with the Orbit project files on D:, not in Downloads.
    val archive = providers.environmentVariable("SAMSUNG_SENSOR_SDK")
        .orElse("D:/07 - Projects & Prototypes/02 - Orbit/samsung-health-sensor-sdk-v1.4.1.zip")
    from(zipTree(archive)) {
        include("1.4.1/libs/samsung-health-sensor-api-1.4.1.aar")
        eachFile { path = name }
    }
    into(layout.buildDirectory.dir("samsung-sensor-sdk"))
    includeEmptyDirs = false
    doLast {
        val aar = destinationDir.resolve("samsung-health-sensor-api-1.4.1.aar")
        val hash = MessageDigest.getInstance("SHA-256").digest(aar.readBytes()).joinToString("") { "%02x".format(it) }
        check(hash == "893cd5d6564db0f304bf511a555c1d65ca6bccc8475fc979ff1d71d50680344c") { "Unexpected Samsung Sensor SDK archive" }
    }
}
android {
    namespace = "com.mani.orbit.wear"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.mani.orbit"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    sourceSets.getByName("main").res.srcDir("../../android/res")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    constraints { implementation("androidx.fragment:fragment:1.5.4") { because("Activity Result permissions require Fragment >= 1.3; Play services otherwise selects 1.1") } }
    implementation(project(":sync"))
    implementation(files(samsungSensorSdk.map { it.destinationDir.resolve("samsung-health-sensor-api-1.4.1.aar") }).builtBy(samsungSensorSdk))
    implementation("androidx.health:health-services-client:1.1.0-rc02")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.wear.tiles:tiles-renderer:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
