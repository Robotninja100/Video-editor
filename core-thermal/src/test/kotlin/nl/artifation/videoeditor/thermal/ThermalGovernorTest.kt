package nl.artifation.videoeditor.thermal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThermalGovernorTest {

    private val policy = ThermalPolicy()

    @Test
    fun `een koel toestel werkt gewoon door`() {
        val besluit = ThermalGovernor(policy).observe(ThermalStatus.NONE, nowMs = 0L)

        assertTrue(!besluit.paused, "besluit: $besluit")
        assertEquals(policy.baseChunkSize, besluit.chunkSize, "was ${besluit.chunkSize}")
        assertEquals(0L, besluit.waitMs, "was ${besluit.waitMs}")
        assertNull(besluit.reason, "was ${besluit.reason}")
    }

    @Test
    fun `een heet toestel krijgt kleinere werkblokken`() {
        val governor = ThermalGovernor(policy)

        val koel = governor.observe(ThermalStatus.NONE, 0L).chunkSize
        val licht = governor.observe(ThermalStatus.LIGHT, 1_000L).chunkSize
        val matig = governor.observe(ThermalStatus.MODERATE, 2_000L).chunkSize

        assertTrue(licht < koel, "licht ($licht) < koel ($koel)")
        assertTrue(matig < licht, "matig ($matig) < licht ($licht)")
        assertEquals(0, governor.transitions, "geen pauze, dus geen overgangen: ${governor.transitions}")
    }

    @Test
    fun `ernstige belasting pauzeert meteen, ook aan het begin van een klus`() {
        val governor = ThermalGovernor(policy)

        val besluit = governor.observe(ThermalStatus.SEVERE, 0L)

        assertTrue(besluit.paused, "besluit: $besluit")
        assertEquals(0, besluit.chunkSize, "een pauze geeft geen werk uit: ${besluit.chunkSize}")
        assertEquals(policy.cooldownMs, besluit.waitMs, "was ${besluit.waitMs}")
        assertEquals(PauseReason.OVERHEATED, besluit.reason, "was ${besluit.reason}")
        assertEquals(1, governor.transitions, "was ${governor.transitions}")
    }

    @Test
    fun `tijdens de koeltijd wordt er niet hervat, ook niet als het toestel al koel is`() {
        val governor = ThermalGovernor(policy)
        governor.observe(ThermalStatus.SEVERE, 0L)

        val besluit = governor.observe(ThermalStatus.NONE, 10_000L)

        assertTrue(besluit.paused, "besluit: $besluit")
        assertEquals(PauseReason.COOLING_DOWN, besluit.reason, "was ${besluit.reason}")
        assertEquals(10_000L, besluit.waitMs, "resterende koeltijd was ${besluit.waitMs}")
        assertEquals(1, governor.transitions, "was ${governor.transitions}")
    }

    @Test
    fun `hervatten gebeurt pas onder de lagere drempel`() {
        val governor = ThermalGovernor(policy)
        governor.observe(ThermalStatus.SEVERE, 0L)

        // Koeltijd is om, maar MODERATE ligt nog boven resumeAt: nog niet hervatten.
        val nogNiet = governor.observe(ThermalStatus.MODERATE, 20_000L)
        val welWeer = governor.observe(ThermalStatus.LIGHT, 25_000L)

        assertTrue(nogNiet.paused, "MODERATE is te warm om te hervatten: $nogNiet")
        assertEquals(PauseReason.OVERHEATED, nogNiet.reason, "was ${nogNiet.reason}")
        assertEquals(policy.recheckMs, nogNiet.waitMs, "hermeettijd was ${nogNiet.waitMs}")
        assertTrue(!welWeer.paused, "LIGHT mag hervatten: $welWeer")
        assertEquals(policy.chunkSizeFor(ThermalStatus.LIGHT), welWeer.chunkSize, "was ${welWeer.chunkSize}")
        assertEquals(2, governor.transitions, "pauzeren plus hervatten: ${governor.transitions}")
    }

    @Test
    fun `na hervatten geldt een minimale werkduur voor er opnieuw gepauzeerd mag worden`() {
        val governor = ThermalGovernor(policy)
        governor.observe(ThermalStatus.SEVERE, 0L)
        governor.observe(ThermalStatus.NONE, 20_000L) // hervat

        val binnenWerkperiode = governor.observe(ThermalStatus.SEVERE, 25_000L)
        val naWerkperiode = governor.observe(ThermalStatus.SEVERE, 50_000L)

        assertTrue(!binnenWerkperiode.paused, "de minimale werkduur loopt nog: $binnenWerkperiode")
        assertEquals(
            policy.minChunkSize,
            binnenWerkperiode.chunkSize,
            "gedwongen doorwerken mag alleen met het kleinste blok: ${binnenWerkperiode.chunkSize}",
        )
        assertTrue(naWerkperiode.paused, "na de werkperiode wordt er wel gepauzeerd: $naWerkperiode")
        assertEquals(3, governor.transitions, "was ${governor.transitions}")
    }

    @Test
    fun `kritiek negeert de minimale werkduur`() {
        val governor = ThermalGovernor(policy)
        governor.observe(ThermalStatus.SEVERE, 0L)
        governor.observe(ThermalStatus.NONE, 20_000L) // hervat

        val besluit = governor.observe(ThermalStatus.CRITICAL, 21_000L)

        assertTrue(besluit.paused, "op kritiek doorwerken omdat de klok het toestaat is precies fout: $besluit")
        assertEquals(policy.deepCooldownMs, besluit.waitMs, "diepe koeltijd was ${besluit.waitMs}")
    }

    @Test
    fun `kritiek tijdens een pauze verlengt de koeltijd zonder extra overgang`() {
        val governor = ThermalGovernor(policy)
        governor.observe(ThermalStatus.SEVERE, 0L) // koeltijd tot 20_000

        val opgelopen = governor.observe(ThermalStatus.CRITICAL, 10_000L)
        val later = governor.observe(ThermalStatus.NONE, 30_000L)

        assertEquals(policy.deepCooldownMs, opgelopen.waitMs, "koeltijd verlengd tot ${opgelopen.waitMs}")
        assertEquals(40_000L, later.waitMs, "resterend tot 70_000ms, was ${later.waitMs}")
        assertTrue(later.paused, "nog steeds dezelfde pauze: $later")
        assertEquals(1, governor.transitions, "het blijft één pauze: ${governor.transitions}")
    }

    @Test
    fun `afsluiten is geen pauze maar een einde`() {
        val governor = ThermalGovernor(policy)

        val besluit = governor.observe(ThermalStatus.SHUTDOWN, 0L)
        val nogEens = governor.observe(ThermalStatus.SHUTDOWN, 100_000L)

        assertTrue(besluit.paused, "besluit: $besluit")
        assertTrue(!besluit.resumable, "wachten heeft geen zin meer: $besluit")
        assertEquals(PauseReason.SHUTDOWN_IMMINENT, besluit.reason, "was ${besluit.reason}")
        assertTrue(!nogEens.resumable, "blijft definitief: $nogEens")
        assertEquals(1, governor.transitions, "was ${governor.transitions}")
    }

    @Test
    fun `de toestand is te bewaren en weer op te pakken`() {
        val eerste = ThermalGovernor(policy)
        eerste.observe(ThermalStatus.SEVERE, 0L)

        val hersteld = ThermalGovernor(policy, eerste.state)
        val besluit = hersteld.observe(ThermalStatus.NONE, 10_000L)

        assertTrue(besluit.paused, "de pauze overleeft het herstel: $besluit")
        assertEquals(10_000L, besluit.waitMs, "resterende koeltijd was ${besluit.waitMs}")
        assertEquals(1, hersteld.transitions, "was ${hersteld.transitions}")
    }

    @Test
    fun `een terugspringende klok wordt geweigerd`() {
        val governor = ThermalGovernor(policy)
        governor.observe(ThermalStatus.SEVERE, 10_000L)

        assertFailsWith<IllegalArgumentException> { governor.observe(ThermalStatus.NONE, 5_000L) }
    }

    @Test
    fun `wachten duurt nooit nul milliseconden zolang er gepauzeerd is`() {
        val governor = ThermalGovernor(policy)
        governor.observe(ThermalStatus.SEVERE, 0L)

        // Koeltijd om, maar nog steeds te warm: de aanroeper moet blijven wachten
        // in plaats van metingen rond te pompen.
        val besluit = governor.observe(ThermalStatus.SEVERE, 20_000L)

        assertTrue(besluit.waitMs > 0L, "wachttijd was ${besluit.waitMs}")
    }
}

/**
 * De hysterese is het deel dat het verschil maakt: zonder gat tussen de
 * drempels schommelt het systeem tussen pauzeren en hervatten zodra de status op
 * een grens balanceert.
 */
class HysteresisTest {

    /** Een realistische, heen-en-weer schommelende reeks metingen, elke vijf seconden één. */
    private val reeks = listOf(
        ThermalStatus.NONE, ThermalStatus.LIGHT, ThermalStatus.MODERATE, ThermalStatus.SEVERE,
        ThermalStatus.MODERATE, ThermalStatus.SEVERE, ThermalStatus.MODERATE, ThermalStatus.LIGHT,
        ThermalStatus.SEVERE, ThermalStatus.LIGHT, ThermalStatus.SEVERE, ThermalStatus.MODERATE,
        ThermalStatus.SEVERE, ThermalStatus.LIGHT, ThermalStatus.MODERATE, ThermalStatus.SEVERE,
        ThermalStatus.LIGHT, ThermalStatus.SEVERE, ThermalStatus.MODERATE, ThermalStatus.LIGHT,
        ThermalStatus.LIGHT, ThermalStatus.SEVERE, ThermalStatus.MODERATE, ThermalStatus.LIGHT,
    )

    private val stapMs = 5_000L

    /** Hoe vaak een domme drempelvergelijking zonder hysterese van gedrag zou wisselen. */
    private fun naieveOvergangen(reeks: List<ThermalStatus>, drempel: ThermalStatus): Int =
        reeks.zipWithNext().count { (vorige, volgende) ->
            (vorige >= drempel) != (volgende >= drempel)
        }

    @Test
    fun `schommelende metingen leveren maar weinig overgangen op`() {
        val governor = ThermalGovernor()

        reeks.forEachIndexed { index, status -> governor.observe(status, index * stapMs) }

        val naief = naieveOvergangen(reeks, ThermalStatus.SEVERE)
        assertTrue(naief >= 12, "de testreeks moet echt schommelen, naïef aantal was $naief")
        assertEquals(4, governor.transitions, "overgangen was ${governor.transitions}, naïef $naief")
    }

    @Test
    fun `het aantal overgangen blijft ver onder het naieve aantal`() {
        val governor = ThermalGovernor()

        reeks.forEachIndexed { index, status -> governor.observe(status, index * stapMs) }

        val naief = naieveOvergangen(reeks, ThermalStatus.SEVERE)
        assertTrue(
            governor.transitions * 3 <= naief,
            "overgangen ${governor.transitions} tegenover naïef $naief",
        )
    }

    @Test
    fun `elke overgang wisselt daadwerkelijk van gedrag`() {
        val governor = ThermalGovernor()
        var vorigePauze = false
        var gewisseld = 0

        reeks.forEachIndexed { index, status ->
            val besluit = governor.observe(status, index * stapMs)
            if (besluit.paused != vorigePauze) gewisseld++
            vorigePauze = besluit.paused
        }

        assertEquals(
            gewisseld,
            governor.transitions,
            "geteld $gewisseld, geboekt ${governor.transitions}",
        )
    }

    @Test
    fun `zonder hysterese zou dezelfde reeks veel vaker wisselen`() {
        // Referentiemeting: dit is precies het gedrag dat we niet willen.
        val zonder = reeks.count { it >= ThermalStatus.SEVERE }

        assertTrue(zonder >= 6, "de reeks komt $zonder keer boven de pauzeerdrempel")
        assertTrue(
            ThermalGovernor().let { governor ->
                reeks.forEachIndexed { index, status -> governor.observe(status, index * stapMs) }
                governor.transitions
            } < zonder,
            "met hysterese moeten het er minder zijn dan $zonder",
        )
    }
}
