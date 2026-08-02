package nl.artifation.videoeditor.analysis.autoedit

import nl.artifation.videoeditor.model.Cue

/**
 * Wat er van een voorstel van het taalmodel is overgebleven, en wat niet.
 *
 * De verworpen indices worden apart bijgehouden in plaats van stil weggelaten: als
 * een model structureel buiten bereik gokt, wil je dat zien, niet stilzwijgend een
 * halve montage krijgen.
 */
public data class IndexSelection(
    val kept: List<Int>,
    val outOfRange: List<Int> = emptyList(),
    val duplicates: List<Int> = emptyList(),
) {
    public val hasRejections: Boolean get() = outOfRange.isNotEmpty() || duplicates.isNotEmpty()
}

/**
 * Auto-edit: het transcript in, een selectie regels uit.
 *
 * De hele reden dat dit met **indices** werkt en niet met tijdstempels: een taalmodel
 * dat tijden mag noemen, kan tijden verzinnen die nergens op slaan, en dan moet je
 * achteraf controleren of ze bestaan. Met regelnummers kan dat per constructie niet —
 * een index verwijst naar een cue of hij bestaat niet, en dat laatste is hier één
 * vergelijking.
 *
 * Bewust zonder netwerkcode: de aanroep naar de Claude-API hoort in `:app`, zodat
 * deze module puur en testbaar blijft.
 */
public object Transcript {

    /**
     * Het transcript zoals het in de prompt gaat: één regel per cue, genummerd vanaf
     * nul, zonder tijden. Regeleindes binnen een cue worden platgeslagen, anders
     * lopen de nummers in de prompt niet meer in de pas met de regels.
     */
    public fun numbered(cues: List<Cue>): String =
        cues.mapIndexed { index, cue -> "$index: ${cue.text.replace(Regex("\\s+"), " ").trim()}" }
            .joinToString("\n")

    /**
     * Haalt de indices uit de ruwe modeluitvoer.
     *
     * Eerst de eerste blokhaken proberen: dat is het formaat dat de prompt vraagt, en
     * daarmee blijven inleidende zinnen en codefences vanzelf buiten schot. Staat er
     * geen lijst in, dan worden alle hele getallen in de tekst genomen — beter iets
     * bruikbaars dan niets, want de validatie erna vangt de rommel toch af.
     */
    public fun parseIndices(raw: String): List<Int> {
        val list = BRACKETED.find(raw)?.groupValues?.get(1) ?: raw
        return NUMBER.findAll(list).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    /** Gooit weg wat niet naar een cue verwijst, ontdubbelt en sorteert. */
    public fun validate(indices: List<Int>, cueCount: Int): IndexSelection {
        require(cueCount >= 0) { "cueCount moet >= 0 zijn" }

        val kept = mutableListOf<Int>()
        val outOfRange = mutableListOf<Int>()
        val duplicates = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()

        for (index in indices) {
            when {
                index !in 0 until cueCount -> outOfRange.add(index)
                !seen.add(index) -> duplicates.add(index)
                else -> kept.add(index)
            }
        }

        return IndexSelection(
            kept = kept.sorted(),
            outOfRange = outOfRange,
            duplicates = duplicates,
        )
    }

    /** Ruwe modeluitvoer rechtstreeks naar een gecontroleerde selectie. */
    public fun select(raw: String, cueCount: Int): IndexSelection =
        validate(parseIndices(raw), cueCount)

    /**
     * De geselecteerde regels als tijdsintervallen, waarbij regels die op elkaar
     * volgen tot één interval samensmelten.
     *
     * Dezelfde vorm als [nl.artifation.videoeditor.analysis.SilenceDetector.keepIntervals],
     * zodat auto-edit en auto-knippen langs dezelfde weg naar clips gaan.
     */
    public fun toKeepIntervals(selection: IndexSelection, cues: List<Cue>): List<LongRange> {
        val intervals = mutableListOf<LongRange>()
        var runStart: Int? = null
        var runEnd = 0

        fun close() {
            val start = runStart ?: return
            val from = cues[start].startUs
            val to = cues[runEnd].endUs
            if (to > from) intervals.add(from until to)
            runStart = null
        }

        for (index in selection.kept) {
            if (index !in cues.indices) continue
            if (runStart != null && index == runEnd + 1) {
                runEnd = index
            } else {
                close()
                runStart = index
                runEnd = index
            }
        }
        close()

        return intervals
    }

    private val BRACKETED = Regex("""\[([^\[\]]*)]""")
    private val NUMBER = Regex("""-?\d+""")
}
