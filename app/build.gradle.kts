plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "nl.artifation.videoeditor"
    compileSdk = 36

    defaultConfig {
        applicationId = "nl.artifation.videoeditor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-spike"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        debug {
            // De spike moet op een echt toestel draaien zonder ondertekensleutel.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Debug-sleutel, zodat CI een installeerbare release-APK kan afleveren
            // zolang er nog geen echte sleutel is. Vóór publicatie vervangen.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint {
        // Zie :core-render — CompositionPlayer en Transformer zijn @UnstableApi,
        // en die staan hier in het hart van preview en export.
        disable += "UnsafeOptInUsageError"
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-analysis"))
    implementation(project(":core-render"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.ui)
    implementation(libs.media3.exoplayer)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
