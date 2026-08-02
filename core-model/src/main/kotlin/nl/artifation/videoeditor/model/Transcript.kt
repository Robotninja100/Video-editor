package nl.artifation.videoeditor.model

import kotlinx.serialization.Serializable

/**
 * Het transcript-sidecarbestand.
 *
 * Dit is bewust een backend-neutraal contract: of het transcript nu van een
 * cloud-dienst komt of van een lokaal model, het formaat is identiek. Daardoor is
 * "lokaal of cloud" een implementatiekeuze en geen architectuurkeuze.
 */
@Serializable
public data class Transcript(
    val segments: List<TranscriptSegment> = emptyList(),
) {
    val durationUs: Us get() = segments.maxOfOrNull { it.endUs } ?: 0L

    /**
     * Rendert het transcript genummerd, zoals het naar de LLM gaat voor auto-edit.
     *
     * De nummering is het hele punt: de LLM selecteert **indices**, nooit tijden.
     * Daarmee kan hij per constructie geen timestamps verzinnen — de klasse fouten
     * waar je anders elke terugkomende tijd tegen het transcript moet valideren.
     */
    public fun numbered(): String =
        segments.joinToString("\n") { "[${it.index}] ${it.text}" }
}

@Serializable
public data class TranscriptSegment(
    val index: Int,
    val startUs: Us,
    val endUs: Us,
    val text: String,
    val words: List<Cue.Word> = emptyList(),
) {
    val durationUs: Us get() = endUs - startUs
}
