package nl.artifation.videoeditor.jobs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.artifation.videoeditor.errors.EditorError
import nl.artifation.videoeditor.model.Us

/**
 * Een langlopende taak: analyse, transcriptie, segmentatie of export.
 *
 * Deze taken duren minuten en moeten een proces-herstart overleven, dus is de
 * hele taak serialiseerbaar en bevat hij álles wat nodig is om het werk opnieuw
 * of verder op te pakken. Er zit bewust geen callback, thread of Android-type in:
 * de wachtrij is een pure toestandsmachine die door een uitvoerder wordt gedreven.
 */
@Serializable
public data class Job(
    val id: String,
    val kind: JobKind,
    /** Tijdstip van indienen; bepaalt de volgorde binnen dezelfde prioriteit. */
    val enqueuedAtUs: Us,
    val priority: JobPriority = JobPriority.Normal,
    val state: JobState = JobState.Queued,
    /** Aantal keren dat de taak is gestárt, inclusief de poging die nu loopt. */
    val attempts: Int = 0,
    /**
     * Het pogingenbudget van déze taak, en daarmee het laatste woord over hoe
     * vaak er nog geprobeerd wordt.
     *
     * `RetryPolicy` kent ook een `maxAttempts`, maar die van de taak wint: hoe
     * lang je ergens op blijft hameren hoort bij het werk (een export waar de
     * gebruiker op wacht mag koppiger zijn dan een achtergrondanalyse), terwijl
     * de policy alleen de vórm van de wachttijd bepaalt. Zie [JobQueue].
     */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /**
     * Niet vóór dit tijdstip opnieuw kiezen; null betekent meteen.
     *
     * Zo blijft de backoff staan op de taak in plaats van in een timer ergens
     * buiten de wachtrij: hij overleeft daardoor het opslaan en is voor de UI
     * gewoon af te lezen ("nieuwe poging over 12 s").
     */
    val notBeforeUs: Us? = null,
    /** Voortgang van deze taak in `0f..1f`. */
    val progress: Float = 0f,
    /**
     * Geschatte hoeveelheid rekenwerk. Alleen gebruikt als gewicht in de
     * samengevoegde voortgang — een grove schatting is genoeg, zolang de
     * verhouding tussen taken klopt.
     */
    val estimatedWorkUs: Us = kind.estimatedWorkUs,
    /**
     * Ondoorzichtig bewaarpunt van de uitvoerder, bijvoorbeeld "tot hier is de
     * export geschreven". De wachtrij interpreteert het nooit, maar gebruikt de
     * aanwezigheid ervan wél: zonder bewaarpunt begint een nieuwe poging bij nul.
     */
    val resumeToken: String? = null,
    val startedAtUs: Us? = null,
    val finishedAtUs: Us? = null,
    /**
     * De laatste fout, ook als de taak daarna opnieuw in de wachtrij kwam.
     *
     * Een [EditorError] en geen vrije tekst: die tekst belandt vroeg of laat op
     * het scherm, en dan leest de gebruiker een logregel. Nu draagt de taak zelf
     * een stabiele code voor het log én een Nederlandse zin voor de gebruiker,
     * en overleeft dat het opslaan — na een herstart is nog te zien waaróp het
     * de vorige keer misging.
     */
    val lastError: EditorError? = null,
) {
    init {
        require(id.isNotBlank()) { "id mag niet leeg zijn" }
        require(enqueuedAtUs >= 0) { "enqueuedAtUs moet >= 0 zijn, was $enqueuedAtUs" }
        require(maxAttempts >= 1) { "maxAttempts moet >= 1 zijn, was $maxAttempts" }
        require(notBeforeUs == null || notBeforeUs >= 0) { "notBeforeUs moet >= 0 zijn, was $notBeforeUs" }
        require(attempts >= 0) { "attempts moet >= 0 zijn, was $attempts" }
        require(progress in 0f..1f) { "progress moet in 0f..1f liggen, was $progress" }
        require(estimatedWorkUs > 0) { "estimatedWorkUs moet positief zijn, was $estimatedWorkUs" }
    }

    public val isTerminal: Boolean get() = state.isTerminal

    /** Of er na de huidige poging nog een poging over is. */
    public val canRetry: Boolean get() = attempts < maxAttempts

    /**
     * Of de taak op [nowUs] gekozen mag worden, of nog in zijn backoff zit.
     *
     * Zegt niets over de toestand: een gepauzeerde taak is ook "due" en wordt
     * toch niet gekozen.
     */
    public fun isDue(nowUs: Us): Boolean = notBeforeUs == null || nowUs >= notBeforeUs

    /**
     * Voert een toestandsovergang uit en houdt de bijbehorende administratie bij.
     *
     * Een ongeldige overgang gooit; stil negeren zou betekenen dat een afgeronde
     * taak opnieuw kan gaan draaien en het dubbele werk pas veel later opvalt.
     */
    public fun withState(to: JobState, nowUs: Us): Job {
        if (!JobStateMachine.isAllowed(state, to)) throw IllegalJobTransition(id, state, to)
        return when (to) {
            // Het tellen gebeurt bij de start, niet bij de fout: een poging die het
            // proces onderuit haalt wordt zo ook geteld.
            JobState.Running -> copy(
                state = to,
                attempts = attempts + 1,
                startedAtUs = nowUs,
                finishedAtUs = null,
                // De wachttijd is opgebruikt zodra de poging begint; hem laten
                // staan zou een tijdstip uit het verleden achterlaten dat de UI
                // als "wacht nog" zou kunnen lezen.
                notBeforeUs = null,
            )
            JobState.Queued -> copy(state = to, finishedAtUs = null)
            JobState.Paused -> copy(state = to)
            JobState.Succeeded -> copy(state = to, progress = 1f, resumeToken = null, finishedAtUs = nowUs)
            JobState.Failed, JobState.Cancelled -> copy(state = to, finishedAtUs = nowUs)
        }
    }

    public companion object {
        /** Drie pogingen: genoeg voor een hik in het netwerk, weinig genoeg om niet te blijven hangen. */
        public const val DEFAULT_MAX_ATTEMPTS: Int = 3
    }
}

/**
 * Hoger wint bij het kiezen van de volgende taak.
 *
 * Een export waar de gebruiker op wacht hoort boven achtergrondanalyse van clips
 * die hij misschien nooit gebruikt.
 */
@Serializable
public enum class JobPriority {
    Low,
    Normal,
    High,
    ;

    /** Expliciet, zodat het gedrag niet aan de volgorde van de enum hangt. */
    public val weight: Int get() = ordinal
}

/**
 * Wat er te doen valt, plus de gegevens die nodig zijn om het na een herstart
 * opnieuw te kunnen doen. Verwijzingen zijn URI's als tekst — geen Android-typen,
 * want deze module is pure JVM.
 */
@Serializable
public sealed interface JobKind {

    /** Standaardgewicht voor de samengevoegde voortgang. */
    public val estimatedWorkUs: Us

    /** Lokale DSP: stiltes en scenegrenzen van één bronclip. */
    @Serializable
    @SerialName("clip-analyse")
    public data class ClipAnalysis(
        val sourceUri: String,
        val clipDurationUs: Us,
    ) : JobKind {
        // Eén keer door de audio en de framehistogrammen heen is ruwweg
        // achtmaal sneller dan realtime.
        override val estimatedWorkUs: Us get() = maxOf(1L, clipDurationUs / 8)
    }

    /** Transcriptie in de cloud; de duur zit vooral in uploaden. */
    @Serializable
    @SerialName("transcriptie")
    public data class Transcription(
        val sourceUri: String,
        val clipDurationUs: Us,
        val language: String? = null,
    ) : JobKind {
        override val estimatedWorkUs: Us get() = maxOf(1L, clipDurationUs / 4)
    }

    /** Segmentatie per frame in de cloud; schaalt met het aantal frames, niet met de duur. */
    @Serializable
    @SerialName("segmentatie")
    public data class Segmentation(
        val sourceUri: String,
        val frameCount: Int,
    ) : JobKind {
        override val estimatedWorkUs: Us get() = maxOf(1L, frameCount * US_PER_SEGMENTED_FRAME)
    }

    /** De export zelf: encoden is het zwaarste dat het toestel doet. */
    @Serializable
    @SerialName("export")
    public data class Export(
        val projectId: String,
        val outputUri: String,
        val outputDurationUs: Us,
    ) : JobKind {
        override val estimatedWorkUs: Us get() = maxOf(1L, outputDurationUs / 2)
    }

    public companion object {
        /** Ruwe kostprijs van één gesegmenteerd frame, inclusief upload. */
        public const val US_PER_SEGMENTED_FRAME: Long = 300_000L
    }
}
