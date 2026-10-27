pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.1.0"
        id("org.jetbrains.kotlin.jvm") version "2.1.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "md-reader"

include(":core")
// -PcoreOnly builds just the pure-Kotlin text module (no Android SDK needed).
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
