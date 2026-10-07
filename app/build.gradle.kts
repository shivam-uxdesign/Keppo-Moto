import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Firebase (transcripts via AI Logic) needs app/google-services.json, which is kept out of git.
// Without it the app still builds; "Write down what I say" then says it isn't available.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

// MapTiler key: local.properties (never committed) or the MAPTILER_KEY env var (CI secret).
// Empty = the app falls back to a keyless basemap.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val mapTilerKey: String = (localProperties.getProperty("MAPTILER_KEY") ?: System.getenv("MAPTILER_KEY") ?: "").trim()
// Test builds: one fixed App Check debug token (registered once in Firebase), kept out of git,
// so reinstalling the app doesn't make a new one that Firebase rejects.
val appCheckDebugToken: String = (localProperties.getProperty("APPCHECK_DEBUG_TOKEN") ?: System.getenv("APPCHECK_DEBUG_TOKEN") ?: "").trim()

// Which build this is, shown in Profile › About: git commit + build date (e.g. "ad42a92 · 2 Oct").
val gitSha: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.getOrElse("").ifEmpty { "local" }
val buildTag: String = "$gitSha · " + LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM", Locale.US))

android {
    // Native libraries (ONNX Runtime for the voice detector is 33 MB raw) are stored compressed:
    // a much smaller download, unpacked once at install.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    namespace = "com.ridetrack.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.keppo.moto"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "MAPTILER_KEY", "\"$mapTilerKey\"")
        buildConfigField("String", "BUILD_TAG", "\"$buildTag\"")
        buildConfigField("String", "APPCHECK_DEBUG_TOKEN", "\"$appCheckDebugToken\"")
        // Optional: `-PabiFilter=arm64-v8a` builds a smaller APK for modern phones only.
        providers.gradleProperty("abiFilter").orNull?.let { abis ->
            ndk { abiFilters += abis.split(",").map(String::trim) }
        }
    }

    signingConfigs {
        // A fixed debug key shared by local and CI builds, so a new test APK installs over the
        // previous one and keeps the rider's data. Debug-only; never use it for a store release.
        getByName("debug") {
            storeFile = file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // `-PshrinkDebug=true`: drop unused library code so the phone APK fits a chat
        // upload. Our own classes are all kept and nothing is renamed or optimised.
        if (providers.gradleProperty("shrinkDebug").orNull == "true") {
            debug {
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-debug-shrink.pro")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:telemetry"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.maplibre.android)
    implementation(libs.androidx.camera.core)
    // Gemini transcripts through Firebase AI Logic, guarded by App Check.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.firebase.config)
    implementation(libs.firebase.appcheck.debug)
    implementation(libs.firebase.appcheck.playintegrity)
    // Silero voice detector for "Film when I speak".
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // Media3's own MP4 muxer: Studio tries it first (some phones' MediaMuxer rejects mixed clips).
    implementation(libs.androidx.media3.muxer)

    // Google Drive backup: Drive access through Play services; uploads run as WorkManager jobs.
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
    // org.json ships with Android; unit tests on the JVM need the real implementation.
    testImplementation(libs.org.json)
}
