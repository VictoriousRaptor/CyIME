pluginManagement {
    repositories {
        // Resolve Android plugins from Google Maven before consulting the Plugin Portal.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        providers.environmentVariable("CI_MAVEN_CENTRAL_URL").orNull?.let { maven(it) }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        providers.environmentVariable("CI_MAVEN_CENTRAL_URL").orNull?.let { maven(it) }
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "CyIME"
include(":app")
include(":plugin-core")
