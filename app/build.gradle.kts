// Doet alleen mee als er een Android SDK is; zie settings.gradle.kts.

plugins {
    alias(libs.plugins.android.application)
    // Bewust `id(...)` zonder versie, en geen alias.
    //
    // `build-logic` is een ingesloten build in pluginManagement en heeft
    // kotlin-gradle-plugin als `implementation`, dus de Kotlin-plugin staat al
    // op het klassenpad van deze build — zonder herkenbare versie. Een tweede
    // verzoek mét versienummer weigert Gradle dan: "already on the classpath
    // with an unknown version, so compatibility cannot be checked". Zonder
    // versie pakt hij wat er staat, en dat is per definitie dezelfde Kotlin als
    // de rest van het project gebruikt.
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "nl.artifation.videoeditor"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.artifation.videoeditor"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
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
    implementation(project(":core-model"))
    implementation(project(":core-analysis"))
    implementation(project(":core-remote"))
    implementation(project(":core-design"))
    implementation(project(":core-render"))

    // `Player` en `CompositionPlayer`. :core-render exporteert deze twee al met
    // `api`, maar :app gebruikt ze rechtstreeks en zegt dat hier dus zelf.
    implementation("androidx.media3:media3-common:1.10.1")
    implementation("androidx.media3:media3-transformer:1.10.1")

    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Compose brengt coroutines transitief mee, maar EditorActivity gebruikt
    // Dispatchers.IO rechtstreeks — dan hoort de afhankelijkheid hier te staan.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.mlkit:face-detection:16.1.7")

    implementation("io.ktor:ktor-client-okhttp:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
}
