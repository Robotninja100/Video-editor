package nl.artifation.videoeditor.analysis

import nl.artifation.videoeditor.model.Cue
import nl.artifation.videoeditor.model.Transcript
import nl.artifation.videoeditor.model.Us

/**
 * Van transcript naar leesbare ondertitelblokken.
 *
 * Een transcript is een woordenstroom; ondertitels zijn blokken met regels die
 * lang genoeg in beeld staan om te lezen. Dit is puur tekstindeling — geen AI —
 * en daarmee volledig hier te testen.
 */
public data class CaptionConfig(
    /** Maximaal aantal tekens per regel. Boven ~40 wordt het onleesbaar op telefoon. */
    val maxCharsPerLine: Int = 32,
    val maxLines: Int = 2,
    /** Een cue korter dan dit is niet te lezen; hij wordt verlengd als er ruimte is. */
    val minDurationUs: Us = 800_000L,
    /** Langer dan dit wordt opgeknipt, ook als de tekst past. */
    val maxDurationUs: Us = 5_000_000L,
    /** Een pauze langer dan dit forceert een nieuw blok. */
    val splitOnGapUs: Us = 600_000L,
) {
    init {
        require(maxCharsPerLine > 0) { "maxCharsPerLine moet positief zijn" }
        require(maxLines > 0) { "maxLines moet positief zijn" }
        require(maxDurationUs > 0L) { "maxDurationUs moet positief zijn" }
    }

    val maxChars: Int get() = maxCharsPerLine * maxLines
}

public object CaptionLayout {

    /** Bouwt cues uit alle woorden in het transcript. */
    public fun fromTranscript(
        transcript: Transcript,
        config: CaptionConfig = CaptionConfig(),
    ): List<Cue> = fromWords(transcript.segments.flatMap { it.words }, config)

    public fun fromWords(
        words: List<Cue.Word>,
        config: CaptionConfig = CaptionConfig(),
    ): List<Cue> {
        if (words.isEmpty()) return emptyList()

        val sorted = words.sortedBy { it.startUs }
        val cues = mutableListOf<Cue>()
        var group = mutableListOf<Cue.Word>()

        fun flush() {
            if (group.isNotEmpty()) {
                cues.add(toCue(group, config))
                group = mutableListOf()
            }
        }

        for (word in sorted) {
            if (group.isNotEmpty()) {
                val candidateChars = group.sumOf { it.text.length + 1 } + word.text.length
                val gapUs = word.startUs - group.last().endUs
                val spanUs = word.endUs - group.first().startUs

                if (candidateChars > config.maxChars ||
                    gapUs > config.splitOnGapUs ||
                    spanUs > config.maxDurationUs
                ) {
                    flush()
                }
            }
            group.add(word)
        }
        flush()

        return enforceMinDuration(cues, config)
    }

    /**
     * Breekt de tekst af op woordgrenzen. Nooit midden in een woord — dat leest
     * als een fout, ook al past het.
     */
    public fun wrap(text: String, maxCharsPerLine: Int): List<String> {
        val lines = mutableListOf<String>()
        var line = StringBuilder()

        for (word in text.split(" ").filter { it.isNotBlank() }) {
            when {
                line.isEmpty() -> line.append(word)
                line.length + 1 + word.length <= maxCharsPerLine -> line.append(' ').append(word)
                else -> {
                    lines.add(line.toString())
                    line = StringBuilder(word)
                }
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    private fun toCue(words: List<Cue.Word>, config: CaptionConfig): Cue {
        val text = words.joinToString(" ") { it.text }
        return Cue(
            startUs = words.first().startUs,
            endUs = words.last().endUs,
            text = wrap(text, config.maxCharsPerLine).joinToString("\n"),
            words = words,
        )
    }

    /**
     * Verlengt te korte cues, maar nooit tot over de volgende heen — overlappende
     * ondertitels zijn erger dan een korte.
     */
    private fun enforceMinDuration(cues: List<Cue>, config: CaptionConfig): List<Cue> =
        cues.mapIndexed { index, cue ->
            if (cue.endUs - cue.startUs >= config.minDurationUs) return@mapIndexed cue

            val ceiling = cues.getOrNull(index + 1)?.startUs ?: Long.MAX_VALUE
            val wanted = cue.startUs + config.minDurationUs
            cue.copy(endUs = minOf(wanted, ceiling).coerceAtLeast(cue.endUs))
        }
}
