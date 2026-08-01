package nl.artifation.videoeditor.project

import nl.artifation.videoeditor.errors.EditorError
import nl.artifation.videoeditor.errors.ErrorLog
import nl.artifation.videoeditor.errors.ErrorMapper

/**
 * Wat er als bestandsnaam in de gebruikerstekst komt te staan.
 *
 * De bestandsvarianten van `:core-errors` noemen altijd één bestand ("Het bestand
 * X kan niet gelezen worden"). Een project heeft voor de gebruiker geen
 * bestandsnaam, en zijn id is een technische sleutel — precies wat volgens de
 * afspraken van `:core-errors` niet in een melding thuishoort. Deze omschrijving
 * loopt in alle drie de zinnen mee ("Het bestand van dit project ..."), zodat de
 * ids blijven waar ze horen: in [ProjectException.message] en in
 * [ProjectException.logLine].
 */
private const val PROJECT_ALS_BESTAND: String = "van dit project"

/** Hoe diep [editorErrorOf] een keten van oorzaken volgt; gelijk aan die van `ErrorMapper`. */
private const val MAX_CAUSE_DEPTH: Int = 8

/**
 * Fouten rond projectbestanden.
 *
 * Bewust checked-achtige, specifieke types: het verschil tussen "stuk bestand"
 * en "te nieuw bestand" bepaalt wat de gebruiker te zien krijgt, en die twee
 * mogen dus nooit op één hoop belanden.
 *
 * Elke fout draagt daarnaast een [error] uit `:core-errors`. Dat is niet dubbelop:
 * de exceptie zelf blijft de ontwikkelaarskant (het type, de extra velden en een
 * technische [message] voor het crashrapport), terwijl [error] de kant is die de
 * app buiten deze module nodig heeft — een stabiele [code], een eerlijk
 * [retryable] en een Nederlandse [userMessage]. Zonder die tweede kant belandt de
 * ontwikkelaarstekst vroeg of laat op het scherm.
 */
public sealed class ProjectException(
    message: String,
    /**
     * Dezelfde fout in het vocabulaire van de hele app.
     *
     * `EditorError` is `@Serializable`; dit is dus ook de vorm waarin een
     * mislukte taak in een wachtrij op schijf blijft staan. Daarom krijgt de
     * variant hier waar mogelijk de ontwikkelaarstekst mee: zonder dat overleeft
     * alleen de exceptie het proces, en die overleeft het niet.
     */
    public val error: EditorError,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Nederlandse tekst voor de gebruiker: wat er misging en wat hij eraan kan doen. */
    public val userMessage: String get() = error.userMessage

    /** Stabiele sleutel voor logs en telemetrie, los van de tekst. */
    public val code: String get() = error.code

    /** Of dezelfde poging later kans van slagen heeft. */
    public val retryable: Boolean get() = error.retryable

    /**
     * Eén regel voor het log, in dezelfde vorm als de rest van de app.
     *
     * Loopt bewust via [ErrorLog]: die schoont sleutels weg en kort een lange
     * detailtekst af. Een parserfout kan zomaar een stuk van het bestand
     * citeren, en een logregel wordt geplakt in een issue.
     */
    public open val logLine: String get() = ErrorLog.line(error)
}

/**
 * Leeg, afgekapt of ongeldig bestand. Herstel is alleen mogelijk uit een ander slot.
 *
 * Wordt [EditorError.ProjectDamaged], die als handeling een eerdere versie
 * openen of opnieuw beginnen geeft. Eerder leende dit van
 * [EditorError.FileUnreadable], maar dat raadt aan het bestand opnieuw naar het
 * toestel te kopiëren — een project komt nergens vandaan, het staat alleen hier.
 */
public class CorruptProjectException(
    message: String,
    cause: Throwable? = null,
) : ProjectException(
    message,
    // De ontwikkelaarstekst gaat mee als detail en niet als gebruikerstekst:
    // ErrorLog schoont en kort dat veld af, dus een sleutel of een half bestand
    // uit een parserfout haalt de logregel niet ongeschonden.
    EditorError.ProjectDamaged(detail = message),
    cause,
)

/**
 * Het bestand komt uit een nieuwere app-versie.
 *
 * Dit moet keihard falen. `ignoreUnknownKeys` zou het bestand half inlezen en
 * bij de eerstvolgende opslag alles wegschrijven wat deze versie niet kende —
 * dan is de montage stil gesloopt.
 *
 * Wordt [EditorError.OutdatedApp], die precies deze situatie beschrijft en als
 * handeling "werk de app bij" geeft. Eerder leende dit van
 * [EditorError.UnsupportedMedia], maar dat raadt aan het bestand naar MP4 om te
 * zetten — onzinnig advies voor een projectbestand.
 */
public class UnsupportedSchemaVersionException(
    public val fileVersion: Int,
    public val supportedVersion: Int,
) : ProjectException(
    "projectbestand heeft schemaversie $fileVersion, deze app kent maximaal " +
        "$supportedVersion; werk de app bij in plaats van het project te openen",
    EditorError.OutdatedApp(fileVersion = fileVersion, supportedVersion = supportedVersion),
)

/**
 * Er staat niets (meer) onder deze id.
 *
 * Wordt [EditorError.FileMissing]: het bestand stond er, staat er niet meer, en
 * de gebruiker moet zelf iets anders kiezen. Opnieuw proberen levert dezelfde
 * lege plek op.
 */
public class ProjectNotFoundException(
    public val id: String,
) : ProjectException("geen project met id '$id'", EditorError.FileMissing(PROJECT_ALS_BESTAND)) {

    /**
     * `FileMissing` logt de bestandsnaam, en dat is hier de omschrijving voor de
     * gebruiker. De id moet er dus los bij, anders staat er in het log wel dát er
     * een project ontbreekt maar niet welk.
     */
    override val logLine: String get() = "${ErrorLog.line(error)} id=$id"
}

/**
 * Er liep al een schrijfactie toen er een tweede begon.
 *
 * Autosave en een handmatige opslag kunnen elkaar overlappen; door dat te
 * weigeren in plaats van te vlechten, kan er nooit een half bestand ontstaan.
 *
 * Dit is de enige projectfout waarbij opnieuw proberen wél zin heeft: de andere
 * schrijfactie is zo klaar. Wordt [EditorError.StorageBusy], die dat precies
 * zegt. Eerder leende dit van `ServiceTimeout`, wat over een trage dienst
 * begon terwijl het probleem in de opslag zat.
 */
public class ConcurrentWriteException(
    public val id: String,
    public val busyWithId: String,
) : ProjectException(
    "schrijven naar '$id' geweigerd: er wordt al naar '$busyWithId' geschreven",
    EditorError.StorageBusy(detail = "bezig met $busyWithId"),
) {

    /** `StorageBusy` heeft geen veld voor ids; zonder deze regel is niet te zien wát er botste. */
    override val logLine: String get() = "${ErrorLog.line(error)} id=$id busyWith=$busyWithId"
}

/**
 * De fout achter een willekeurige exceptie, of null als hij niet te duiden is.
 *
 * `ErrorMapper` kan dit niet zelf: `:core-errors` kent deze module niet — de
 * afhankelijkheid loopt maar één kant op — dus zou een [ProjectException] daar op
 * null uitkomen en zonder gebruikerstekst blijven. Deze functie vangt de
 * projectfouten af en laat al het andere (bestands- en netwerkexcepties van de
 * JVM) aan `ErrorMapper` over, zodat een aanroeper die alleen `Throwable`s ziet
 * met één functie toekan.
 */
public fun editorErrorOf(throwable: Throwable, path: String? = null): EditorError? {
    var current: Throwable? = throwable
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        (current as? ProjectException)?.let { return it.error }
        current = current.cause.takeIf { it !== current }
        depth++
    }
    return ErrorMapper.fromThrowable(throwable, path)
}
