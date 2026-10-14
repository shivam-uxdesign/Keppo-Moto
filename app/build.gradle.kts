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
// Each build's number for Play: the commit count, so every new commit uploads as a newer version.
val commitCount: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.getOrElse(1)

// The Play upload key: keystore.properties (never committed) or env vars (environment secrets).
// Without it, release builds come out unsigned (fine for checking the build, not for Play).
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String): String? = (keystoreProperties.getProperty(key) ?: System.getenv("KEPPO_UPLOAD_${key.uppercase()}"))?.trim()?.takeIf { it.isNotEmpty() }
val uploadStoreFile: String? = signingValue("storeFile")

android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("roborazzi.test.record", "true") }
    }
    // Native libraries (ONNX Runtime for the voice detector is 33 MB raw) are stored compressed:
    // a much smaller download, unpacked once at install.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    namespace = "com.ridetrack.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.keppo.moto"
        minSdk = 26
        targetSdk = 36
        versionCode = commitCount
        versionName = "0.1.0"
        buildConfigField("String", "MAPTILER_KEY", "\"$mapTilerKey\"")
        buildConfigField("String", "BUILD_TAG", "\"$buildTag\"")
        // Only test builds carry the App Check debug token (a release must never: it would let
        // anyone use the Gemini allowance from outside the app).
        buildConfigField("String", "APPCHECK_DEBUG_TOKEN", "\"\"")
        buildConfigField("Boolean", "APPCHECK_DEBUG", "false")
        // Test builds (debug, releaseTest) show testing tools such as "Act as a free rider".
        buildConfigField("Boolean", "TEST_BUILD", "false")
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
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = rootProject.file(uploadStoreFile)
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
            }
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
        debug {
            buildConfigField("String", "APPCHECK_DEBUG_TOKEN", "\"$appCheckDebugToken\"")
            buildConfigField("Boolean", "APPCHECK_DEBUG", "true")
            buildConfigField("Boolean", "TEST_BUILD", "true")
        }
        // The Play build: shrunk, optimised and renamed by R8, unused resources dropped, signed
        // with the upload key (Play App Signing re-signs it for riders).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
        // The Play build to install by hand and test: the same shrinking as release, signed with
        // the debug key, and App Check's debug token so Gemini works outside the Play Store.
        create("releaseTest") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "APPCHECK_DEBUG_TOKEN", "\"$appCheckDebugToken\"")
            buildConfigField("Boolean", "APPCHECK_DEBUG", "true")
            buildConfigField("Boolean", "TEST_BUILD", "true")
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
    // Google Play Billing: the Pro subscription.
    implementation(libs.billing.ktx)
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
    // Screenshots of screens on the JVM (Robolectric + Roborazzi), to compare with the prototype.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.39.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.39.0")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
