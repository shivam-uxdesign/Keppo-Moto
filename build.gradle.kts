buildscript {
    // The Android Gradle Plugin is put on the root classpath (rather than declared in
    // the plugins block) so it can be skipped when building the core module alone.
    if (providers.gradleProperty("coreOnly").orNull != "true") {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath(libs.android.gradle.plugin)
            classpath(libs.google.services.plugin)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
