package nl.artifation.videoeditor.project

import kotlinx.serialization.Serializable
import nl.artifation.videoeditor.model.Clip
import nl.artifation.videoeditor.model.Project
import nl.artifation.videoeditor.model.Us

/**
 * Versie van het projectbestandsformaat die deze code schrijft.
 *
 * Verhogen zodra het formaat verandert, met een bijbehorende migratie in
 * [SchemaMigrations.ALL]. De ketentest bewaakt dat je dat niet vergeet.
 */
public const val CURRENT_SCHEMA_VERSION: Int = 3

/** Oudste versie die nog opgetild kan worden naar [CURRENT_SCHEMA_VERSION]. */
public const val OLDEST_SUPPORTED_SCHEMA_VERSION: Int = 1

/**
 * Kop van een projectbestand: genoeg voor een projectenlijst, zonder de
 * tijdlijn te hoeven decoderen.
 *
 * Duur en aantal clips staan er afgeleid in. Dat is bewust redundant: bij
 * honderden projecten is de lijst anders zo traag als het zwaarste project,
 * en de kop wordt bij elke opslag opnieuw berekend zodat hij niet kan verjaren.
 */
@Serializable
public data class ProjectSummary(
    val id: String,
    val name: String,
    val lastModifiedMs: Long,
    val durationUs: Us,
    val clipCount: Int,
    /** Sleutel in de framecache; null zolang er nog geen frame gerenderd is. */
    val thumbnailKey: String? = null,
)

/**
 * Een projectbestand: versienummer, kop en het project zelf.
 *
 * [schemaVersion] heeft expres géén standaardwaarde. `ProjectJson.format` staat
 * op `encodeDefaults = false`, dus een veld met een default zou niet worden
 * weggeschreven — precies het veld dat altijd in het bestand moet staan.
 */
@Serializable
public data class ProjectFile(
    val schemaVersion: Int,
    val summary: ProjectSummary,
    val project: Project,
) {
    init {
        require(summary.id == project.id) {
            "kop hoort bij project '${summary.id}' maar bevat project '${project.id}'"
        }
    }

    /** Zelfde project, andere naam; werkt meteen de wijzigingstijd bij. */
    public fun renamed(name: String, nowMs: Long): ProjectFile =
        copy(summary = summary.copy(name = name, lastModifiedMs = nowMs))

    /** Legt een bewerkt project vast; de afgeleide velden in de kop gaan mee. */
    public fun withProject(project: Project, nowMs: Long): ProjectFile =
        of(project, summary.name, nowMs, summary.thumbnailKey)

    public fun withThumbnail(thumbnailKey: String?): ProjectFile =
        copy(summary = summary.copy(thumbnailKey = thumbnailKey))

    public companion object {

        public fun of(
            project: Project,
            name: String,
            lastModifiedMs: Long,
            thumbnailKey: String? = null,
        ): ProjectFile = ProjectFile(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            summary = summaryOf(project, name, lastModifiedMs, thumbnailKey),
            project = project,
        )

        public fun summaryOf(
            project: Project,
            name: String,
            lastModifiedMs: Long,
            thumbnailKey: String? = null,
        ): ProjectSummary = ProjectSummary(
            id = project.id,
            name = name,
            lastModifiedMs = lastModifiedMs,
            durationUs = project.durationUs,
            clipCount = project.clipCount(),
            thumbnailKey = thumbnailKey,
        )
    }
}

/** Clips over alle sequences heen; gaten tellen niet mee. */
public fun Project.clipCount(): Int =
    sequences.sumOf { sequence -> sequence.items.count { it is Clip } }
