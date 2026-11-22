import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedShimAssetsDir = layout.buildDirectory.dir("generated/shim-assets")

android {
    namespace = "com.sandboxforge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sandboxforge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-shim"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedShimAssetsDir)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/**",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val buildShimDex by tasks.registering {
    dependsOn(":shim:bundleReleaseAar")
    val aarFile = project(":shim").layout.buildDirectory.file("outputs/aar/shim-release.aar")
    val outDir = generatedShimAssetsDir
    inputs.file(aarFile)
    outputs.dir(outDir)

    doLast {
        val aar = aarFile.get().asFile
        require(aar.exists()) { "Missing shim AAR at ${aar.absolutePath}" }

        val workDir = layout.buildDirectory.dir("intermediates/shim-dex-work").get().asFile
        workDir.mkdirs()
        val classesJar = File(workDir, "classes.jar")

        ZipFile(aar).use { zip ->
            val entry = zip.getEntry("classes.jar")
                ?: error("classes.jar not found inside ${aar.name}")
            zip.getInputStream(entry).use { input ->
                classesJar.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val sdkDir = android.sdkDirectory
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val d8Name = if (isWindows) "d8.bat" else "d8"
        val buildToolsDir = File(sdkDir, "build-tools")
        val d8Executable = buildToolsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { File(it, d8Name) }
            ?.firstOrNull { it.exists() }
            ?: error("Android SDK d8 tool not found under ${buildToolsDir.absolutePath}")

        val androidJar = File(sdkDir, "platforms/android-35/android.jar")
        val dexOutDir = File(workDir, "dex-out").apply {
            deleteRecursively()
            mkdirs()
        }

        val cmd = mutableListOf(
            d8Executable.absolutePath,
            "--min-api", "26",
            "--output", dexOutDir.absolutePath
        )
        if (androidJar.exists()) {
            cmd.add("--lib")
            cmd.add(androidJar.absolutePath)
        }
        cmd.add(classesJar.absolutePath)

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val outputText = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("d8 failed with exit code $exitCode:\n$outputText")
        }

        val generatedDex = File(dexOutDir, "classes.dex")
        require(generatedDex.exists()) { "d8 did not produce classes.dex" }

        val targetAssetsDir = outDir.get().asFile.apply { mkdirs() }
        generatedDex.copyTo(File(targetAssetsDir, "sandbox_shim.dex"), overwrite = true)
    }
}

tasks.named("preBuild").configure {
    dependsOn(buildShimDex)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Binary AndroidManifest.xml patching + APK v1/v2/v3 signing + local per-device cert generation
    implementation("io.github.reandroid:ARSCLib:1.4.0")
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
