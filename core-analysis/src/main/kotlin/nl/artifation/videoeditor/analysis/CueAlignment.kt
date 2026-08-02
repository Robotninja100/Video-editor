package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.Cue
import nl.artifation.videoeditor.model.Us
import kotlin.math.abs

/**
 * Cue-grenzen bijstellen op de gemeten stiltes.
 *
 * Een transcriptiedienst levert tijden die kloppen op een tiende seconde na. Dat
 * valt in tekst niet op, maar in beeld wel: een ondertitel die een halve tel te
 * vroeg verschijnt, hoort zichtbaar bij de vorige zin. De stiltedetectie uit
 * fase 2 weet veel preciezer wáár er gesproken wordt, want die meet het signaal
 * zelf in plaats van het te herkennen.
 *
 * Dit is dus geen tweede detectie maar een correctie: elke grens mag een klein
 * stukje opschuiven naar het dichtstbijzijnde begin of einde van spraak. Ligt er
 * niets in de buurt, dan blijft de grens staan — liever ongewijzigd dan verkeerd
 * vastgezet.
 */
public data class AlignConfig(
    /** Verder dan dit wordt een grens nooit verschoven. */
    val maxShiftUs: Us = DEFAULT_MAX_SHIFT_US,
    /** Een correctie die een cue korter dan dit maakt, gaat niet door. */
    val minCueDurationUs: Us = DEFAULT_MIN_CUE_DURATION_US,
) {
    init {
        require(maxShiftUs >= 0L) { "maxShiftUs moet >= 0 zijn, was $maxShiftUs" }
        require(minCueDurationUs >= 0L) { "minCueDurationUs moet >= 0 zijn, was $minCueDurationUs" }
    }

    public companion object {
        /**
         * Ruim genoeg voor de onnauwkeurigheid van een transcriptie, te klein om
         * een grens naar de verkeerde zin te trekken.
         */
        public const val DEFAULT_MAX_SHIFT_US: Us = 300_000L

        public const val DEFAULT_MIN_CUE_DURATION_US: Us = 400_000L
    }
}

public object CueAlignment {

    /**
     * @param cues oplopend in tijd, zoals een transcriptie ze levert
     * @param silences de gemeten stiltes van dezelfde bronclip
     * @return dezelfde cues, met bijgestelde grenzen; nooit meer of minder cues
     */
    public fun align(
        cues: List<Cue>,
        silences: List<SilenceInterval>,
        config: AlignConfig = AlignConfig(),
    ): List<Cue> {
        if (cues.isEmpty() || silences.isEmpty()) return cues

        val sorted = silences.sortedBy { it.startUs }
        // Spraak begint waar een stilte ophoudt en houdt op waar er een begint.
        val speechStarts = sorted.map { it.endUs }
        val speechEnds = sorted.map { it.startUs }

        val aligned = ArrayList<Cue>(cues.size)
        var previousEndUs = Long.MIN_VALUE

        for (cue in cues) {
            val snapped = snap(cue, speechStarts, speechEnds, previousEndUs, config)
            aligned.add(snapped)
            previousEndUs = snapped.endUs
        }
        return aligned
    }

    /**
     * Probeert beide grenzen te verschuiven, maar houdt alleen wat de cue geldig
     * laat. Een correctie die de cue zou omdraaien of onleesbaar kort zou maken,
     * gaat gewoon niet door: het origineel was al bruikbaar.
     */
    private fun snap(
        cue: Cue,
        speechStarts: List<Us>,
        speechEnds: List<Us>,
        previousEndUs: Us,
        config: AlignConfig,
    ): Cue {
        val candidateStart = nearest(speechStarts, cue.startUs, config.maxShiftUs) ?: cue.startUs
        val candidateEnd = nearest(speechEnds, cue.endUs, config.maxShiftUs) ?: cue.endUs

        // Nooit over de vorige cue heen: die is al vastgesteld.
        val startUs = maxOf(candidateStart, previousEndUs)

        val (finalStart, finalEnd) = firstValidPair(
            cue = cue,
            options = listOf(
                startUs to candidateEnd,
                startUs to cue.endUs,
                cue.startUs to candidateEnd,
                maxOf(cue.startUs, previousEndUs) to cue.endUs,
            ),
            config = config,
        )

        if (finalStart == cue.startUs && finalEnd == cue.endUs) return cue

        return cue.copy(
            startUs = finalStart,
            endUs = finalEnd,
            words = clampWords(cue.words, finalStart, finalEnd),
        )
    }

    /**
     * De correcties op volgorde van voorkeur: eerst beide grenzen, dan elk apart,
     * en anders het origineel. De eerste die een geldige cue oplevert, wint.
     */
    private fun firstValidPair(
        cue: Cue,
        options: List<Pair<Us, Us>>,
        config: AlignConfig,
    ): Pair<Us, Us> {
        val originalDurationUs = cue.endUs - cue.startUs
        // Een cue die van zichzelf al kort is, mag daar niet op afgerekend worden.
        val floorUs = minOf(config.minCueDurationUs, originalDurationUs)

        return options.firstOrNull { (start, end) -> end - start >= floorUs && end > start }
            ?: (cue.startUs to cue.endUs)
    }

    /**
     * Woordtijden mee laten lopen met de nieuwe grenzen. Woorden die daardoor
     * helemaal buiten de cue vallen verdwijnen, zodat karaoke-highlighting geen
     * woord aanwijst dat niet meer in beeld is.
     */
    private fun clampWords(words: List<Cue.Word>, startUs: Us, endUs: Us): List<Cue.Word> =
        words.mapNotNull { word ->
            val wordStart = word.startUs.coerceIn(startUs, endUs)
            val wordEnd = word.endUs.coerceIn(startUs, endUs)
            if (wordEnd <= wordStart) null else word.copy(startUs = wordStart, endUs = wordEnd)
        }

    /** Het dichtstbijzijnde tijdstip binnen [maxShiftUs], of `null` als er niets in de buurt ligt. */
    private fun nearest(candidates: List<Us>, targetUs: Us, maxShiftUs: Us): Us? =
        candidates.minByOrNull { abs(it - targetUs) }?.takeIf { abs(it - targetUs) <= maxShiftUs }
}
