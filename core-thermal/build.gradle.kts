plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // Deze module hangt bewust van geen enkele andere module af; alleen de
    // serialisatie-runtime is nodig voor de bewaarbare toestand (@Serializable).
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }
