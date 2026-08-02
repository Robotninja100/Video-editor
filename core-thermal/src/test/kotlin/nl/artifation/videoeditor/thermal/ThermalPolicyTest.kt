package nl.artifation.videoeditor.thermal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThermalPolicyTest {

    private val policy = ThermalPolicy()

    @Test
    fun `een koel toestel krijgt het volle werkblok`() {
        assertEquals(
            policy.baseChunkSize,
            policy.chunkSizeFor(ThermalStatus.NONE),
            "was ${policy.chunkSizeFor(ThermalStatus.NONE)}",
        )
    }

    @Test
    fun `een heet toestel krijgt kleinere werkblokken`() {
        val koel = policy.chunkSizeFor(ThermalStatus.NONE)
        val licht = policy.chunkSizeFor(ThermalStatus.LIGHT)
        val matig = policy.chunkSizeFor(ThermalStatus.MODERATE)

        assertTrue(licht < koel, "licht ($licht) moet kleiner zijn dan koel ($koel)")
        assertTrue(matig < licht, "matig ($matig) moet kleiner zijn dan licht ($licht)")
        assertEquals(360, licht, "was $licht")
        assertEquals(180, matig, "was $matig")
    }

    @Test
    fun `vanaf de pauzeerdrempel geldt de ondergrens`() {
        // Deze blokgrootte geldt alleen tijdens de beschermde werkperiode; dan wel zo klein mogelijk.
        assertEquals(
            policy.minChunkSize,
            policy.chunkSizeFor(ThermalStatus.SEVERE),
            "was ${policy.chunkSizeFor(ThermalStatus.SEVERE)}",
        )
        assertEquals(
            policy.minChunkSize,
            policy.chunkSizeFor(ThermalStatus.EMERGENCY),
            "was ${policy.chunkSizeFor(ThermalStatus.EMERGENCY)}",
        )
    }

    @Test
    fun `de blokgrootte zakt nooit tot nul, hoe klein de factor ook is`() {
        val krap = ThermalPolicy(baseChunkSize = 100, minChunkSize = 10, lightFactor = 0.02, moderateFactor = 0.01)

        for (status in ThermalStatus.entries) {
            val grootte = krap.chunkSizeFor(status)
            assertTrue(grootte >= krap.minChunkSize, "blokgrootte bij $status was $grootte")
        }
    }

    @Test
    fun `de blokgrootte gaat nooit boven de basisgrootte uit`() {
        val ruim = ThermalPolicy(baseChunkSize = 100, minChunkSize = 90, lightFactor = 1.0, moderateFactor = 1.0)

        assertEquals(100, ruim.chunkSizeFor(ThermalStatus.LIGHT), "was ${ruim.chunkSizeFor(ThermalStatus.LIGHT)}")
    }

    @Test
    fun `kritiek koelt langer af dan ernstig`() {
        val ernstig = policy.cooldownFor(ThermalStatus.SEVERE)
        val kritiek = policy.cooldownFor(ThermalStatus.CRITICAL)

        assertEquals(policy.cooldownMs, ernstig, "was $ernstig")
        assertEquals(policy.deepCooldownMs, kritiek, "was $kritiek")
        assertTrue(kritiek > ernstig, "kritiek ($kritiek) moet langer zijn dan ernstig ($ernstig)")
    }

    @Test
    fun `de drempels liggen uit elkaar zodat er een hysteresegat is`() {
        assertTrue(policy.shouldPause(ThermalStatus.SEVERE), "SEVERE moet pauzeren")
        assertTrue(!policy.shouldPause(ThermalStatus.MODERATE), "MODERATE moet doorwerken")
        assertTrue(!policy.mayResume(ThermalStatus.MODERATE), "MODERATE mag nog niet hervatten")
        assertTrue(policy.mayResume(ThermalStatus.LIGHT), "LIGHT mag hervatten")
    }

    @Test
    fun `een hervatdrempel gelijk aan de pauzeerdrempel wordt geweigerd`() {
        // Zonder gat tussen de drempels is er geen hysterese en gaat het systeem flapperen.
        val fout = assertFailsWith<IllegalArgumentException> {
            ThermalPolicy(pauseAt = ThermalStatus.SEVERE, resumeAt = ThermalStatus.SEVERE)
        }
        assertTrue(fout.message!!.contains("hysterese"), "boodschap was: ${fout.message}")
    }

    @Test
    fun `een blokgrootte van nul wordt geweigerd`() {
        assertFailsWith<IllegalArgumentException> { ThermalPolicy(minChunkSize = 0) }
    }

    @Test
    fun `een basisgrootte onder de ondergrens wordt geweigerd`() {
        assertFailsWith<IllegalArgumentException> { ThermalPolicy(baseChunkSize = 10, minChunkSize = 60) }
    }

    @Test
    fun `een kortere diepe koeltijd dan de gewone wordt geweigerd`() {
        assertFailsWith<IllegalArgumentException> {
            ThermalPolicy(cooldownMs = 30_000, deepCooldownMs = 10_000)
        }
    }

    @Test
    fun `een hermeettijd van nul wordt geweigerd`() {
        // Anders blijft de aanroeper metingen rondpompen zolang het toestel warm is.
        assertFailsWith<IllegalArgumentException> { ThermalPolicy(recheckMs = 0) }
    }
}
