package nl.artifation.videoeditor.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Een autosave die halverwege het schrijven is afgebroken. */
private fun halfGeschreven(): String {
    val heel = ProjectCodec.encode(voorbeeldBestand(naam = "Autosave"))
    return heel.substring(0, heel.length * 2 / 3)
}

class CrashRecoveryTest {

    private val store = InMemoryProjectStore()

    @Test
    fun `zonder bestanden valt er niets te openen`() {
        assertEquals(RecoveryPlan.NotFound("p1"), CrashRecovery.inspect(store, "p1"))
    }

    @Test
    fun `alleen een opgeslagen bestand wordt gewoon geopend`() {
        store.save(voorbeeldBestand())

        val plan = CrashRecovery.inspect(store, "p1")

        assertIs<RecoveryPlan.OpenSaved>(plan, "plan: $plan")
        assertEquals("Vakantie", plan.saved.name, "plan: $plan")
    }

    @Test
    fun `een nieuwere autosave wordt aangeboden`() {
        store.save(voorbeeldBestand(naam = "Opgeslagen", lastModifiedMs = 1_000L))
        store.save(
            voorbeeldBestand(naam = "Autosave", lastModifiedMs = 4_000L),
            ProjectSlot.AUTOSAVE,
        )

        val plan = CrashRecovery.inspect(store, "p1")

        assertIs<RecoveryPlan.OfferAutosave>(plan, "plan: $plan")
        assertEquals("Autosave", plan.autosave.name, "plan: $plan")
        assertEquals(3_000L, plan.newerByMs, "plan: $plan")
    }

    @Test
    fun `een oudere autosave wordt weggegooid`() {
        store.save(voorbeeldBestand(naam = "Opgeslagen", lastModifiedMs = 5_000L))
        store.save(
            voorbeeldBestand(naam = "Autosave", lastModifiedMs = 1_000L),
            ProjectSlot.AUTOSAVE,
        )

        val plan = CrashRecovery.inspect(store, "p1")

        assertIs<RecoveryPlan.DiscardAutosave>(plan, "plan: $plan")
        assertEquals(
            RecoveryPlan.DiscardReason.AUTOSAVE_NOT_NEWER,
            plan.reason,
            "plan: $plan",
        )
    }

    /**
     * Deze test legde eerder het omgekeerde vast: bij gelijke tijden werd de
     * autosave weggegooid. Maar gelijk volgnummer én gelijke tijd betekent dat de
     * volgorde onbekend is, en dan is weggooien de onomkeerbare richting. De
     * gebruiker hoort te kiezen.
     */
    @Test
    fun `bij een onbekende volgorde wordt de keuze voorgelegd`() {
        store.save(voorbeeldBestand(lastModifiedMs = 5_000L))
        store.save(voorbeeldBestand(lastModifiedMs = 5_000L), ProjectSlot.AUTOSAVE)

        assertIs<RecoveryPlan.OfferAutosave>(
            CrashRecovery.inspect(store, "p1"),
            "onbekende volgorde mag geen stilzwijgende verwijdering opleveren",
        )
    }

    @Test
    fun `een oudere autosave wordt nog steeds weggegooid`() {
        store.save(voorbeeldBestand(lastModifiedMs = 5_000L))
        store.save(voorbeeldBestand(lastModifiedMs = 1_000L), ProjectSlot.AUTOSAVE)

        assertIs<RecoveryPlan.DiscardAutosave>(CrashRecovery.inspect(store, "p1"))
    }

    @Test
    fun `een half geschreven autosave verliest van het opgeslagen bestand`() {
        store.save(voorbeeldBestand(naam = "Opgeslagen", lastModifiedMs = 1_000L))
        store.writeRaw("p1", ProjectSlot.AUTOSAVE, halfGeschreven())

        val plan = CrashRecovery.inspect(store, "p1")

        assertIs<RecoveryPlan.DiscardAutosave>(plan, "plan: $plan")
        assertEquals(
            RecoveryPlan.DiscardReason.AUTOSAVE_UNREADABLE,
            plan.reason,
            "plan: $plan",
        )
        assertEquals("Opgeslagen", plan.saved.name, "plan: $plan")
    }

    @Test
    fun `een lege autosave telt als onleesbaar`() {
        store.save(voorbeeldBestand())
        store.writeRaw("p1", ProjectSlot.AUTOSAVE, "")

        assertIs<RecoveryPlan.DiscardAutosave>(
            CrashRecovery.inspect(store, "p1"),
            "een bestand van nul bytes is het normale gevolg van een crash",
        )
    }

    @Test
    fun `zonder opgeslagen bestand is de autosave het enige wat er is`() {
        store.save(voorbeeldBestand(naam = "Autosave"), ProjectSlot.AUTOSAVE)

        val plan = CrashRecovery.inspect(store, "p1")

        assertIs<RecoveryPlan.OfferAutosave>(plan, "plan: $plan")
        assertNull(plan.saved, "er hoort geen opgeslagen bestand te zijn: $plan")
    }

    @Test
    fun `een stuk opgeslagen bestand laat de autosave het overnemen`() {
        store.writeRaw("p1", ProjectSlot.MAIN, "{ kapot")
        store.save(voorbeeldBestand(naam = "Autosave"), ProjectSlot.AUTOSAVE)

        val plan = CrashRecovery.inspect(store, "p1")

        assertIs<RecoveryPlan.OfferAutosave>(plan, "plan: $plan")
        assertEquals("Autosave", plan.autosave.name, "plan: $plan")
    }

    @Test
    fun `twee kapotte bestanden zijn niet te herstellen`() {
        store.writeRaw("p1", ProjectSlot.MAIN, "{ kapot")
        store.writeRaw("p1", ProjectSlot.AUTOSAVE, "")

        assertIs<RecoveryPlan.Unrecoverable>(CrashRecovery.inspect(store, "p1"))
    }

    @Test
    fun `een bestand uit een nieuwere app is geen herstelgeval`() {
        // Terugvallen op een oudere autosave zou hier de nieuwere montage
        // weggooien; dus komt de fout naar boven in plaats van een plan.
        store.writeRaw(
            "p1",
            ProjectSlot.MAIN,
            metSchemaVersie(ProjectCodec.encode(voorbeeldBestand()), CURRENT_SCHEMA_VERSION + 1),
        )
        store.save(voorbeeldBestand(lastModifiedMs = 9_000L), ProjectSlot.AUTOSAVE)

        assertFailsWith<UnsupportedSchemaVersionException> { CrashRecovery.inspect(store, "p1") }
    }
}

/**
 * Het volgnummer bestaat omdat de wandklok op een telefoon geen betrouwbare
 * volgorde geeft: een NTP-correctie of tijdzone-update kan hem terugzetten.
 */
class VolgnummerOrdeningTest {

    private val store = InMemoryProjectStore()

    private fun bestand(revision: Long, lastModifiedMs: Long) = ProjectFile.of(
        project = voorbeeldProject(),
        name = "Vakantie",
        lastModifiedMs = lastModifiedMs,
        revision = revision,
    )

    @Test
    fun `een hoger volgnummer wint van een latere klok`() {
        // De klok is 30 seconden teruggezet nadat het hoofdbestand werd opgeslagen.
        store.save(bestand(revision = 4, lastModifiedMs = 1_700_000_000_000L))
        store.save(bestand(revision = 5, lastModifiedMs = 1_699_999_970_000L), ProjectSlot.AUTOSAVE)

        assertIs<RecoveryPlan.OfferAutosave>(
            CrashRecovery.inspect(store, "p1"),
            "het nieuwste werk mag niet sneuvelen door een klokcorrectie",
        )
    }

    @Test
    fun `een lager volgnummer verliest ook bij een latere klok`() {
        store.save(bestand(revision = 9, lastModifiedMs = 1_000L))
        store.save(bestand(revision = 8, lastModifiedMs = 9_000L), ProjectSlot.AUTOSAVE)

        assertIs<RecoveryPlan.DiscardAutosave>(CrashRecovery.inspect(store, "p1"))
    }

    @Test
    fun `bij gelijke volgnummers beslist de klok alsnog`() {
        store.save(bestand(revision = 3, lastModifiedMs = 1_000L))
        store.save(bestand(revision = 3, lastModifiedMs = 9_000L), ProjectSlot.AUTOSAVE)

        assertIs<RecoveryPlan.OfferAutosave>(
            CrashRecovery.inspect(store, "p1"),
            "bestanden uit v3 hebben geen volgnummer; dan is de klok wat er is",
        )
    }

    @Test
    fun `opslaan hoogt het volgnummer op`() {
        val eerste = ProjectFile.of(voorbeeldProject(), "Vakantie", lastModifiedMs = 0L)
        val tweede = eerste.withProject(voorbeeldProject(), nowMs = 1_000L)
        val derde = tweede.renamed("Andere naam", nowMs = 2_000L)

        assertEquals(0L, eerste.summary.revision)
        assertEquals(1L, tweede.summary.revision)
        assertEquals(2L, derde.summary.revision, "hernoemen is ook een schrijfactie")
    }
}
