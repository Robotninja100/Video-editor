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

    @Test
    fun `een even oude autosave voegt niets toe`() {
        store.save(voorbeeldBestand(lastModifiedMs = 5_000L))
        store.save(voorbeeldBestand(lastModifiedMs = 5_000L), ProjectSlot.AUTOSAVE)

        assertIs<RecoveryPlan.DiscardAutosave>(
            CrashRecovery.inspect(store, "p1"),
            "gelijke tijden zijn geen reden om te herstellen",
        )
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
