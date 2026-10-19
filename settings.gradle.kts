pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.firebase.*")
                includeGroupByRegex("com\\.google\\.gms.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.firebase.*")
                includeGroupByRegex("com\\.google\\.gms.*")
                // ML Kit (on-phone picture labels for moment keywords).
                includeGroupByRegex("com\\.google\\.mlkit.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "RideTrack"

include(":core:telemetry")

// `-PcoreOnly=true` builds only the pure-Kotlin telemetry core, which is useful on
// machines without the Android SDK (the core has no Android dependencies).
if (providers.gradleProperty("coreOnly").orNull != "true") {
    include(":app")
}
