plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core-model"))

    // Eén foutenvocabulaire voor de hele app: elke fout draagt een stabiele
    // code, een retryable-vlag en een tekst die de gebruiker snapt.
    api(project(":core-errors"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }
