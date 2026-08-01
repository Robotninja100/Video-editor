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

// Android-modules worden toegevoegd zodra de Android SDK beschikbaar is:
//   :app  :core-render  :ml-whisper  :ml-tracking
// Zie docs/BOUWPLAN.md.
