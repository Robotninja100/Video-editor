package nl.artifation.videoeditor.project

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Eén stap in de migratieketen.
 *
 * Migraties werken op ruwe JSON, niet op het model. Dat is de kern van het
 * mechanisme: een migratie moet het formaat beschrijven zoals het toen was, en
 * blijven werken als het model daarna nog tien keer verandert. Zodra een
 * migratie `Project.serializer()` zou gebruiken, breekt hij bij de volgende
 * modelwijziging — en juist oude bestanden zijn dan het slachtoffer.
 */
public interface SchemaMigration {
    public val fromVersion: Int
    public val toVersion: Int

    public fun migrate(document: JsonObject): JsonObject
}

/**
 * v1 was het kale `Project`-object, zoals `ProjectJson.encode` het schreef:
 * geen versienummer, geen kop, geen naam.
 *
 * De migratie vouwt dat in de omslag en berekent de kop uit de ruwe JSON, zodat
 * ook een gemigreerd project in de projectenlijst verschijnt zonder dat de hele
 * tijdlijn gedecodeerd hoeft te worden.
 */
public object MigrationV1ToV2 : SchemaMigration {

    override val fromVersion: Int = 1
    override val toVersion: Int = 2

    override fun migrate(document: JsonObject): JsonObject {
        val id = (document["id"] as? JsonPrimitive)?.content
            ?: throw CorruptProjectException("v1-project zonder id-veld")

        return buildJsonObject {
            put("schemaVersion", toVersion)
            putJsonObject("summary") {
                put("id", id)
                // v1 kende geen projectnaam; de id is het enige herkenbare dat er is.
                put("name", id)
                // En geen wijzigingstijd. 0 in plaats van "nu": een verzonnen tijd
                // zou het project bovenaan de lijst zetten alsof het net bewerkt is.
                put("lastModifiedMs", 0L)
                put("durationUs", durationUsOf(document))
                put("clipCount", clipCountOf(document))
            }
            put("project", document)
        }
    }

    /** Projectduur = de langste sequence, net als `Project.durationUs`. */
    private fun durationUsOf(project: JsonObject): Long =
        sequencesOf(project).maxOfOrNull { sequence ->
            itemsOf(sequence).sumOf { itemDurationUs(it) }
        } ?: 0L

    private fun clipCountOf(project: JsonObject): Int =
        sequencesOf(project).sumOf { sequence ->
            itemsOf(sequence).count { (it["kind"] as? JsonPrimitive)?.content == "clip" }
        }

    private fun itemDurationUs(item: JsonObject): Long {
        val kind = (item["kind"] as? JsonPrimitive)?.content
        return when (kind) {
            "gap" -> item.long("durationUs")
            "clip" -> {
                // Snelheid stond er alleen in als hij afweek: encodeDefaults = false.
                val speed = (item["speed"] as? JsonPrimitive)?.floatOrNull ?: 1f
                ((item.long("outPointUs") - item.long("inPointUs")) / speed).toLong()
            }

            else -> throw CorruptProjectException("onbekend tijdlijnitem '$kind' in v1-project")
        }
    }

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull
            ?: throw CorruptProjectException("v1-project mist veld '$key'")
}

/**
 * v3 voegt een thumbnailsleutel aan de kop toe, zodat de projectenlijst een
 * beeldje kan tonen zonder het project te openen.
 *
 * Voor bestaande projecten is de bron van het eerste clipje de beste gok — beter
 * dan een leeg vlak tot de gebruiker het project een keer opent.
 */
public object MigrationV2ToV3 : SchemaMigration {

    override val fromVersion: Int = 2
    override val toVersion: Int = 3

    override fun migrate(document: JsonObject): JsonObject {
        val summary = document["summary"] as? JsonObject
            ?: throw CorruptProjectException("v2-project zonder kop")
        val project = document["project"] as? JsonObject
            ?: throw CorruptProjectException("v2-project zonder project")

        val thumbnailKey = summary["thumbnailKey"]?.takeIf { it is JsonPrimitive && it.isString }
            ?: firstSourceUri(project)

        return buildJsonObject {
            for ((key, value) in document) put(key, value)
            put("schemaVersion", toVersion)
            putJsonObject("summary") {
                for ((key, value) in summary) put(key, value)
                if (thumbnailKey != null) put("thumbnailKey", thumbnailKey)
            }
        }
    }

    private fun firstSourceUri(project: JsonObject): JsonPrimitive? =
        sequencesOf(project)
            .flatMap { itemsOf(it) }
            .firstOrNull { (it["kind"] as? JsonPrimitive)?.content == "clip" }
            ?.get("sourceUri") as? JsonPrimitive
}

/**
 * v3 → v4: het volgnummer erbij.
 *
 * Bestaande bestanden krijgen 0. Herstel valt voor die bestanden terug op de
 * wijzigingstijd — meer valt er over hun onderlinge volgorde ook niet te weten.
 */
public object MigrationV3ToV4 : SchemaMigration {

    override val fromVersion: Int = 3
    override val toVersion: Int = 4

    override fun migrate(document: JsonObject): JsonObject {
        val summary = document["summary"] as? JsonObject
            ?: throw CorruptProjectException("v3-project zonder kop")

        return buildJsonObject {
            for ((key, value) in document) put(key, value)
            put("schemaVersion", toVersion)
            putJsonObject("summary") {
                for ((key, value) in summary) put(key, value)
                put("revision", 0L)
            }
        }
    }
}

private fun sequencesOf(project: JsonObject): List<JsonObject> =
    (project["sequences"] as? JsonArray).orEmptyObjects()

private fun itemsOf(sequence: JsonObject): List<JsonObject> =
    (sequence["items"] as? JsonArray).orEmptyObjects()

private fun JsonArray?.orEmptyObjects(): List<JsonObject> =
    this?.mapNotNull { it as? JsonObject } ?: emptyList()

/**
 * De migratieketen van [OLDEST_SUPPORTED_SCHEMA_VERSION] tot
 * [CURRENT_SCHEMA_VERSION].
 */
public object SchemaMigrations {

    public val ALL: List<SchemaMigration> = listOf(MigrationV1ToV2, MigrationV2ToV3, MigrationV3ToV4)

    private val byFromVersion: Map<Int, SchemaMigration> = ALL.associateBy { it.fromVersion }

    /**
     * v1 kende het veld `schemaVersion` nog niet; een bestand zonder dat veld is
     * dus per definitie v1. Daarom kan de allereerste versie nooit meer 0 heten.
     */
    public fun versionOf(document: JsonObject): Int {
        val raw = document["schemaVersion"] ?: return OLDEST_SUPPORTED_SCHEMA_VERSION
        return (raw as? JsonPrimitive)?.intOrNull
            ?: throw CorruptProjectException("schemaVersion is geen getal: $raw")
    }

    /** De stappen die nodig zijn om [version] op [CURRENT_SCHEMA_VERSION] te krijgen. */
    public fun chainFrom(version: Int): List<SchemaMigration> {
        val steps = mutableListOf<SchemaMigration>()
        var at = version
        while (at < CURRENT_SCHEMA_VERSION) {
            val step = byFromVersion[at]
                ?: error("gat in de migratieketen: geen migratie vanaf schemaversie $at")
            steps.add(step)
            at = step.toVersion
        }
        return steps
    }

    /**
     * Tilt [document] op naar [CURRENT_SCHEMA_VERSION].
     *
     * Een nieuwere versie dan deze code kent, wordt geweigerd — zie
     * [UnsupportedSchemaVersionException].
     */
    public fun migrateToCurrent(document: JsonObject): JsonObject {
        val version = versionOf(document)
        if (version > CURRENT_SCHEMA_VERSION) {
            throw UnsupportedSchemaVersionException(version, CURRENT_SCHEMA_VERSION)
        }
        if (version < OLDEST_SUPPORTED_SCHEMA_VERSION) {
            throw CorruptProjectException(
                "schemaversie $version bestaat niet; oudste bekende versie is " +
                    "$OLDEST_SUPPORTED_SCHEMA_VERSION",
            )
        }
        return chainFrom(version).fold(document) { acc, migration -> migration.migrate(acc) }
    }
}
