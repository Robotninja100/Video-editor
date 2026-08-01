package nl.artifation.videoeditor.thermal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThermalStatusTest {

    @Test
    fun `de niveaus zijn oplopend geordend zodat je ze kunt vergelijken`() {
        val oplopend = listOf(
            ThermalStatus.NONE,
            ThermalStatus.LIGHT,
            ThermalStatus.MODERATE,
            ThermalStatus.SEVERE,
            ThermalStatus.CRITICAL,
            ThermalStatus.EMERGENCY,
            ThermalStatus.SHUTDOWN,
        )

        assertEquals(oplopend, ThermalStatus.entries.toList(), "volgorde: ${ThermalStatus.entries}")
        assertEquals(oplopend, oplopend.shuffled().sorted(), "sorteren moet dezelfde volgorde geven")
    }

    @Test
    fun `atLeast vergelijkt op ernst`() {
        assertTrue(ThermalStatus.SEVERE.atLeast(ThermalStatus.MODERATE), "SEVERE is erger dan MODERATE")
        assertTrue(ThermalStatus.SEVERE.atLeast(ThermalStatus.SEVERE), "gelijk telt mee")
        assertTrue(
            !ThermalStatus.LIGHT.atLeast(ThermalStatus.MODERATE),
            "LIGHT is niet erger dan MODERATE",
        )
    }

    @Test
    fun `fromAndroidLevel vertaalt de bekende niveaus een op een`() {
        val verwacht = ThermalStatus.entries.toList()

        val vertaald = verwacht.indices.map { ThermalStatus.fromAndroidLevel(it) }

        assertEquals(verwacht, vertaald, "vertaald: $vertaald")
    }

    @Test
    fun `een onbekend niveau telt als geen belasting`() {
        assertEquals(
            ThermalStatus.NONE,
            ThermalStatus.fromAndroidLevel(-1),
            "was ${ThermalStatus.fromAndroidLevel(-1)}",
        )
    }

    @Test
    fun `een niveau boven het hoogst bekende telt als het zwaarste`() {
        // Veiligste aanname bij een nieuwer Android met een extra niveau.
        assertEquals(
            ThermalStatus.SHUTDOWN,
            ThermalStatus.fromAndroidLevel(99),
            "was ${ThermalStatus.fromAndroidLevel(99)}",
        )
    }
}
