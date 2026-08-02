package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.Transcript
import nl.artifation.videoeditor.model.TranscriptSegment
import nl.artifation.videoeditor.model.Us

/**
 * Vertaalt de selectie van de LLM naar een cut-list.
 *
 * De LLM krijgt een genummerd transcript en geeft **indices** terug, geen tijden.
 * Alles wat daar niet aan voldoet wordt hier weggegooid: een model dat een index
 * verzint die niet bestaat, mag nooit een edit produceren.
 */
public object CutList {

    public data class Result(
        /** De geselecteerde segmenten, ontdubbeld en op volgorde. */
        val kept: List<TranscriptSegment>,
        /** Indices die niet bestonden in het transcript. */
        val rejected: List<Int>,
        /**
         * Indices die het model meer dan één keer noemde.
         *
         * Apart van [rejected], want het is een ander soort fout: een onbekende
         * index is een verzinsel, een dubbele is een model dat zichzelf herhaalt.
         * Het resultaat is in beide gevallen bruikbaar, maar wie er structureel
         * één ziet oplopen wil dat weten in plaats van stilzwijgend een selectie
         * krijgen die korter is dan het model dacht te geven.
         */
        val duplicates: List<Int> = emptyList(),
    ) {
        val hasRejections: Boolean get() = rejected.isNotEmpty() || duplicates.isNotEmpty()
    }

    /**
     * Valideert [indices] tegen [transcript].
     *
     * Onbekende indices verdwijnen naar [Result.rejected], herhalingen naar
     * [Result.duplicates]. De volgorde wordt genormaliseerd naar tijdvolgorde — de
     * LLM mag geen segmenten omdraaien, dat levert onbedoelde jump-cuts op.
     */
    public fun fromIndices(transcript: Transcript, indices: List<Int>): Result {
        val byIndex = transcript.segments.associateBy { it.index }
        val rejected = mutableListOf<Int>()
        val duplicates = mutableListOf<Int>()
        val kept = linkedSetOf<Int>()

        for (index in indices) {
            when {
                !byIndex.containsKey(index) -> rejected.add(index)
                !kept.add(index) -> duplicates.add(index)
            }
        }

        return Result(
            kept = kept.mapNotNull { byIndex[it] }.sortedBy { it.startUs },
            rejected = rejected,
            duplicates = duplicates,
        )
    }

    /**
     * Voegt de geselecteerde segmenten samen tot intervallen.
     *
     * Aaneengesloten segmenten worden één interval, zodat je geen reeks
     * onnodige splits op de tijdlijn krijgt. [gapToleranceUs] overbrugt kleine
     * gaatjes tussen segmenten die in de praktijk aan elkaar vast zitten.
     */
    public fun toIntervals(
        segments: List<TranscriptSegment>,
        gapToleranceUs: Us = 200_000L,
    ): List<LongRange> {
        if (segments.isEmpty()) return emptyList()

        val sorted = segments.sortedBy { it.startUs }
        val out = mutableListOf<LongRange>()
        var start = sorted.first().startUs
        var end = sorted.first().endUs

        for (segment in sorted.drop(1)) {
            if (segment.startUs - end <= gapToleranceUs) {
                end = maxOf(end, segment.endUs)
            } else {
                out.add(start until end)
                start = segment.startUs
                end = segment.endUs
            }
        }
        out.add(start until end)
        return out
    }
}
