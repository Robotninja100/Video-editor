package nl.artifation.videoeditor.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectRepositoryTest {

    private val store = InMemoryProjectStore()
    private val repository = ProjectRepository(store)

    @Test
    fun `een nieuw project staat meteen in de lijst`() {
        repository.create(voorbeeldProject(), name = "Vakantie", nowMs = 42L)

        val lijst = repository.list()

        assertEquals(1, lijst.size, "lijst: $lijst")
        assertEquals("Vakantie", lijst.single().name, "lijst: $lijst")
        assertEquals(42L, lijst.single().lastModifiedMs, "lijst: $lijst")
    }

    @Test
    fun `opslaan ruimt de autosave op`() {
        repository.create(voorbeeldProject(), "Vakantie", nowMs = 1L)
        repository.autosave(voorbeeldBestand(naam = "Tussenstand", lastModifiedMs = 2L))

        repository.save(voorbeeldBestand(naam = "Definitief", lastModifiedMs = 3L))

        assertFalse(store.has("p1", ProjectSlot.AUTOSAVE), "de autosave is nu overbodig")
        assertEquals("Definitief", repository.open("p1").summary.name)
    }

    @Test
    fun `een autosave laat het opgeslagen bestand ongemoeid`() {
        repository.create(voorbeeldProject(), "Opgeslagen", nowMs = 1L)

        repository.autosave(voorbeeldBestand(naam = "Tussenstand", lastModifiedMs = 2L))

        assertEquals(
            "Opgeslagen",
            store.load("p1", ProjectSlot.MAIN).summary.name,
            "MAIN mag niet meebewegen met de autosave",
        )
    }

    @Test
    fun `de gebruiker kan de nieuwere autosave accepteren`() {
        repository.create(voorbeeldProject(), "Opgeslagen", nowMs = 1L)
        repository.autosave(voorbeeldBestand(naam = "Tussenstand", lastModifiedMs = 9L))

        val hersteld = repository.open("p1", acceptAutosave = true)

        assertEquals("Tussenstand", hersteld.summary.name, "hersteld: ${hersteld.summary}")
        // Na herstel is de tussenstand het goede bestand, en is er niets meer te kiezen.
        assertEquals(
            "Tussenstand",
            store.load("p1", ProjectSlot.MAIN).summary.name,
            "de autosave moet gepromoveerd zijn",
        )
        assertFalse(store.has("p1", ProjectSlot.AUTOSAVE), "de autosave hoort opgeruimd te zijn")
    }

    @Test
    fun `de gebruiker kan de autosave weigeren`() {
        repository.create(voorbeeldProject(), "Opgeslagen", nowMs = 1L)
        repository.autosave(voorbeeldBestand(naam = "Tussenstand", lastModifiedMs = 9L))

        val geopend = repository.open("p1", acceptAutosave = false)
        repository.discardAutosave("p1")

        assertEquals("Opgeslagen", geopend.summary.name, "geopend: ${geopend.summary}")
        assertFalse(store.has("p1", ProjectSlot.AUTOSAVE), "de geweigerde autosave moet weg")
    }

    @Test
    fun `een half geschreven autosave overschrijft het goede bestand nooit`() {
        repository.create(voorbeeldProject(), "Opgeslagen", nowMs = 1L)
        val heel = ProjectCodec.encode(voorbeeldBestand(naam = "Tussenstand", lastModifiedMs = 9L))
        store.writeRaw("p1", ProjectSlot.AUTOSAVE, heel.substring(0, heel.length / 2))

        val geopend = repository.open("p1", acceptAutosave = true)

        assertEquals("Opgeslagen", geopend.summary.name, "geopend: ${geopend.summary}")
        assertEquals(
            "Opgeslagen",
            store.load("p1", ProjectSlot.MAIN).summary.name,
            "MAIN mag niet aangeraakt zijn",
        )
        assertNull(store.readRaw("p1", ProjectSlot.AUTOSAVE), "de kapotte autosave hoort weg")
    }

    @Test
    fun `zonder opgeslagen bestand wordt de autosave hoe dan ook gepromoveerd`() {
        repository.autosave(voorbeeldBestand(naam = "Alleen dit nog", lastModifiedMs = 3L))

        val geopend = repository.open("p1", acceptAutosave = false)

        assertEquals("Alleen dit nog", geopend.summary.name, "geopend: ${geopend.summary}")
        assertTrue(store.has("p1", ProjectSlot.MAIN), "de autosave moest het goede bestand worden")
    }

    @Test
    fun `een mislukte autosave laat het opgeslagen bestand intact`() {
        repository.create(voorbeeldProject(), "Opgeslagen", nowMs = 1L)
        store.failNextWrite("p1", ProjectSlot.AUTOSAVE)

        assertFailsWith<IllegalStateException> {
            repository.autosave(voorbeeldBestand(naam = "Tussenstand", lastModifiedMs = 2L))
        }

        assertEquals("Opgeslagen", repository.open("p1").summary.name)
        assertFalse(repository.isWriting, "de schrijfvlag moet ook na een fout weer vrij zijn")
    }

    @Test
    fun `een onbekend project openen geeft een duidelijke fout`() {
        val fout = assertFailsWith<ProjectNotFoundException> { repository.open("weg") }

        assertEquals("weg", fout.id, "fout: ${fout.message}")
    }

    @Test
    fun `een onherstelbaar project openen geeft een duidelijke fout`() {
        store.writeRaw("p1", ProjectSlot.MAIN, "{ kapot")
        store.writeRaw("p1", ProjectSlot.AUTOSAVE, "")

        assertFailsWith<CorruptProjectException> { repository.open("p1") }
    }

    @Test
    fun `hernoemen houdt het project heel en werkt de tijd bij`() {
        repository.create(voorbeeldProject(), "Oude naam", nowMs = 1L)

        val hernoemd = repository.rename("p1", "Nieuwe naam", nowMs = 7L)

        assertEquals("Nieuwe naam", hernoemd.summary.name, "kop: ${hernoemd.summary}")
        assertEquals(7L, hernoemd.summary.lastModifiedMs, "kop: ${hernoemd.summary}")
        assertEquals(voorbeeldProject(), repository.open("p1").project, "de tijdlijn moest blijven")
    }

    @Test
    fun `dupliceren maakt een los project`() {
        repository.create(voorbeeldProject("p1"), "Origineel", nowMs = 1L)

        val kopie = repository.duplicate("p1", newId = "p2", name = "Kopie", nowMs = 8L)

        assertEquals("p2", kopie.project.id, "kopie: ${kopie.summary}")
        assertEquals(
            voorbeeldProject("p1").sequences,
            kopie.project.sequences,
            "de tijdlijn moest identiek zijn",
        )
        assertEquals(setOf("p1", "p2"), repository.list().map { it.id }.toSet())

        // Losstaand: het origineel bewerken raakt de kopie niet.
        repository.rename("p1", "Origineel v2", nowMs = 9L)
        assertEquals("Kopie", repository.open("p2").summary.name)
    }

    @Test
    fun `dupliceren onder dezelfde id wordt geweigerd`() {
        repository.create(voorbeeldProject(), "Origineel", nowMs = 1L)

        assertFailsWith<IllegalArgumentException>("dat zou het origineel overschrijven") {
            repository.duplicate("p1", newId = "p1", name = "Kopie", nowMs = 2L)
        }
    }

    @Test
    fun `verwijderen haalt het project uit de lijst`() {
        repository.create(voorbeeldProject(), "Weg ermee", nowMs = 1L)
        repository.autosave(voorbeeldBestand(lastModifiedMs = 2L))

        repository.delete("p1")

        assertEquals(emptyList(), repository.list(), "lijst: ${repository.list()}")
        assertFailsWith<ProjectNotFoundException> { repository.open("p1") }
    }

    @Test
    fun `twee schrijfacties door elkaar worden geweigerd`() {
        val haperend = HaperendeStore()
        val repo = ProjectRepository(haperend)
        // Autosave die precies tijdens de handmatige opslag afgaat.
        haperend.voorSchrijven = { repo.autosave(voorbeeldBestand(naam = "Tussenstand")) }

        val fout = assertFailsWith<ConcurrentWriteException> {
            repo.save(voorbeeldBestand(naam = "Definitief"))
        }

        assertEquals("p1", fout.busyWithId, "fout: ${fout.message}")
        // De andere schrijfactie is zo klaar; dit is de enige projectfout waarbij
        // de wachtrij het opnieuw mag proberen.
        assertTrue(fout.retryable, "fout: ${fout.logLine}")
    }
}

/** Store die vlak vóór het schrijven iets anders laat gebeuren; simuleert een botsing. */
private class HaperendeStore : ProjectStore {

    private val achterliggend = InMemoryProjectStore()

    /** Wordt eenmalig aangeroepen vlak voor de eerstvolgende schrijfactie. */
    var voorSchrijven: (() -> Unit)? = null

    override fun ids(): List<String> = achterliggend.ids()

    override fun readRaw(id: String, slot: ProjectSlot): String? = achterliggend.readRaw(id, slot)

    override fun writeRaw(id: String, slot: ProjectSlot, text: String) {
        val haak = voorSchrijven
        voorSchrijven = null
        haak?.invoke()
        achterliggend.writeRaw(id, slot, text)
    }

    override fun deleteSlot(id: String, slot: ProjectSlot) = achterliggend.deleteSlot(id, slot)

    override fun delete(id: String) = achterliggend.delete(id)
}
