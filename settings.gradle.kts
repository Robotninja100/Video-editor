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
include(":core-pipeline")

// De Android-modules doen alleen mee als er een SDK is.
//
// Voorwaarde is `sdk.dir` in local.properties — dat is precies het bestand dat
// Android Studio zelf aanmaakt — of `-PmetAndroid` op de opdrachtregel. Zonder
// die twee blijven ze buiten de build, zodat `./gradlew test` blijft werken op
// een machine zonder Android SDK. Dat is geen theoretisch geval: de pure-JVM
// modules zijn in precies zo'n omgeving geschreven, en de CI-jobs voor analyse
// en tests draaien er nog steeds zo.
//
// Bewust niet op `ANDROID_HOME` kijken: op een GitHub-runner staat die altijd,
// ook in de jobs die alleen de JVM-modules moeten bouwen.
val lokaal = java.util.Properties().apply {
    File(settingsDir, "local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val metAndroid = lokaal.getProperty("sdk.dir") != null ||
    startParameter.projectProperties.containsKey("metAndroid")

if (metAndroid) {
    include(":core-render")
    include(":app")
} else {
    logger.lifecycle(
        "Android-modules overgeslagen: geen sdk.dir in local.properties en geen -PmetAndroid.",
    )
}
