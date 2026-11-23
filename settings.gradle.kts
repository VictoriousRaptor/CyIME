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
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.environmentVariable("ROBOLECTRIC_MAVEN_URL").orNull?.let { mirror ->
            exclusiveContent {
                forRepository { maven(mirror) }
                filter { includeGroup("org.robolectric") }
            }
        }
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "CyIME"
include(":app")
include(":plugin-core")
