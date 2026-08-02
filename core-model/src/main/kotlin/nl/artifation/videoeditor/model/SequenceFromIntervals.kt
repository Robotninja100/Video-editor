package nl.artifation.videoeditor.model

/**
 * Van "welke stukken houden we" naar een tijdlijn.
 *
 * Dit is de schakel tussen analyse en montage. Stiltedetectie en auto-edit
 * leveren allebei hetzelfde: een lijst intervallen uit het bronmateriaal die
 * moeten blijven. Wat er daarna gebeurt is voor beide identiek, dus staat het
 * hier één keer en niet twee keer bij de aanroeper.
 *
 * De intervallen worden gesorteerd en samengevoegd voordat er clips van worden
 * gemaakt. Overlappende intervallen — twee analyses die elkaar deels dekken —
 * zouden anders hetzelfde beeld twee keer op de tijdlijn zetten.
 *
 * @param intervals bronintervallen in microseconden; leeg levert een lege sequence
 * @param idPrefix elke clip krijgt `"$idPrefix-<n>"`, zodat dezelfde invoer altijd
 *   dezelfde ids oplevert — dat scheelt bij diffs en maakt tests leesbaar
 */
public fun sequenceFromIntervals(
    id: String,
    sourceUri: String,
    intervals: List<LongRange>,
    idPrefix: String = "clip",
): Sequence {
    val merged = mergeOverlapping(intervals)

    return Sequence(
        id = id,
        items = merged.mapIndexed { index, interval ->
            Clip(
                id = "$idPrefix-$index",
                sourceUri = sourceUri,
                inPointUs = interval.first,
                // `LongRange` is inclusief aan beide kanten, `outPointUs` is dat
                // niet. Zonder deze plus één verdwijnt er per knip één microseconde.
                outPointUs = interval.last + 1,
            )
        },
    )
}

/**
 * Sorteert en plakt intervallen die elkaar raken of overlappen aan elkaar.
 *
 * Aansluitende intervallen worden ook samengevoegd: twee clips die naadloos op
 * elkaar volgen zijn hetzelfde beeld als één clip, maar met een knip erin die
 * niemand gevraagd heeft.
 */
internal fun mergeOverlapping(intervals: List<LongRange>): List<LongRange> {
    val usable = intervals.filter { !it.isEmpty() }.sortedBy { it.first }
    if (usable.isEmpty()) return emptyList()

    val merged = mutableListOf<LongRange>()
    var current = usable.first()

    for (interval in usable.drop(1)) {
        current = if (interval.first <= current.last + 1) {
            current.first..maxOf(current.last, interval.last)
        } else {
            merged.add(current)
            interval
        }
    }
    merged.add(current)

    return merged
}
