import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

/**
 * Dekkingsmeting over alle modules samen, toegepast op het hoofdproject.
 *
 * Elke module past Kover zelf toe via [videoeditor.kotlin-library]; hier worden
 * die metingen samengevoegd tot één rapport. `./gradlew :koverXmlReport` levert
 * het bestand dat CI in de samenvatting zet, `:koverHtmlReport` het rapport om
 * zelf in te kijken.
 */

plugins {
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
    // Alleen modules die Kover zelf toepassen tellen mee. `withId` vuurt op het
    // moment dat een module de plugin aanzet, dus de Android-modules — die de
    // conventieplugin niet gebruiken en geen Kover kennen — vallen er vanzelf
    // buiten in plaats van de aggregatie te laten struikelen.
    subprojects.forEach { module ->
        module.plugins.withId("org.jetbrains.kotlinx.kover") {
            add("kover", module)
        }
    }
}

kover {
    reports {
        filters {
            excludes {
                // Gegenereerde serializers zeggen niets over de dekking van
                // handgeschreven code.
                classes("*\$\$serializer")
            }
        }

        // De rapporten hangen bewust niet aan `check`: `./gradlew test` hoort
        // snel te blijven. CI en de `controle`-opdracht vragen ze expliciet aan
        // via `:koverXmlReport`, wat de standaardinstelling van Kover al is.

        verify {
            // De regeldekking staat op het moment van instellen op ruim 95%
            // (97% als je de gegenereerde serializers meetelt). De ondergrens
            // ligt daar bewust onder: hij moet een echte terugval betrappen,
            // niet klagen over een enkele ongeteste regel. Vertakkingsdekking
            // staat lager (ongeveer 66%) en is hier nog geen eis; dat is werk
            // voor een volgende ronde.
            rule("Regeldekking over alle modules") {
                bound {
                    minValue.set(90)
                    coverageUnits.set(CoverageUnit.LINE)
                    aggregationForGroup.set(AggregationType.COVERED_PERCENTAGE)
                }
            }
        }
    }
}
