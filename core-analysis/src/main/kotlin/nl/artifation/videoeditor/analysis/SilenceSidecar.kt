package nl.artifation.videoeditor.analysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.artifation.videoeditor.model.Us

/**
 * Het complete resultaat van de audio-analyse van één bronclip.
 *
 * Bestaat als eigen type omdat de stiltes en de loudness altijd samen gemeten
 * worden — allebei uit dezelfde gedecodeerde audio — en het zonde zou zijn om
 * daar twee keer een bestand voor te decoderen. Wat er in de sidecar belandt is
 * dus één ding, niet twee.
 */
@Serializable
public data class AudioAnalysis(
    val silences: List<SilenceInterval> = emptyList(),
    val loudness: LoudnessResult? = null,
    /** De samplerate waarop gemeten is; nodig om een meting te kunnen navertellen. */
    val sampleRate: Int = 0,
    /** Duur van het gedecodeerde materiaal. */
    val durationUs: Us = 0L,
) {
    /** De stukken die je wilt hóuden: het complement van de stiltes. */
    public fun keepIntervals(): List<LongRange> =
        SilenceDetector.keepIntervals(silences, durationUs)
}

/**
 * De sidecar-payload voor [nl.artifation.videoeditor.model.Us]-tijden.
 *
 * `:core-library` bewaart de payload als tekst en kijkt er bewust niet in — die
 * weet wélke analyses er zijn, niet wat erin staat. Het coderen hoort dus hier,
 * bij de analyse die het produceert, en niet in de app.
 */
public object AudioAnalysisJson {

    private val format: Json = Json {
        // Analyses zijn cache; een veld erbij mag een oude sidecar niet onleesbaar
        // maken. De versie in het sidecar-record bepaalt of hij nog geldig is.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun encode(analysis: AudioAnalysis): String = format.encodeToString(analysis)

    /** `null` bij onleesbare tekst: opnieuw analyseren is beter dan half interpreteren. */
    public fun decode(text: String): AudioAnalysis? =
        runCatching { format.decodeFromString<AudioAnalysis>(text) }.getOrNull()
}
