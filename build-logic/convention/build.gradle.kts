import java.util.Properties

plugins {
    `kotlin-dsl`
}

// De conventieplugins zijn zelf Kotlin-scripts en worden door Gradle
// gecompileerd. Ze draaien in de Gradle-JVM, dus 21 net als de modules.
kotlin {
    jvmToolchain(21)
}

/**
 * Dezelfde voorwaarde als in de hoofd-settings: doen de Android-modules mee?
 *
 * `rootDir` is hier de map van deze ingesloten build, dus local.properties van
 * het hoofdproject staat er één niveau boven.
 */
val metAndroid: Boolean = run {
    val lokaal = Properties().apply {
        File(rootDir.parentFile, "local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { load(it) }
    }
    lokaal.getProperty("sdk.dir") != null || providers.gradleProperty("metAndroid").isPresent
}

dependencies {
    // `implementation` en niet `compileOnly`: een voorgecompileerde
    // scriptplugin die `plugins { id(...) }` gebruikt heeft de plugin ook
    // tijdens het uitvoeren van de build nodig.
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.serializationGradlePlugin)
    implementation(libs.detekt.gradlePlugin)
    implementation(libs.kover.gradlePlugin)

    // De Android-plugins horen híer en niet in app/build.gradle.kts.
    //
    // Deze ingesloten build staat in `pluginManagement` van het hoofdproject,
    // dus alles wat hier `implementation` is, staat op het pluginklassenpad van
    // élke module — inclusief de Kotlin-plugin, en zonder herkenbare versie.
    // Daardoor liep het op twee manieren stuk:
    //
    // - `alias(libs.plugins.kotlin.android)` mét versie werd geweigerd: "already
    //   on the classpath with an unknown version".
    // - `id("org.jetbrains.kotlin.android")` zonder versie werd wél geaccepteerd,
    //   maar laadde de Kotlin-plugin uit déze classloader, waar AGP niet in zit.
    //   Resultaat: NoClassDefFoundError op com/android/build/gradle/api/BaseVariant.
    //
    // De Kotlin-plugin en AGP moeten elkaar kunnen zien, dus horen ze in dezelfde
    // classloader. Dat is deze. Achter de SDK-voorwaarde, zodat een machine
    // zonder Android SDK ze niet hoeft op te halen.
    if (metAndroid) {
        implementation(libs.android.gradlePlugin)
        implementation(libs.compose.compilerGradlePlugin)
    }
}
