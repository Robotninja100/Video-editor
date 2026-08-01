pluginManagement {
    // De conventieplugins (`videoeditor.*`) komen uit deze ingesloten build.
    includeBuild("build-logic")

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
include(":core-remote")
include(":core-design")
include(":core-library")
include(":core-project")
include(":core-jobs")
include(":core-errors")
include(":core-thermal")

// Android-modules: broncode staat in de repo, maar is nooit gecompileerd —
// de omgeving waarin die geschreven is had geen Android SDK. Aanzetten door
// hun build.gradle.kts.disabled te hernoemen en deze regels te ontkommentariëren.
// Zie README.md en docs/BOUWPLAN.md.
// include(":core-render")
// include(":app")
