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
     *
     * @param mediaDurationUs de duur van het materiaal, als die bekend is. Tijden
     *   buiten dat bereik worden geklemd; zonder deze waarde valt een absurde
     *   eindtijd van de dienst niet te herkennen.
     */
    public fun parse(body: String, mediaDurationUs: Long? = null): Transcript {
        val response = json.decodeFromString<ApiResponse>(body)
        val allWords = response.words.map { it.toWord(mediaDurationUs) }

        if (response.segments.isEmpty()) {
            // Sommige antwoorden bevatten alleen woorden; maak er dan één segment van.
            if (allWords.isEmpty()) return Transcript()
            return Transcript(
                listOf(
                    TranscriptSegment(
                        index = 0,
                        // min/max en niet first/last: de volgorde van de lijst is
                        // niets waar de dienst zich aan verbonden heeft, en één
                        // omgedraaid paar gaf een segment met negatieve duur.
                        startUs = allWords.minOf { it.startUs },
                        endUs = allWords.maxOf { it.endUs },
                        text = response.text.ifBlank { allWords.joinToString(" ") { it.text } },
                        words = allWords,
                    ),
                ),
            )
        }

        val bounds = response.segments.map { segment ->
            val startUs = segment.start.toUs().clampToMedia(mediaDurationUs)
            startUs to maxOf(segment.end.toUs().clampToMedia(mediaDurationUs), startUs)
        }
        val perSegment = allWords.groupBy { nearestSegment(it.startUs, bounds) }

        return Transcript(
            segments = bounds.mapIndexed { index, (startUs, endUs) ->
                TranscriptSegment(
                    index = index,
                    startUs = startUs,
                    endUs = endUs,
                    text = response.segments[index].text.trim(),
                    words = perSegment[index].orEmpty(),
                )
            },
        )
    }

    /**
     * Het segment waar een woord bij hoort: het segment dat het woord bevat, en
     * anders het dichtstbijzijnde.
     *
     * Alleen kijken naar wat er binnen een segment begint klinkt redelijk, maar
     * Whisper legt zijn segmentgrenzen juist óp de pauzes. Er zit dus standaard
     * een gat tussen twee segmenten, en het eerste woord begint routineus een
     * fractie vóór het segment. Zulke woorden verdwenen zonder enig signaal:
     * de segmenttekst noemde ze nog, maar er was geen woordtiming meer om op te
     * highlighten of op te knippen.
     */
    private fun nearestSegment(atUs: Long, bounds: List<Pair<Long, Long>>): Int {
        var best = 0
        var bestDistance = Long.MAX_VALUE
        bounds.forEachIndexed { index, (startUs, endUs) ->
            // Halfopen: een woord precies op de grens hoort bij het volgende
            // segment, zodat aansluitende segmenten het niet allebei opeisen.
            val end = maxOf(endUs, startUs + 1)
            val distance = when {
                atUs < startUs -> startUs - atUs
                atUs < end -> 0L
                else -> atUs - end + 1
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /**
     * Tijden die niet kunnen. Een dienst mag zich verrekenen; de tijdlijn mag
     * er niet op stukgaan. Zonder bekende mediaduur blijft alleen "niet negatief"
     * en "einde niet vóór begin" over.
     */
    private fun Long.clampToMedia(mediaDurationUs: Long?): Long =
        if (mediaDurationUs == null) coerceAtLeast(0L) else coerceIn(0L, mediaDurationUs)

    private fun ApiWord.toWord(mediaDurationUs: Long?): Cue.Word {
        val startUs = start.toUs().clampToMedia(mediaDurationUs)
        return Cue.Word(
            startUs = startUs,
            endUs = maxOf(end.toUs().clampToMedia(mediaDurationUs), startUs),
            text = word.trim(),
        )
    }

    /** De dienst antwoordt in seconden, de rest van dit project rekent in microseconden. */
    private const val US_PER_SECOND = 1_000_000.0

    private fun Double.toUs(): Long = (this * US_PER_SECOND).roundToLong()
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

    /**
     * Een woord dat in een gemeten stilte begint, schuift naar het einde daarvan.
     *
     * Drie dingen blijven daarbij overeind, en die zijn met opzet genoemd omdat
     * de vorige versie ze alle drie brak:
     *
     * 1. **Duur.** Het woord schuift, het krimpt niet. Eerder werd alleen het
     *    begin verzet en het einde meegetrokken tot precies datzelfde punt, wat
     *    woorden van nul lengte opleverde: onhighlightbaar en niet aan te wijzen.
     * 2. **Volgorde.** Twee woorden die in dezelfde stilte beginnen kwamen op
     *    exact hetzelfde tijdstip terecht en waren daarna niet meer uit elkaar te
     *    houden. De teller loopt daarom over het hele transcript door, ook over
     *    segmentgrenzen heen.
     * 3. **Samenhang met het segment.** Een verzet woord kon buiten zijn eigen
     *    segment belanden. De segmentgrenzen groeien nu mee met hun woorden — de
     *    woorden zijn de meting, de segmentgrens is de samenvatting.
     */
    public fun align(transcript: Transcript, silences: List<Silence>): Transcript {
        if (silences.isEmpty()) return transcript
        val sorted = silences.sortedBy { it.startUs }
        var cursorUs = Long.MIN_VALUE

        val segments = transcript.segments.map { segment ->
            val words = segment.words.map { word ->
                val durationUs = (word.endUs - word.startUs).coerceAtLeast(0L)
                val silence = sorted.firstOrNull { word.startUs >= it.startUs && word.startUs < it.endUs }
                val startUs = maxOf(silence?.endUs ?: word.startUs, cursorUs)
                cursorUs = startUs + durationUs
                word.copy(startUs = startUs, endUs = cursorUs)
            }
            if (words.isEmpty()) {
                segment
            } else {
                segment.copy(
                    startUs = minOf(segment.startUs, words.first().startUs),
                    endUs = maxOf(segment.endUs, words.last().endUs),
                    words = words,
                )
            }
        }
        return transcript.copy(segments = segments)
    }
}
