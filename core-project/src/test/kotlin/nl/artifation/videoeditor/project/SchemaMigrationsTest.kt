package nl.artifation.videoeditor.project

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import nl.artifation.videoeditor.model.Gap
import nl.artifation.videoeditor.model.US_PER_SECOND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigratieketenTest {

    @Test
    fun `de keten van de oudste tot de huidige versie is compleet`() {
        val stappen = SchemaMigrations.chainFrom(OLDEST_SUPPORTED_SCHEMA_VERSION)

        val bezocht = stappen.map { it.fromVersion } + CURRENT_SCHEMA_VERSION
        assertEquals(
            (OLDEST_SUPPORTED_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION).toList(),
            bezocht,
            "keten: ${stappen.map { "v${it.fromVersion}->v${it.toVersion}" }}",
        )
    }

    @Test
    fun `elke migratie zet precies één stap`() {
        for (migratie in SchemaMigrations.ALL) {
            assertEquals(
                migratie.fromVersion + 1,
                migratie.toVersion,
                "migratie $migratie springt meer dan één versie",
            )
        }
    }

    @Test
    fun `elke versie tot de huidige heeft precies één migratie`() {
        val vanaf = SchemaMigrations.ALL.groupBy { it.fromVersion }

        for (versie in OLDEST_SUPPORTED_SCHEMA_VERSION until CURRENT_SCHEMA_VERSION) {
            assertEquals(
                1,
                vanaf[versie]?.size ?: 0,
                "geen of dubbele migratie vanaf v$versie; gevonden: ${vanaf[versie]}",
            )
        }
    }

    @Test
    fun `de keten voegt niets toe voor een bestand van de huidige versie`() {
        assertEquals(
            emptyList(),
            SchemaMigrations.chainFrom(CURRENT_SCHEMA_VERSION),
            "een actueel bestand hoeft niet gemigreerd te worden",
        )
    }

    @Test
    fun `een bestand zonder versienummer is versie 1`() {
        assertEquals(1, SchemaMigrations.versionOf(parseJson(legacyV1Json())))
    }

    @Test
    fun `een versienummer dat geen getal is geeft een fout`() {
        val document = buildJsonObject { put("schemaVersion", "drie") }

        assertFailsWith<CorruptProjectException>("tekst is geen versienummer") {
            SchemaMigrations.versionOf(document)
        }
    }

    @Test
    fun `een versie onder de oudste bekende geeft een fout`() {
        val document = buildJsonObject { put("schemaVersion", 0) }

        assertFailsWith<CorruptProjectException>("v0 heeft nooit bestaan") {
            SchemaMigrations.migrateToCurrent(document)
        }
    }

    @Test
    fun `een bestand van een nieuwere versie geeft een duidelijke fout`() {
        val tekst = metSchemaVersie(
            ProjectCodec.encode(voorbeeldBestand()),
            CURRENT_SCHEMA_VERSION + 1,
        )

        val fout = assertFailsWith<UnsupportedSchemaVersionException> { ProjectCodec.decode(tekst) }

        assertEquals(CURRENT_SCHEMA_VERSION + 1, fout.fileVersion, "fout: ${fout.message}")
        assertEquals(CURRENT_SCHEMA_VERSION, fout.supportedVersion, "fout: ${fout.message}")
        assertTrue(
            fout.message!!.contains("werk de app bij"),
            "de boodschap moet zeggen wat de gebruiker moet doen: ${fout.message}",
        )
    }

    @Test
    fun `ook de kop van een nieuwer bestand wordt niet half gelezen`() {
        val tekst = metSchemaVersie(
            ProjectCodec.encode(voorbeeldBestand()),
            CURRENT_SCHEMA_VERSION + 5,
        )

        assertFailsWith<UnsupportedSchemaVersionException>("de lijst mag niet doen alsof") {
            ProjectCodec.decodeSummary(tekst)
        }
    }
}

class MigratieV1NaarV2Test {

    @Test
    fun `het kale project wordt in een omslag gevouwen`() {
        val v1 = parseJson(legacyV1Json())

        val v2 = MigrationV1ToV2.migrate(v1)

        assertEquals(2, SchemaMigrations.versionOf(v2), "resultaat: $v2")
        assertEquals(v1, v2["project"], "het project zelf mag niet veranderen")
    }

    @Test
    fun `de naam valt terug op de id en de wijzigingstijd op nul`() {
        val kop = MigrationV1ToV2.migrate(parseJson(legacyV1Json())).kop()

        assertEquals("p1", kop["name"]?.jsonPrimitive?.content, "kop: $kop")
        // Een verzonnen "nu" zou het project bovenaan de lijst zetten alsof het
        // net bewerkt is; 0 is eerlijker.
        assertEquals(0L, kop["lastModifiedMs"]?.jsonPrimitive?.content?.toLong(), "kop: $kop")
    }

    @Test
    fun `duur en clipCount worden uit de ruwe JSON berekend`() {
        val kop = MigrationV1ToV2.migrate(parseJson(legacyV1Json())).kop()

        assertEquals(3_500_000L, kop["durationUs"]?.jsonPrimitive?.content?.toLong(), "kop: $kop")
        assertEquals(2, kop["clipCount"]?.jsonPrimitive?.content?.toInt(), "kop: $kop")
    }

    @Test
    fun `een weggelaten snelheid telt als 1x`() {
        // encodeDefaults staat uit, dus speed ontbreekt bij normale clips en
        // staat er alleen bij een afwijkende waarde.
        val versneld = project(
            "p1",
            clip("a", outPointUs = 2 * US_PER_SECOND, speed = 2f),
            clip("b", outPointUs = 2 * US_PER_SECOND),
            Gap(US_PER_SECOND),
        )

        val kop = MigrationV1ToV2.migrate(parseJson(legacyV1Json(versneld))).kop()

        assertEquals(4_000_000L, kop["durationUs"]?.jsonPrimitive?.content?.toLong(), "kop: $kop")
    }

    @Test
    fun `een v1-project zonder id is niet te migreren`() {
        assertFailsWith<CorruptProjectException>("zonder id is er geen project") {
            MigrationV1ToV2.migrate(buildJsonObject { put("sequences", JsonPrimitive("nee")) })
        }
    }

    @Test
    fun `een onbekend tijdlijnitem geeft een fout in plaats van een verkeerde duur`() {
        val document = parseJson(
            """
            {
              "id": "p1",
              "sequences": [ { "id": "s", "items": [ { "kind": "sticker" } ] } ]
            }
            """.trimIndent(),
        )

        assertFailsWith<CorruptProjectException>("onbekend item moet opvallen") {
            MigrationV1ToV2.migrate(document)
        }
    }

    private fun JsonObject.kop(): JsonObject = this["summary"]!!.jsonObject
}

class MigratieV2NaarV3Test {

    /** Een v2-bestand: de omslag van v1->v2, dus zonder thumbnailsleutel. */
    private fun v2(): JsonObject = MigrationV1ToV2.migrate(parseJson(legacyV1Json()))

    @Test
    fun `de thumbnailsleutel wordt gevuld met de eerste clip`() {
        val v3 = MigrationV2ToV3.migrate(v2())

        assertEquals(3, SchemaMigrations.versionOf(v3), "resultaat: $v3")
        assertEquals(
            "content://media/a",
            v3["summary"]!!.jsonObject["thumbnailKey"]?.jsonPrimitive?.content,
            "kop: ${v3["summary"]}",
        )
    }

    @Test
    fun `de rest van de kop blijft ongemoeid`() {
        val voor = v2()["summary"]!!.jsonObject

        val na = MigrationV2ToV3.migrate(v2())["summary"]!!.jsonObject

        for ((sleutel, waarde) in voor) {
            assertEquals(waarde, na[sleutel], "veld '$sleutel' veranderde tijdens de migratie")
        }
    }

    @Test
    fun `een project zonder clips krijgt geen thumbnailsleutel`() {
        val leeg = MigrationV1ToV2.migrate(parseJson(legacyV1Json(project("p1", Gap(1_000L)))))

        val v3 = MigrationV2ToV3.migrate(leeg)

        assertNull(
            v3["summary"]!!.jsonObject["thumbnailKey"],
            "er is geen frame om te tonen: ${v3["summary"]}",
        )
    }

    @Test
    fun `een bestaande thumbnailsleutel blijft staan`() {
        val metSleutel = JsonObject(
            v2() + (
                "summary" to JsonObject(
                    v2()["summary"]!!.jsonObject + ("thumbnailKey" to JsonPrimitive("eigen")),
                )
                ),
        )

        val v3 = MigrationV2ToV3.migrate(metSleutel)

        assertEquals(
            "eigen",
            v3["summary"]!!.jsonObject["thumbnailKey"]?.jsonPrimitive?.content,
            "kop: ${v3["summary"]}",
        )
    }

    @Test
    fun `een v2-bestand zonder kop is stuk`() {
        assertFailsWith<CorruptProjectException>("zonder kop valt er niets te migreren") {
            MigrationV2ToV3.migrate(buildJsonObject { put("schemaVersion", 2) })
        }
    }
}

class OudProjectLadenTest {

    @Test
    fun `een project van een oudere versie blijft laden`() {
        val bestand = ProjectCodec.decode(legacyV1Json())

        assertEquals(CURRENT_SCHEMA_VERSION, bestand.schemaVersion, "gemigreerd: $bestand")
        assertEquals(voorbeeldProject(), bestand.project, "de tijdlijn moet gelijk blijven")
        assertEquals("p1", bestand.summary.name, "kop: ${bestand.summary}")
        assertEquals(3_500_000L, bestand.summary.durationUs, "kop: ${bestand.summary}")
        assertEquals("content://media/a", bestand.summary.thumbnailKey, "kop: ${bestand.summary}")
    }

    @Test
    fun `een gemigreerd project is daarna in het huidige formaat op te slaan`() {
        val gemigreerd = ProjectCodec.decode(legacyV1Json())

        val opnieuw = ProjectCodec.decode(ProjectCodec.encode(gemigreerd))

        assertEquals(gemigreerd, opnieuw, "tweede ronde: $opnieuw")
    }

    @Test
    fun `de kop van een oud bestand is te lezen zonder het project te decoderen`() {
        val kop = ProjectCodec.decodeSummary(legacyV1Json())

        assertEquals(2, kop.clipCount, "kop: $kop")
        assertEquals(3_500_000L, kop.durationUs, "kop: $kop")
    }
}
