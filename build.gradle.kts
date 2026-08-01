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
    dependsOn(
        subprojects.map { "${it.path}:detekt" },
        subprojects.map { "${it.path}:test" },
        "koverXmlReport",
        "koverVerify",
    )
}
