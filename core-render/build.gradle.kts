// Doet alleen mee als er een Android SDK is; zie settings.gradle.kts.

plugins {
    // Alle plugins zonder versie: ze staan al op het klassenpad via
    // build-logic, en juist dáár moeten ze vandaan komen. Zie
    // build-logic/convention/build.gradle.kts voor waarom AGP en de
    // Kotlin-plugin in dezelfde classloader horen.
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.artifation.videoeditor.render"
    compileSdk = 36

    defaultConfig {
        minSdk = 31 // RenderEffect en moderne MediaCodec-paden
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")
}

dependencies {
    api(project(":core-model"))

    // Gepind: CompositionPlayer is @ExperimentalApi en breekt tussen versies.
    // Herbeoordeel bewust, niet als bijvangst van een upgrade.
    //
    // `api` en niet `implementation` voor transformer en common: CompositionMapper
    // geeft een `Composition` terug, dus dat type staat in de publieke API van
    // deze module. Met `implementation` staat het niet op het compileerpad van
    // :app en is de mapper daar niet aan te roepen.
    api("androidx.media3:media3-transformer:1.10.1")
    api("androidx.media3:media3-common:1.10.1")

    // Alleen intern gebruikt, door MaskedBlurShaderProgram.
    implementation("androidx.media3:media3-effect:1.10.1")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
}
