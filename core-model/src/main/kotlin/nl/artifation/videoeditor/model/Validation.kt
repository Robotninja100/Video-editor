package nl.artifation.videoeditor.model

/**
 * Modelvalidatie.
 *
 * Het doel is om fouten hier te vangen in plaats van pas bij export, waar ze
 * als onbegrijpelijke Media3-excepties naar boven komen.
 */
public data class Problem(
    val path: String,
    val message: String,
)

public fun Project.validate(): List<Problem> = buildList {
    if (id.isBlank()) add(Problem("project.id", "id mag niet leeg zijn"))
    if (sequences.isEmpty()) add(Problem("project.sequences", "minstens één sequence vereist"))

    sequences.groupBy { it.id }
        .filterValues { it.size > 1 }
        .keys
        .forEach { add(Problem("project.sequences", "dubbele sequence-id '$it'")) }

    sequences.forEachIndexed { index, sequence ->
        addAll(sequence.validate("project.sequences[$index]"))
    }

    addAll(outputSpec.validate("project.outputSpec"))
}

public fun Sequence.validate(path: String = "sequence"): List<Problem> = buildList {
    if (id.isBlank()) add(Problem("$path.id", "id mag niet leeg zijn"))

    items.forEachIndexed { index, item ->
        val itemPath = "$path.items[$index]"
        when (item) {
            is Gap -> if (item.durationUs <= 0L) {
                add(Problem(itemPath, "gap moet een positieve duur hebben, was ${item.durationUs}"))
            }

            is Clip -> {
                if (item.id.isBlank()) add(Problem("$itemPath.id", "clip-id mag niet leeg zijn"))
                if (item.sourceUri.isBlank()) {
                    add(Problem("$itemPath.sourceUri", "sourceUri mag niet leeg zijn"))
                }
                if (item.inPointUs < 0L) {
                    add(Problem("$itemPath.inPointUs", "inPoint moet >= 0 zijn, was ${item.inPointUs}"))
                }
                if (item.outPointUs <= item.inPointUs) {
                    add(
                        Problem(
                            "$itemPath.outPointUs",
                            "outPoint (${item.outPointUs}) moet groter zijn dan inPoint (${item.inPointUs})",
                        ),
                    )
                }
                if (item.speed <= 0f || !item.speed.isFinite()) {
                    add(Problem("$itemPath.speed", "speed moet positief en eindig zijn, was ${item.speed}"))
                }
            }
        }
    }
}

public fun OutputSpec.validate(path: String = "outputSpec"): List<Problem> = buildList {
    if (width <= 0) add(Problem("$path.width", "width moet positief zijn, was $width"))
    if (height <= 0) add(Problem("$path.height", "height moet positief zijn, was $height"))
    if (frameRate <= 0) add(Problem("$path.frameRate", "frameRate moet positief zijn, was $frameRate"))

    // Encoders willen vrijwel altijd even afmetingen (chroma subsampling).
    if (width % 2 != 0) add(Problem("$path.width", "width moet even zijn, was $width"))
    if (height % 2 != 0) add(Problem("$path.height", "height moet even zijn, was $height"))
}
