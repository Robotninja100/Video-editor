import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * De gedeelde bouwconfiguratie van elke pure-JVM-module in dit project.
 *
 * Alles wat in negen modulebestanden hetzelfde hoorde te zijn staat hier één
 * keer: de Kotlin-toolchain, de expliciete API-modus, de testafhankelijkheden,
 * JUnit 5, statische analyse en dekkingsmeting. Een modulebestand houdt daarmee
 * alleen nog zijn eigen afhankelijkheden over.
 *
 * Modules die `@Serializable` gebruiken passen daarnaast
 * `videoeditor.kotlin-library-serialization` toe.
 */

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Expliciete API-modus, standaard streng.
 *
 * De bestaande code bleek er al helemaal op ingericht: bij het aanzetten kwam
 * er geen enkele overtreding uit, in geen van de negen modules. Streng dus, en
 * niet waarschuwend — anders zakt het langzaam weer weg.
 *
 * Wie tijdens het schrijven even ruimte wil, draait het per opdracht terug met
 * `-Pvideoeditor.explicitApi.strict=false`; dan waarschuwt de compiler alleen.
 * In CI staat de vlag niet, dus daar is het altijd streng.
 */
val strictExplicitApi: Boolean =
    providers.gradleProperty("videoeditor.explicitApi.strict").orNull?.toBooleanStrictOrNull() ?: true

extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(21)
    if (strictExplicitApi) {
        explicitApi()
    } else {
        explicitApiWarning()
    }
}

dependencies {
    "testImplementation"(kotlin("test"))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())

    // Brengt de ktlint-opmaakregels binnen detekt. Eén gereedschap, één
    // basislijn, één stap in CI.
    "detektPlugins"(libs.findLibrary("detekt-formatting").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
    }
    // De JUnit-XML's zijn wat CI omzet naar de samenvatting bij een pull request.
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }
}

extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    // Per module een eigen basislijn: `detektBaseline` schrijft er precies één
    // per project, dus één gedeeld bestand zouden de modules over elkaar heen
    // schrijven.
    baseline = rootProject.layout.projectDirectory
        .file("config/detekt/baseline-${project.name}.xml")
        .asFile
        .takeIf { it.exists() }
    basePath = rootProject.projectDir.absolutePath
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
        md.required.set(false)
        txt.required.set(false)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = "21"
    baseline.set(
        rootProject.layout.projectDirectory.file("config/detekt/baseline-${project.name}.xml"),
    )
}
