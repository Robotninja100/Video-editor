package nl.artifation.videoeditor.project

/**
 * Fouten rond projectbestanden.
 *
 * Bewust checked-achtige, specifieke types: het verschil tussen "stuk bestand"
 * en "te nieuw bestand" bepaalt wat de gebruiker te zien krijgt, en die twee
 * mogen dus nooit op één hoop belanden.
 */
public sealed class ProjectException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Leeg, afgekapt of ongeldig bestand. Herstel is alleen mogelijk uit een ander slot. */
public class CorruptProjectException(
    message: String,
    cause: Throwable? = null,
) : ProjectException(message, cause)

/**
 * Het bestand komt uit een nieuwere app-versie.
 *
 * Dit moet keihard falen. `ignoreUnknownKeys` zou het bestand half inlezen en
 * bij de eerstvolgende opslag alles wegschrijven wat deze versie niet kende —
 * dan is de montage stil gesloopt.
 */
public class UnsupportedSchemaVersionException(
    public val fileVersion: Int,
    public val supportedVersion: Int,
) : ProjectException(
    "projectbestand heeft schemaversie $fileVersion, deze app kent maximaal " +
        "$supportedVersion; werk de app bij in plaats van het project te openen",
)

public class ProjectNotFoundException(
    public val id: String,
) : ProjectException("geen project met id '$id'")

/**
 * Er liep al een schrijfactie toen er een tweede begon.
 *
 * Autosave en een handmatige opslag kunnen elkaar overlappen; door dat te
 * weigeren in plaats van te vlechten, kan er nooit een half bestand ontstaan.
 */
public class ConcurrentWriteException(
    public val id: String,
    public val busyWithId: String,
) : ProjectException("schrijven naar '$id' geweigerd: er wordt al naar '$busyWithId' geschreven")
