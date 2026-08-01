package nl.artifation.videoeditor.project

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import nl.artifation.videoeditor.model.Clip
import nl.artifation.videoeditor.model.Gap
import nl.artifation.videoeditor.model.Project
import nl.artifation.videoeditor.model.ProjectJson
import nl.artifation.videoeditor.model.Sequence
import nl.artifation.videoeditor.model.TimelineItem
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.model.Us

internal fun clip(
    id: String,
    inPointUs: Us = 0L,
    outPointUs: Us = US_PER_SECOND,
    speed: Float = 1f,
): Clip = Clip(
    id = id,
    sourceUri = "content://media/$id",
    inPointUs = inPointUs,
    outPointUs = outPointUs,
    speed = speed,
)

internal fun project(id: String = "p1", vararg items: TimelineItem): Project =
    Project(id = id, sequences = listOf(Sequence(id = "hoofdtrack", items = items.toList())))

/** Duur 3.5s, twee clips — de getallen komen terug in de kop-assertions. */
internal fun voorbeeldProject(id: String = "p1"): Project = project(
    id,
    clip("a"),
    Gap(500_000L),
    clip("b", outPointUs = 2 * US_PER_SECOND),
)

internal fun voorbeeldBestand(
    id: String = "p1",
    naam: String = "Vakantie",
    lastModifiedMs: Long = 1_000L,
): ProjectFile = ProjectFile.of(voorbeeldProject(id), naam, lastModifiedMs)

/** Een projectbestand zoals v1 het schreef: het kale project, zonder omslag. */
internal fun legacyV1Json(project: Project = voorbeeldProject()): String =
    ProjectJson.encode(project)

internal fun parseJson(text: String): JsonObject =
    ProjectJson.format.parseToJsonElement(text).jsonObject

internal fun printJson(document: JsonObject): String =
    ProjectJson.format.encodeToString(JsonObject.serializer(), document)

/** Zet er een ander versienummer op, zonder de rest aan te raken. */
internal fun metSchemaVersie(text: String, versie: Int): String =
    printJson(JsonObject(parseJson(text) + ("schemaVersion" to JsonPrimitive(versie))))
