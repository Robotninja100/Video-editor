package nl.artifation.videoeditor.errors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * De diensten waar de editor van afhangt.
 *
 * Een gesloten lijst met Nederlandse labels in plaats van vrije strings: zo kan
 * er nooit een merknaam, een endpoint of een sleutel in een gebruikersmelding
 * belanden, en hoeft de melding niet per dienst opnieuw bedacht te worden.
 */
@Serializable
public enum class RemoteService(public val label: String) {
    TRANSCRIPTION("de ondertiteldienst"),
    SEGMENTATION("de objectherkenning"),
    AUTO_EDIT("de montage-assistent"),
}

/**
 * Waar in het werk iets misging.
 *
 * De labels zijn zo geschreven dat ze als onderwerp van een zin werken:
 * "Het exporteren van je video is gestopt."
 */
@Serializable
public enum class Stage(public val label: String) {
    IMPORT("Het toevoegen van je materiaal"),
    ANALYSIS("De analyse van je materiaal"),
    TRANSCRIPTION("Het maken van de ondertitels"),
    EDIT("De montage"),
    EXPORT("Het exporteren van je video"),
    SAVE("Het opslaan van je project"),
}

/**
 * Invoer die de editor niet kan gebruiken.
 *
 * Ook dit is een gesloten lijst met kant-en-klare teksten. Zou [EditorError.InvalidInput]
 * een vrije string dragen, dan zou vroeg of laat een technische melding
 * ("index out of range") als gebruikerstekst op het scherm komen.
 */
@Serializable
public enum class InputProblem(public val message: String) {
    EMPTY_TIMELINE("De tijdlijn is nog leeg. Zet eerst een clip op de tijdlijn en probeer het opnieuw."),
    SELECTION_TOO_SHORT("De selectie is korter dan één beeld. Maak de selectie iets langer en probeer het opnieuw."),
    REVERSED_RANGE("Het eindpunt ligt voor het beginpunt. Sleep de uiteinden goed en probeer het opnieuw."),
    FILE_TOO_LARGE(
        "Het bestand is te groot voor deze bewerking. " +
            "Knip het eerst in kortere stukken en probeer het opnieuw.",
    ),
    UNSUPPORTED_LANGUAGE(
        "Deze taal kan nog niet automatisch ondertiteld worden. " +
            "Kies een andere taal en probeer het opnieuw.",
    ),
    NO_AUDIO_TRACK("Dit materiaal heeft geen geluid, dus er valt niets te ondertitelen. Kies een clip met geluid."),
    UNRESOLVED_RECOVERY(
        "Dit project heeft nog wijzigingen van een vorige keer die niet zijn afgerond. " +
            "Open het project eerst en kies wat je daarmee wilt.",
    ),
}

/**
 * Alles wat in deze editor mis kan gaan, als gesloten taxonomie.
 *
 * Gesloten, omdat elke plek die een fout afhandelt — de wachtrij, het scherm,
 * de logregel — moet kunnen rekenen op precies drie dingen: een stabiele
 * [code], een eerlijk antwoord op "heeft opnieuw proberen zin" ([retryable]) en
 * een [userMessage] die zonder vertaalslag op het scherm kan.
 *
 * De klasse is `@Serializable` zodat een mislukte taak met fout en al in de
 * wachtrij op schijf kan blijven staan tot het toestel weer wil.
 */
@Serializable
public sealed interface EditorError {

    /**
     * Stabiele sleutel voor logs en telemetrie.
     *
     * Los van de tekst: de tekst mag herschreven worden zonder dat oude logs
     * onvergelijkbaar worden.
     */
    public val code: String

    /**
     * Of dezelfde poging later kans van slagen heeft.
     *
     * Dit is het onderscheid waar de wachtrij op draait: een netwerkstoring
     * verdwijnt vanzelf, een kapot bronbestand niet. Zonder dit verschil blijft
     * een wachtrij eindeloos hameren op werk dat nooit gaat lukken.
     */
    public val retryable: Boolean

    /**
     * Nederlandse tekst voor de gebruiker: wat er misging en wat hij eraan kan doen.
     *
     * Geen excuses, geen uitroeptekens, geen techniek. De tekst noemt hooguit
     * een bestandsnaam — nooit een pad, een statuscode of een sleutel.
     */
    public val userMessage: String

    /** De opslag zit vol. */
    @Serializable
    @SerialName("storage_full")
    public data class OutOfStorage(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : EditorError {
        init {
            require(requiredBytes >= 0L) { "requiredBytes moet >= 0 zijn, was $requiredBytes" }
            require(availableBytes >= 0L) { "availableBytes moet >= 0 zijn, was $availableBytes" }
        }

        /** Wat er minstens bij moet. */
        val shortfallBytes: Long get() = (requiredBytes - availableBytes).coerceAtLeast(0L)

        override val code: String get() = "storage_full"

        /** Opruimen is mensenwerk; nog eens proberen levert dezelfde volle schijf op. */
        override val retryable: Boolean get() = false

        override val userMessage: String
            get() {
                val advies = if (shortfallBytes < MB) "wat ruimte" else "ongeveer ${formatBytes(shortfallBytes)}"
                return "Er is geen ruimte meer op je toestel. Maak $advies vrij en probeer het opnieuw."
            }
    }

    /** Het bronbestand staat niet meer waar het stond. */
    @Serializable
    @SerialName("file_missing")
    public data class FileMissing(val path: String) : EditorError {
        override val code: String get() = "file_missing"
        override val retryable: Boolean get() = false
        override val userMessage: String
            get() = "Het bestand ${fileNameOf(path)} staat niet meer op deze plek. " +
                "Kies het opnieuw of haal het terug uit je galerij."
    }

    /** Het bestand is er wel, maar gaat niet open of is halverwege afgebroken. */
    @Serializable
    @SerialName("file_unreadable")
    public data class FileUnreadable(
        val path: String,
        val detail: String? = null,
    ) : EditorError {
        override val code: String get() = "file_unreadable"
        override val retryable: Boolean get() = false
        override val userMessage: String
            get() = "Het bestand ${fileNameOf(path)} kan niet gelezen worden. " +
                "Kopieer het opnieuw naar je toestel en probeer het daarna nog eens."
    }

    /** Het toestel kan dit materiaal niet openen: de decoder wil het niet. */
    @Serializable
    @SerialName("unsupported_media")
    public data class UnsupportedMedia(
        val path: String,
        /** Technische hint voor de logregel, bijvoorbeeld "hevc" of "prores". Nooit in de tekst. */
        val formatHint: String? = null,
    ) : EditorError {
        override val code: String get() = "unsupported_media"
        override val retryable: Boolean get() = false
        override val userMessage: String
            get() = "Het bestand ${fileNameOf(path)} staat in een vorm die dit toestel niet kan openen. " +
                "Zet het om naar MP4 en voeg het daarna opnieuw toe."
    }

    /**
     * Een projectbestand is beschadigd of afgebroken.
     *
     * Bewust niet [FileUnreadable]: die raadt aan het bestand opnieuw naar het
     * toestel te kopiëren, en een project komt nergens vandaan — het staat alleen
     * hier. De handeling is een eerdere versie openen of opnieuw beginnen.
     */
    @Serializable
    @SerialName("project_damaged")
    public data class ProjectDamaged(val detail: String? = null) : EditorError {
        override val code: String get() = "project_damaged"
        override val retryable: Boolean get() = false
        override val userMessage: String
            get() = "Dit project kan niet geopend worden omdat het bestand beschadigd is. " +
                "Open een eerdere versie of begin een nieuw project."
    }

    /**
     * Het bestand vraagt om een nieuwere versie van de app dan deze.
     *
     * Bewust een eigen variant en niet [UnsupportedMedia]: die raadt aan het
     * materiaal naar MP4 om te zetten, en dat is voor een projectbestand
     * onzinnig advies. De juiste handeling is de app bijwerken.
     */
    @Serializable
    @SerialName("outdated_app")
    public data class OutdatedApp(
        val fileVersion: Int,
        val supportedVersion: Int,
    ) : EditorError {
        override val code: String get() = "outdated_app"
        override val retryable: Boolean get() = false
        override val userMessage: String
            get() = "Dit project is gemaakt met een nieuwere versie van de app. " +
                "Werk de app bij en open het daarna opnieuw."
    }

    /**
     * De opslag is bezet doordat er al een schrijfactie loopt.
     *
     * Wel opnieuw te proberen, in tegenstelling tot de andere opslagfouten: de
     * vorige schrijfactie is zo klaar. Zonder eigen variant zou dit moeten lenen
     * van een dienstfout, en dan liegt de tekst over waar het probleem zit.
     */
    @Serializable
    @SerialName("storage_busy")
    public data class StorageBusy(val detail: String? = null) : EditorError {
        override val code: String get() = "storage_busy"
        override val retryable: Boolean get() = true
        override val userMessage: String
            get() = "Er wordt op dit moment al iets anders opgeslagen. " +
                "Wacht even en probeer het daarna opnieuw."
    }

    /** De decoder of encoder gaf er halverwege de bewerking de brui aan. */
    @Serializable
    @SerialName("codec_failure")
    public data class CodecFailure(
        val stage: Stage,
        val path: String? = null,
        val detail: String? = null,
    ) : EditorError {
        override val code: String get() = "codec_failure"

        /**
         * Niet opnieuw proberen: dezelfde beelden door dezelfde decoder halen
         * loopt op hetzelfde beeld weer vast. De gebruiker moet iets veranderen.
         */
        override val retryable: Boolean get() = false

        override val userMessage: String
            get() = "${stage.label} liep vast op dit materiaal. " +
                "Kies een kortere selectie of een lagere kwaliteit en probeer het opnieuw."
    }

    /** Geen verbinding. [service] is null als de storing niet aan één dienst hangt. */
    @Serializable
    @SerialName("network_unavailable")
    public data class NetworkUnavailable(val service: RemoteService? = null) : EditorError {
        override val code: String get() = "network_unavailable"
        override val retryable: Boolean get() = true
        override val userMessage: String
            get() = if (service == null) {
                "Er is op dit moment geen internetverbinding. Controleer je verbinding en probeer het opnieuw."
            } else {
                "Er is geen verbinding met ${service.label}. Controleer je internetverbinding en probeer het opnieuw."
            }
    }

    /** De dienst antwoordde niet binnen de afgesproken tijd. */
    @Serializable
    @SerialName("service_timeout")
    public data class ServiceTimeout(
        val service: RemoteService? = null,
        val waitedMs: Long? = null,
    ) : EditorError {
        override val code: String get() = "service_timeout"
        override val retryable: Boolean get() = true
        override val userMessage: String
            get() {
                val wie = service?.label ?: "de dienst"
                return "Het antwoord van $wie bleef te lang uit. " +
                    "Probeer het zo nog eens, het liefst met een snellere verbinding."
            }
    }

    /** De dienst gaf een foutcode terug. */
    @Serializable
    @SerialName("service_failure")
    public data class ServiceFailure(
        val service: RemoteService,
        val statusCode: Int,
        val detail: String? = null,
        val retryAfterMs: Long? = null,
    ) : EditorError {
        override val code: String get() = "service_failure"

        /**
         * Alleen bij 5xx: daar ligt het aan de andere kant en helpt wachten.
         * Bij 4xx klopt de vraag zelf niet en verandert herhalen niets.
         */
        override val retryable: Boolean get() = statusCode >= 500

        override val userMessage: String
            get() = if (retryable) {
                "${service.label.sentenceStart()} kan je opdracht nu niet verwerken. " +
                    "Probeer het over een paar minuten opnieuw."
            } else {
                "${service.label.sentenceStart()} kan hier niets mee. " +
                    "Controleer je materiaal en je instellingen en probeer het opnieuw."
            }
    }

    /** Te veel verzoeken in korte tijd. */
    @Serializable
    @SerialName("rate_limited")
    public data class RateLimited(
        val service: RemoteService,
        /** Wat de dienst zelf als wachttijd opgaf, als die het meldde. */
        val retryAfterMs: Long? = null,
    ) : EditorError {
        override val code: String get() = "rate_limited"
        override val retryable: Boolean get() = true
        override val userMessage: String
            get() {
                val wachttijd = retryAfterMs?.let { "ongeveer ${humanWait(it)}" } ?: "een paar minuten"
                return "Je gebruikt ${service.label} even te vaak achter elkaar. " +
                    "Wacht $wachttijd en probeer het opnieuw."
            }
    }

    /** De dienst wees de toegang af. */
    @Serializable
    @SerialName("authentication_rejected")
    public data class AuthenticationRejected(val service: RemoteService) : EditorError {
        override val code: String get() = "authentication_rejected"

        /** Dezelfde afgewezen toegang blijft afgewezen tot iemand hem goedzet. */
        override val retryable: Boolean get() = false

        override val userMessage: String
            get() = "De toegang tot ${service.label} is geweigerd. " +
                "Controleer je gegevens bij de instellingen en probeer het opnieuw."
    }

    /** De gebruiker vroeg iets wat zo niet kan. */
    @Serializable
    @SerialName("invalid_input")
    public data class InvalidInput(val problem: InputProblem) : EditorError {
        override val code: String get() = "invalid_input"
        override val retryable: Boolean get() = false
        override val userMessage: String get() = problem.message
    }

    /** De gebruiker brak het werk zelf af. */
    @Serializable
    @SerialName("cancelled")
    public data class Cancelled(val stage: Stage? = null) : EditorError {
        override val code: String get() = "cancelled"

        /**
         * Nooit vanzelf opnieuw proberen: de gebruiker heeft juist gezegd dat
         * hij dit niet wilde. Opnieuw beginnen is aan hem.
         */
        override val retryable: Boolean get() = false

        override val userMessage: String
            get() {
                val wat = stage?.label?.midSentence() ?: "de bewerking"
                return "Je hebt $wat zelf gestopt. Je kunt het opnieuw starten wanneer je wilt."
            }
    }

    /** Het toestel is te warm om door te werken. */
    @Serializable
    @SerialName("overheated")
    public data class Overheated(
        val measuredCelsius: Int? = null,
        /**
         * Hoe lang het toestel met rust gelaten moet worden.
         *
         * Draagt een waarde omdat de fout anders in de exponentiële backoff
         * valt en de wachtrij binnen drie seconden vier keer terugkomt op een
         * toestel dat minuten nodig heeft — en het daarna permanent opgeeft.
         * Wie de temperatuur echt meet ([nl.artifation.videoeditor.errors] weet
         * dat niet) geeft hier zijn eigen schatting mee.
         */
        val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ) : EditorError {
        override val code: String get() = "overheated"

        /** Warmte zakt vanzelf; dit is juist het geval waarvoor uitgesteld opnieuw proberen bestaat. */
        override val retryable: Boolean get() = true

        override val userMessage: String
            get() = "Je toestel is te warm geworden om door te werken. " +
                "Leg het even weg en ga over een paar minuten verder."

        public companion object {
            /** Een minuut is de ondergrens waarop een warm toestel merkbaar zakt. */
            public const val DEFAULT_COOLDOWN_MS: Long = 60_000L
        }
    }

    /**
     * Een fout met de fase eromheen.
     *
     * Zo blijft de oorzaak intact — de wachtrij kijkt naar de oorzaak, het
     * scherm vertelt de gebruiker wat er niet doorging.
     */
    @Serializable
    @SerialName("stage_failure")
    public data class StageFailure(
        val stage: Stage,
        val cause: EditorError,
    ) : EditorError {
        override val code: String get() = "stage_failure"

        /** De omhulling verandert niets aan de vraag of herhalen zin heeft. */
        override val retryable: Boolean get() = cause.retryable

        override val userMessage: String get() = "${stage.label} is gestopt. ${cause.userMessage}"
    }

    /**
     * Meer dan één fout tegelijk, bijvoorbeeld bij een export van tien clips.
     *
     * Maak deze niet zelf aan maar via [combineErrors]: die vlakt geneste
     * verzamelingen af en geeft één fout terug als er maar één is.
     */
    @Serializable
    @SerialName("multiple")
    public data class Multiple(val errors: List<EditorError>) : EditorError {
        init {
            require(errors.size >= 2) { "Multiple heeft minstens twee fouten nodig, kreeg ${errors.size}" }
            require(errors.none { it is Multiple }) { "Multiple mag niet genest zijn; gebruik combineErrors()" }
        }

        override val code: String get() = "multiple"

        /**
         * Alleen als álles opnieuw kan. Zit er één kapot bestand tussen, dan
         * loopt de hele taak daar bij elke herhaling weer op stuk.
         */
        override val retryable: Boolean get() = errors.all { it.retryable }

        /** De fout waar de gebruiker mee moet beginnen: die waar herhalen niet helpt. */
        val primary: EditorError get() = errors.firstOrNull { !it.retryable } ?: errors.first()

        override val userMessage: String
            get() = "${errors.size} onderdelen zijn niet gelukt. Begin hiermee: ${primary.userMessage}"
    }
}

/**
 * De fout zonder de fasen eromheen — waar de wachtrij naar wil kijken.
 */
public fun EditorError.rootCause(): EditorError =
    if (this is EditorError.StageFailure) cause.rootCause() else this

/**
 * De wachttijd die de andere kant zelf opgaf, ook als hij een paar lagen diep zit.
 *
 * Bij meerdere fouten telt de langste: eerder terugkomen zou meteen weer een
 * afwijzing opleveren.
 */
public fun EditorError.retryAfterMs(): Long? = when (this) {
    is EditorError.RateLimited -> retryAfterMs
    is EditorError.ServiceFailure -> retryAfterMs
    is EditorError.Overheated -> cooldownMs
    is EditorError.StageFailure -> cause.retryAfterMs()
    is EditorError.Multiple -> errors.mapNotNull { it.retryAfterMs() }.maxOrNull()
    else -> null
}

/**
 * Voegt losse fouten samen tot één fout: niets, precies die ene, of een [EditorError.Multiple].
 *
 * Geneste verzamelingen worden afgevlakt en dubbele fouten verdwijnen — anders
 * krijgt de gebruiker tien keer dezelfde regel te lezen.
 */
public fun combineErrors(errors: List<EditorError>): EditorError? {
    val flat = errors.flatMap { if (it is EditorError.Multiple) it.errors else listOf(it) }.distinct()
    return when (flat.size) {
        0 -> null
        1 -> flat.single()
        else -> EditorError.Multiple(flat)
    }
}

/**
 * Draagt een [EditorError] door code heen die alleen excepties kent.
 *
 * De exceptie-tekst is de geschoonde logregel, zodat een stacktrace in een
 * crashrapport nooit een sleutel of een volledig pad bevat.
 */
public class EditorException(
    public val error: EditorError,
    cause: Throwable? = null,
) : Exception(ErrorLog.line(error), cause)
