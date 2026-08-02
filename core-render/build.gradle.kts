plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "nl.artifation.videoeditor.render"
    compileSdk = 36

    defaultConfig {
        // 26 in plaats van Media3's eigen 23: de maskdecoder gebruikt
        // MediaCodec-API's die pas vanaf Oreo betrouwbaar zijn.
        minSdk = 26
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

    lint {
        // Deze module bestáát uit Transformer- en effect-code, en die staat bij
        // Media3 vrijwel volledig als @UnstableApi gemarkeerd. Elke regel apart
        // annoteren levert honderden annotaties op zonder dat er iets veiliger van
        // wordt. De echte maatregel staat in libs.versions.toml: Media3 is
        // vastgepind op één versie, zodat een upgrade een bewuste stap is.
        disable += "UnsafeOptInUsageError"
        warningsAsErrors = true
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Deze module is de publieke grens richting :app; expliciete zichtbaarheid
        // voorkomt dat interne hulpklassen ongemerkt API worden.
        explicitApi()
    }
}

dependencies {
    api(project(":core-model"))

    api(libs.media3.common)
    api(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.exoplayer)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
}
