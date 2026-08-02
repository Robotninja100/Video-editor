pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "video-editor"

// Pure-JVM modules: bouwen en testen zonder Android SDK of toestel.
include(":core-model")
include(":core-analysis")

// Android-modules. Vereisen de Android SDK; zie README.md §"Bouwen".
include(":core-render")
include(":app")

// Nog niet aangemaakt; die wachten op de NDK (fase 3 en 6):
//   :ml-whisper  :ml-tracking
// Zie docs/BOUWPLAN.md.
