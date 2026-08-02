plugins {
    // Voegt de dekkingsmeting van alle modules samen tot één rapport.
    id("videoeditor.coverage-aggregation")
}

/**
 * Eén opdracht die alles draait wat een pull request moet halen.
 *
 * Handig lokaal, en het houdt CI en de werkplek gelijk: wat hier slaagt, slaagt
 * daar ook.
 */
tasks.register("controle") {
    group = "verification"
    description = "Draait statische analyse, alle tests en het dekkingsrapport."
    // Filteren op wat er echt is, niet op paden die er zouden moeten zijn.
    //
    // De Android-modules gebruiken de conventieplugins niet en kennen dus geen
    // detekt-taak. Met een vaste lijst paden ("${it.path}:detekt") faalde deze
    // opdracht met "task not found" zodra iemand met een Android SDK bouwde —
    // en dat is precies de machine waarop je hem wilt draaien. `matching` is
    // een levende verzameling: leeg als de taak niet bestaat.
    dependsOn(
        provider {
            subprojects
                .filter { it.plugins.hasPlugin("io.gitlab.arturbosch.detekt") }
                .flatMap { listOf("${it.path}:detekt", "${it.path}:test") }
        },
    )
    dependsOn("koverXmlReport", "koverVerify")
}
