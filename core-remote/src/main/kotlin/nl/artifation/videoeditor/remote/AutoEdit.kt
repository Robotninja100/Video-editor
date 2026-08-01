package nl.artifation.videoeditor.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.artifation.videoeditor.model.Transcript

/**
 * Auto-edit: transcript in, selectie uit.
 *
 * Het model selecteert **indices** uit een genummerd transcript en verzint nooit
 * tijden. Dat is geen stijlkeuze maar de kern van de betrouwbaarheid: een
 * verzonnen index bestaat niet en valt weg bij validatie, terwijl een verzonnen
 * timestamp er plausibel uitziet en stilzwijgend een verkeerde edit oplevert.
 */
public object AutoEdit {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    public data class Options(
        /** Waar de montage op moet sturen. */
        val instruction: String = "Selecteer de fragmenten die samen een heldere, " +
            "boeiende korte video vormen. Behoud de kernargumenten en gooi herhaling, " +
            "uitweidingen en versprekingen weg.",
        val targetDurationSeconds: Int? = null,
        val model: String = "claude-opus-5",
        val maxTokens: Int = 4_096,
    )

    /**
     * Bouwt de prompt. Het genummerde transcript is het enige wat het model te
     * zien krijgt, zodat het niets anders kán teruggeven dan indices.
     */
    public fun buildPrompt(transcript: Transcript, options: Options = Options()): String {
        val duration = options.targetDurationSeconds
            ?.let { "\n\nMik op ongeveer $it seconden totale speelduur." }
            ?: ""

        return """
            |${options.instruction}$duration
            |
            |Hieronder staat een transcript. Elk fragment heeft een nummer tussen blokhaken.
            |
            |Antwoord uitsluitend met JSON in deze vorm, zonder toelichting eromheen:
            |{"segments": [{"index": 12, "reason": "kernargument"}]}
            |
            |Regels:
            |- Gebruik alleen nummers die hierboven voorkomen.
            |- Verzin geen tijden; alleen nummers tellen.
            |- Houd de oorspronkelijke volgorde aan.
            |
            |Transcript:
            |${transcript.numbered()}
        """.trimMargin()
    }

    @Serializable
    private data class ApiSelection(val segments: List<ApiSegment> = emptyList())

    @Serializable
    private data class ApiSegment(val index: Int, val reason: String = "")

    public data class Selection(
        val indices: List<Int>,
        val reasons: Map<Int, String>,
    )

    /**
     * Parseert het antwoord.
     *
     * Modellen zetten JSON graag in een ```json-blok of laten er een zin omheen
     * staan, ook als je vraagt dat niet te doen. In plaats van daarop te
     * vertrouwen wordt het buitenste JSON-object eruit gesneden. Is er geen
     * bruikbare JSON, dan is het resultaat leeg — nooit een gok.
     */
    public fun parse(body: String): Selection {
        val payload = extractJsonObject(body) ?: return Selection(emptyList(), emptyMap())

        val selection = runCatching { json.decodeFromString<ApiSelection>(payload) }
            .getOrElse { return Selection(emptyList(), emptyMap()) }

        return Selection(
            indices = selection.segments.map { it.index },
            reasons = selection.segments.associate { it.index to it.reason },
        )
    }

    /** Snijdt het eerste complete JSON-object eruit, met respect voor strings. */
    internal fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                char == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }
}
