plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core-model"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // De loudness-tests draaien de referentiesignalen uit EBU Tech 3341: 80 seconden
    // audio op 48 kHz, in Double door twee filtertrappen. Expliciet ingesteld zodat
    // een bouwmachine met weinig geheugen niet stilletjes omvalt.
    maxHeapSize = "1g"
}
