package nl.artifation.videoeditor.project

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nl.artifation.videoeditor.model.US_PER_SECOND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectCodecTest {

    @Test
    fun `heen en weer coderen levert hetzelfde bestand op`() {
        val origineel = voorbeeldBestand()

        val terug = ProjectCodec.decode(ProjectCodec.encode(origineel))

        assertEquals(origineel, terug, "gedecodeerd: $terug")
    }

    @Test
    fun `het versienummer staat altijd in het bestand`() {
        // encodeDefaults staat uit; een schemaVersion met standaardwaarde zou
        // stilzwijgend wegvallen en het bestand onherkenbaar maken.
        val tekst = ProjectCodec.encode(voorbeeldBestand())

        assertEquals(
            CURRENT_SCHEMA_VERSION,
            ProjectCodec.versionOf(tekst),
            "versie in het bestand: $tekst",
        )
    }

    @Test
    fun `de kop bevat wat een projectenlijst nodig heeft`() {
        val kop = ProjectCodec.decodeSummary(ProjectCodec.encode(voorbeeldBestand()))

        assertEquals("p1", kop.id, "kop: $kop")
        assertEquals("Vakantie", kop.name, "kop: $kop")
        assertEquals(1_000L, kop.lastModifiedMs, "kop: $kop")
        assertEquals(3_500_000L, kop.durationUs, "kop: $kop")
        assertEquals(2, kop.clipCount, "kop: $kop")
    }

    @Test
    fun `de kop is te lezen zonder het project te decoderen`() {
        // Het project wordt met opzet onzin; alleen de kop moet nog werken. Dat
        // is precies de belofte waarop de projectenlijst leunt.
        val heel = parseJson(ProjectCodec.encode(voorbeeldBestand()))
        val stuk = printJson(
            JsonObject(heel + ("project" to buildJsonObject { put("id", "p1") })),
        )

        val kop = ProjectCodec.decodeSummary(stuk)

        assertEquals("Vakantie", kop.name, "kop: $kop")
        assertFailsWith<CorruptProjectException>("het project zelf moet wél falen") {
            ProjectCodec.decode(stuk)
        }
    }

    @Test
    fun `een leeg bestand geeft een duidelijke fout`() {
        val fout = assertFailsWith<CorruptProjectException> { ProjectCodec.decode("   ") }

        assertTrue(fout.message!!.contains("leeg"), "boodschap: ${fout.message}")
    }

    @Test
    fun `een afgekapt bestand geeft een duidelijke fout`() {
        val heel = ProjectCodec.encode(voorbeeldBestand())
        val afgekapt = heel.substring(0, heel.length / 2)

        assertFailsWith<CorruptProjectException>("halve JSON moet falen") {
            ProjectCodec.decode(afgekapt)
        }
    }

    @Test
    fun `een JSON-waarde die geen object is geeft een fout`() {
        assertFailsWith<CorruptProjectException>("een array is geen projectbestand") {
            ProjectCodec.decode("[1, 2, 3]")
        }
    }

    @Test
    fun `onbekende velden uit een nieuwere minor-versie blijven laden`() {
        val heel = parseJson(ProjectCodec.encode(voorbeeldBestand()))
        val metExtra = printJson(JsonObject(heel + ("watIsDit" to JsonPrimitive("nieuw"))))

        val bestand = ProjectCodec.decode(metExtra)

        assertEquals("Vakantie", bestand.summary.name, "gedecodeerd: ${bestand.summary}")
    }

    @Test
    fun `een kop die niet bij het project hoort wordt geweigerd`() {
        val heel = parseJson(ProjectCodec.encode(voorbeeldBestand(id = "p1")))
        val kop = JsonObject(
            (heel["summary"] as JsonObject) + ("id" to JsonPrimitive("p2")),
        )
        val gemengd = printJson(JsonObject(heel + ("summary" to kop)))

        val fout = assertFailsWith<CorruptProjectException> { ProjectCodec.decode(gemengd) }

        assertTrue(fout.message!!.contains("inconsistent"), "boodschap: ${fout.message}")
    }

    @Test
    fun `duur en clipCount worden uit het project afgeleid, niet uit de kop`() {
        // Een met de hand aangepaste of door een oudere versie geschreven kop
        // mag nooit de waarheid worden zodra het project er tóch al is.
        val heel = parseJson(ProjectCodec.encode(voorbeeldBestand()))
        val kop = JsonObject(
            (heel["summary"] as JsonObject) +
                ("durationUs" to JsonPrimitive(99L)) +
                ("clipCount" to JsonPrimitive(99)),
        )

        val bestand = ProjectCodec.decode(printJson(JsonObject(heel + ("summary" to kop))))

        assertEquals(3_500_000L, bestand.summary.durationUs, "kop: ${bestand.summary}")
        assertEquals(2, bestand.summary.clipCount, "kop: ${bestand.summary}")
    }

    @Test
    fun `een leeg project heeft duur nul en geen clips`() {
        val bestand = ProjectFile.of(project(id = "leeg"), "Leeg", lastModifiedMs = 5L)

        val terug = ProjectCodec.decode(ProjectCodec.encode(bestand))

        assertEquals(0L, terug.summary.durationUs, "kop: ${terug.summary}")
        assertEquals(0, terug.summary.clipCount, "kop: ${terug.summary}")
    }

    @Test
    fun `snelheid telt mee in de duur in de kop`() {
        val bestand = ProjectFile.of(
            project("snel", clip("a", outPointUs = 2 * US_PER_SECOND, speed = 2f)),
            "Snel",
            lastModifiedMs = 0L,
        )

        assertEquals(US_PER_SECOND, bestand.summary.durationUs, "kop: ${bestand.summary}")
    }
}
