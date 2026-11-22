plugins {
    id("com.android.library")
}

android {
    namespace = "com.sandboxforge.shim"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Intentionally zero external dependencies so sandbox_shim.dex is ~6 KB
// and never collides with any guest app's Kotlin/AndroidX libraries.
dependencies {
}
