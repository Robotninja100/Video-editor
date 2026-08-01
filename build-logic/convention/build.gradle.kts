plugins {
    `kotlin-dsl`
}

// De conventieplugins zijn zelf Kotlin-scripts en worden door Gradle
// gecompileerd. Ze draaien in de Gradle-JVM, dus 21 net als de modules.
kotlin {
    jvmToolchain(21)
}

dependencies {
    // `implementation` en niet `compileOnly`: een voorgecompileerde
    // scriptplugin die `plugins { id(...) }` gebruikt heeft de plugin ook
    // tijdens het uitvoeren van de build nodig.
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.serializationGradlePlugin)
    implementation(libs.detekt.gradlePlugin)
    implementation(libs.kover.gradlePlugin)
}
