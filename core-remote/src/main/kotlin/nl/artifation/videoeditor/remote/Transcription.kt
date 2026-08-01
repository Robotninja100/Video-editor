package nl.artifation.videoeditor.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.artifation.videoeditor.model.Cue
import nl.artifation.videoeditor.model.Transcript
import nl.artifation.videoeditor.model.TranscriptSegment
import kotlin.math.roundToLong

/**
 * Transcriptie via een Whisper-compatibele dienst (Groq `whisper-large-v3-turbo`).
 *
 * Alleen het contract en de vertaling staan hier — geen HTTP-client. Daardoor is
 * de kwetsbare kant (parsen van een antwoord dat je niet in de hand hebt) hier
 * volledig te testen, zonder netwerk en zonder sleutel.
 */
public object Transcription {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Wat er in de request meegaat, los van het multipart-audiobestand. */
    public data class Request(
        val model: String = "whisper-large-v3-turbo",
        /** ISO 639-1, of null om te laten detecteren. Expliciet zetten is nauwkeuriger. */
        val language: String? = null,
        val responseFormat: String = "verbose_json",
        val timestampGranularities: List<String> = listOf("segment", "word"),
    ) {
        public fun asFormFields(): Map<String, String> = buildMap {
            put("model", model)
            put("response_format", responseFormat)
            language?.let { put("language", it) }
            timestampGranularities.forEachIndexed { index, granularity ->
                put("timestamp_granularities[$index]", granularity)
            }
        }
    }

    @Serializable
    private data class ApiResponse(
        val text: String = "",
        val segments: List<ApiSegment> = emptyList(),
        val words: List<ApiWord> = emptyList(),
    )

    @Serializable
    private data class ApiSegment(
        val id: Int = 0,
        val start: Double = 0.0,
        val end: Double = 0.0,
        val text: String = "",
    )

    @Serializable
    private data class ApiWord(
        val word: String = "",
        val start: Double = 0.0,
        val end: Double = 0.0,
    )

    /**
     * Vertaalt het API-antwoord naar het interne sidecar-formaat.
     *
     * Woorden worden aan segmenten toegewezen op basis van hun tijdstip, want de
     * API levert ze als losse lijsten. Segmenten worden hernummerd vanaf 0 — de
     * auto-edit rekent op aaneengesloten indices en de API garandeert dat niet.
     */
    public fun parse(body: String): Transcript {
        val response = json.decodeFromString<ApiResponse>(body)

        if (response.segments.isEmpty()) {
            // Sommige antwoorden bevatten alleen woorden; maak er dan één segment van.
            if (response.words.isEmpty()) return Transcript()
            val words = response.words.map { it.toWord() }
            return Transcript(
                listOf(
                    TranscriptSegment(
                        index = 0,
                        startUs = words.first().startUs,
                        endUs = words.last().endUs,
                        text = response.text.ifBlank { words.joinToString(" ") { it.text } },
                        words = words,
                    ),
                ),
            )
        }

        val allWords = response.words.map { it.toWord() }

        return Transcript(
            segments = response.segments.mapIndexed { index, segment ->
                val startUs = segment.start.toUs()
                val endUs = segment.end.toUs()
                TranscriptSegment(
                    index = index,
                    startUs = startUs,
                    endUs = endUs,
                    text = segment.text.trim(),
                    words = allWords.filter { it.startUs in startUs until maxOf(endUs, startUs + 1) },
                )
            },
        )
    }

    private fun ApiWord.toWord() = Cue.Word(
        startUs = start.toUs(),
        endUs = end.toUs(),
        text = word.trim(),
    )

    private fun Double.toUs(): Long = (this * 1_000_000.0).roundToLong()
}

/**
 * Corrigeert woordtijden tegen gedetecteerde stiltes.
 *
 * Whisper-timestamps op woordniveau lopen een fractie los. Een woord dat volgens
 * het transcript midden in een gemeten stilte begint, klopt niet: schuif het naar
 * het einde van die stilte. Dat is het verschil tussen ondertitels die net naast
 * de spraak lopen en ondertitels die synchroon zijn.
 */
public object TimestampAlignment {

    public data class Silence(val startUs: Long, val endUs: Long)

    public fun align(transcript: Transcript, silences: List<Silence>): Transcript {
        if (silences.isEmpty()) return transcript
        val sorted = silences.sortedBy { it.startUs }

        return transcript.copy(
            segments = transcript.segments.map { segment ->
                segment.copy(words = segment.words.map { it.nudged(sorted) })
            },
        )
    }

    private fun Cue.Word.nudged(silences: List<Silence>): Cue.Word {
        val containing = silences.firstOrNull { startUs >= it.startUs && startUs < it.endUs }
            ?: return this
        val shifted = containing.endUs
        // Nooit korter dan nul maken: het einde schuift mee als dat nodig is.
        return copy(startUs = shifted, endUs = maxOf(endUs, shifted))
    }
}
