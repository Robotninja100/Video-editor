package nl.artifation.videoeditor.project

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import nl.artifation.videoeditor.model.ProjectJson

/**
 * Leest en schrijft projectbestanden, inclusief migratie van oudere versies.
 *
 * Hergebruikt bewust `ProjectJson.format`: de tijdlijn binnen het bestand moet
 * byte-voor-byte hetzelfde gecodeerd worden als `:core-model` het bedoelt
 * (`kind` als discriminator, geen defaults).
 */
public object ProjectCodec {

    private val json: Json = ProjectJson.format

    public fun encode(file: ProjectFile): String =
        json.encodeToString(ProjectFile.serializer(), file)

    /**
     * Leest een volledig projectbestand en migreert het naar het huidige formaat.
     *
     * @throws CorruptProjectException bij lege of ongeldige inhoud
     * @throws UnsupportedSchemaVersionException bij een bestand uit een nieuwere app
     */
    public fun decode(text: String): ProjectFile {
        val migrated = SchemaMigrations.migrateToCurrent(parse(text))
        val file = try {
            json.decodeFromJsonElement(ProjectFile.serializer(), migrated)
        } catch (e: SerializationException) {
            throw CorruptProjectException("projectbestand is niet te decoderen: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            // `require` in ProjectFile: kop en project horen niet bij elkaar.
            throw CorruptProjectException("projectbestand is inconsistent: ${e.message}", e)
        }
        // Duur en clipCount zijn afgeleid. Ze staan in het bestand voor de lijst,
        // maar zodra het project er tóch is, is het project de waarheid.
        return file.copy(
            summary = file.summary.copy(
                durationUs = file.project.durationUs,
                clipCount = file.project.clipCount(),
            ),
        )
    }

    /**
     * Leest alleen de kop. Dit is wat de projectenlijst gebruikt: de tijdlijn
     * wordt niet gematerialiseerd, hoe groot het project ook is.
     */
    public fun decodeSummary(text: String): ProjectSummary {
        val migrated = SchemaMigrations.migrateToCurrent(parse(text))
        val header = migrated["summary"]
            ?: throw CorruptProjectException("projectbestand mist de kop")
        return try {
            json.decodeFromJsonElement(ProjectSummary.serializer(), header)
        } catch (e: SerializationException) {
            throw CorruptProjectException("kop van het projectbestand is stuk: ${e.message}", e)
        }
    }

    /** Versie van het bestand zoals het op schijf staat, vóór migratie. */
    public fun versionOf(text: String): Int = SchemaMigrations.versionOf(parse(text))

    /** Of de tekst als project te openen is; gebruikt door de herstelbeslissing. */
    public fun isReadable(text: String): Boolean =
        try {
            decodeSummary(text)
            true
        } catch (e: CorruptProjectException) {
            false
        }

    private fun parse(text: String): JsonObject {
        // Een afgekapt bestand van 0 bytes is de normale uitkomst van een crash
        // midden in een schrijfactie; die verdient een begrijpelijke fout.
        if (text.isBlank()) throw CorruptProjectException("projectbestand is leeg")
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw CorruptProjectException("projectbestand bevat ongeldige JSON: ${e.message}", e)
        }
        return element as? JsonObject
            ?: throw CorruptProjectException("projectbestand is geen JSON-object")
    }
}
