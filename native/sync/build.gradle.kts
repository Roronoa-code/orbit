plugins { id("com.android.library") }
android {
    namespace = "com.mani.orbit.sync"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { implementation("androidx.metrics:metrics-performance:1.0.0") }
