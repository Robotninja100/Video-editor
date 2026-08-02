package nl.artifation.videoeditor.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryProjectStoreTest {

    private val store = InMemoryProjectStore()

    @Test
    fun `opslaan en laden geeft hetzelfde bestand terug`() {
        val bestand = voorbeeldBestand()

        store.save(bestand)

        assertEquals(bestand, store.load("p1"), "geladen: ${store.load("p1")}")
    }

    @Test
    fun `een onbekend project laden geeft een duidelijke fout`() {
        val fout = assertFailsWith<ProjectNotFoundException> { store.load("bestaat-niet") }

        assertEquals("bestaat-niet", fout.id, "fout: ${fout.message}")
    }

    @Test
    fun `de autosave staat los van het opgeslagen bestand`() {
        store.save(voorbeeldBestand(naam = "Opgeslagen"))
        store.save(voorbeeldBestand(naam = "Autosave"), ProjectSlot.AUTOSAVE)

        assertEquals("Opgeslagen", store.load("p1", ProjectSlot.MAIN).summary.name)
        assertEquals("Autosave", store.load("p1", ProjectSlot.AUTOSAVE).summary.name)
    }

    @Test
    fun `verwijderen haalt beide slots weg`() {
        store.save(voorbeeldBestand())
        store.save(voorbeeldBestand(), ProjectSlot.AUTOSAVE)

        store.delete("p1")

        assertNull(store.readRaw("p1", ProjectSlot.MAIN), "MAIN bleef staan")
        assertNull(store.readRaw("p1", ProjectSlot.AUTOSAVE), "AUTOSAVE bleef staan")
        assertEquals(emptyList(), store.ids(), "ids: ${store.ids()}")
    }

    @Test
    fun `een slot verwijderen laat het andere staan`() {
        store.save(voorbeeldBestand())
        store.save(voorbeeldBestand(), ProjectSlot.AUTOSAVE)

        store.deleteSlot("p1", ProjectSlot.AUTOSAVE)

        assertTrue(store.has("p1", ProjectSlot.MAIN), "MAIN moest blijven")
        assertFalse(store.has("p1", ProjectSlot.AUTOSAVE), "AUTOSAVE moest weg")
    }

    @Test
    fun `de lijst staat op nieuwste eerst`() {
        store.save(voorbeeldBestand(id = "oud", lastModifiedMs = 100L))
        store.save(voorbeeldBestand(id = "nieuw", lastModifiedMs = 900L))
        store.save(voorbeeldBestand(id = "midden", lastModifiedMs = 500L))

        val ids = store.summaries().map { it.id }

        assertEquals(listOf("nieuw", "midden", "oud"), ids, "volgorde: $ids")
    }

    @Test
    fun `de lijst kijkt niet naar autosaves`() {
        store.save(voorbeeldBestand(id = "alleen-autosave"), ProjectSlot.AUTOSAVE)

        assertEquals(emptyList(), store.summaries(), "lijst: ${store.summaries()}")
    }

    @Test
    fun `een corrupt bestand sloopt de lijst niet`() {
        store.save(voorbeeldBestand(id = "goed"))
        store.writeRaw("stuk", ProjectSlot.MAIN, "{ dit is geen json")

        val ids = store.summaries().map { it.id }

        assertEquals(listOf("goed"), ids, "lijst: $ids")
    }

    @Test
    fun `een onleesbaar project blijft zichtbaar als probleem`() {
        store.save(voorbeeldBestand(id = "goed"))
        store.writeRaw("stuk", ProjectSlot.MAIN, "")
        store.writeRaw(
            "te-nieuw",
            ProjectSlot.MAIN,
            metSchemaVersie(ProjectCodec.encode(voorbeeldBestand()), CURRENT_SCHEMA_VERSION + 1),
        )

        assertEquals(
            setOf("stuk", "te-nieuw"),
            store.unreadableIds().toSet(),
            "problemen: ${store.unreadableIds()}",
        )
    }

    @Test
    fun `een mislukte schrijfactie laat het bestaande bestand intact`() {
        store.save(voorbeeldBestand(naam = "Goed"))
        store.failNextWrite("p1", ProjectSlot.MAIN)

        assertFailsWith<IllegalStateException>("de gesimuleerde fout moet doorkomen") {
            store.save(voorbeeldBestand(naam = "Half"))
        }
        assertEquals("Goed", store.load("p1").summary.name, "het oude bestand moest blijven")
    }

    @Test
    fun `ids somt elk project één keer op`() {
        store.save(voorbeeldBestand(id = "p1"))
        store.save(voorbeeldBestand(id = "p1"), ProjectSlot.AUTOSAVE)
        store.save(voorbeeldBestand(id = "p2"))

        assertEquals(setOf("p1", "p2"), store.ids().toSet(), "ids: ${store.ids()}")
        assertEquals(2, store.ids().size, "dubbele ids: ${store.ids()}")
    }

    @Test
    fun `de lijst leest oude bestanden mee`() {
        store.writeRaw("oud", ProjectSlot.MAIN, legacyV1Json(voorbeeldProject("oud")))

        val kop = store.summaries().single()

        assertEquals("oud", kop.id, "kop: $kop")
        assertEquals(3_500_000L, kop.durationUs, "kop: $kop")
    }
}
