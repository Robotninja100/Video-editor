package nl.artifation.videoeditor.thermal

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Uitkomst van een gesimuleerde klus; alles synchroon, met een klok als teller. */
private data class Simulatie(
    val chunks: List<WorkChunk>,
    val voortgang: List<Double>,
    val pauzes: Int,
    val stappen: Int,
    val elapsedMs: Long,
    val gestopt: Boolean,
)

/**
 * Draait een klus af met een gesimuleerde klok: werken kost tijd per eenheid,
 * wachten schuift de klok op. Geen threads, geen slaap.
 */
private fun simuleer(
    scheduler: ChunkScheduler,
    maxStappen: Int = 5_000,
    msPerEenheid: (ThermalStatus) -> Long = { 1L },
    status: (nowMs: Long, completed: Int) -> ThermalStatus,
): Simulatie {
    val chunks = mutableListOf<WorkChunk>()
    val voortgang = mutableListOf<Double>()
    var now = 0L
    var pauzes = 0
    var stappen = 0
    var gestopt = false

    while (stappen < maxStappen) {
        stappen++
        val gemeten = status(now, scheduler.completedUnits)
        when (val plan = scheduler.next(gemeten, now)) {
            is ChunkPlan.Work -> {
                chunks += plan.chunk
                now += plan.chunk.size * msPerEenheid(gemeten)
                scheduler.complete(plan.chunk, now)
            }
            is ChunkPlan.Pause -> {
                pauzes++
                now += plan.waitMs
            }
            is ChunkPlan.Stopped -> {
                gestopt = true
                voortgang += scheduler.progress
                break
            }
            ChunkPlan.Done -> {
                voortgang += scheduler.progress
                break
            }
        }
        voortgang += scheduler.progress
    }
    return Simulatie(chunks, voortgang, pauzes, stappen, now, gestopt)
}

/** Simpel warmtemodel: werken verwarmt, wachten koelt af. */
private class Thermostaat(
    private val warmtePerEenheid: Double = 1.0 / 400,
    private val koelingPerMs: Double = 1.0 / 20_000,
) {
    private var laatstVoltooid = 0
    private var laatsteMs = 0L
    private var warmte = 0.0

    fun meet(nowMs: Long, completed: Int): ThermalStatus {
        val gedaan = completed - laatstVoltooid
        if (gedaan > 0) {
            warmte += gedaan * warmtePerEenheid
        } else {
            warmte = max(0.0, warmte - (nowMs - laatsteMs) * koelingPerMs)
        }
        laatstVoltooid = completed
        laatsteMs = nowMs
        // Nooit tot SHUTDOWN: dat is een apart, definitief geval.
        return ThermalStatus.entries[warmte.toInt().coerceIn(0, ThermalStatus.CRITICAL.ordinal)]
    }
}

/** Controleert de kerneis: geen gat, geen overlap, precies één keer alles. */
private fun assertDekkend(chunks: List<WorkChunk>, totaal: Int) {
    var cursor = 0
    for (chunk in chunks) {
        assertEquals(cursor, chunk.start, "blok $chunk sluit niet aan op $cursor (alle blokken: $chunks)")
        cursor = chunk.endExclusive
    }
    assertEquals(totaal, cursor, "samen dekken de blokken $cursor van $totaal eenheden")
    assertEquals(totaal, chunks.sumOf { it.size }, "som van de blokgroottes was ${chunks.sumOf { it.size }}")
}

class ChunkSchedulerTest {

    @Test
    fun `een koele klus wordt in gelijke blokken afgewerkt`() {
        val scheduler = ChunkScheduler(totalUnits = 18_000)

        val simulatie = simuleer(scheduler) { _, _ -> ThermalStatus.NONE }

        assertDekkend(simulatie.chunks, 18_000)
        assertEquals(30, simulatie.chunks.size, "18.000 / 600, was ${simulatie.chunks.size}")
        assertEquals(0, simulatie.pauzes, "een koel toestel pauzeert niet, was ${simulatie.pauzes}")
        assertTrue(scheduler.isDone, "voortgang: ${scheduler.completedUnits}/${scheduler.totalUnits}")
    }

    @Test
    fun `een blokgrootte die halverwege verandert laat geen gat achter`() {
        val scheduler = ChunkScheduler(totalUnits = 5_000)

        val simulatie = simuleer(scheduler) { _, completed ->
            when {
                completed < 1_200 -> ThermalStatus.NONE
                completed < 3_000 -> ThermalStatus.MODERATE
                else -> ThermalStatus.LIGHT
            }
        }

        assertDekkend(simulatie.chunks, 5_000)
        val groottes = simulatie.chunks.map { it.size }.distinct()
        assertTrue(groottes.size >= 3, "de blokgrootte moet echt gewisseld hebben: $groottes")
    }

    @Test
    fun `het laatste blok is precies de rest en loopt niet over het einde heen`() {
        val scheduler = ChunkScheduler(totalUnits = 700)

        val simulatie = simuleer(scheduler) { _, _ -> ThermalStatus.NONE }

        assertEquals(
            listOf(WorkChunk(0, 600), WorkChunk(600, 700)),
            simulatie.chunks,
            "was ${simulatie.chunks}",
        )
    }

    @Test
    fun `bij oplopende hitte worden de blokken kleiner`() {
        val scheduler = ChunkScheduler(totalUnits = 10_000)
        val groottes = mutableListOf<Int>()
        var now = 0L

        for (status in listOf(ThermalStatus.NONE, ThermalStatus.LIGHT, ThermalStatus.MODERATE)) {
            val plan = scheduler.next(status, now) as ChunkPlan.Work
            groottes += plan.chunk.size
            now += 1_000L
            scheduler.complete(plan.chunk, now)
        }

        assertEquals(listOf(600, 360, 180), groottes, "was $groottes")
    }

    @Test
    fun `bij oplopende hitte daalt de doorvoer, maar de klus komt af`() {
        val koel = ChunkScheduler(totalUnits = 3_000)
        val heet = ChunkScheduler(totalUnits = 3_000)
        val thermostaat = Thermostaat()

        val koeleRun = simuleer(koel) { _, _ -> ThermalStatus.NONE }
        val heteRun = simuleer(
            heet,
            // Een terugschakelende chip doet er ruwweg twee keer zo lang over.
            msPerEenheid = { status -> if (status >= ThermalStatus.MODERATE) 2L else 1L },
        ) { now, completed -> thermostaat.meet(now, completed) }

        assertEquals(3_000, koel.completedUnits, "koele klus: ${koel.completedUnits}")
        assertEquals(3_000, heet.completedUnits, "hete klus: ${heet.completedUnits}")
        assertTrue(heteRun.pauzes > 0, "een hete klus hoort te pauzeren, was ${heteRun.pauzes}")
        assertTrue(
            heteRun.elapsedMs > koeleRun.elapsedMs,
            "heet ${heteRun.elapsedMs}ms tegenover koel ${koeleRun.elapsedMs}ms",
        )
        assertDekkend(heteRun.chunks, 3_000)
    }

    @Test
    fun `voortgang loopt monotoon op tot precies honderd procent`() {
        val scheduler = ChunkScheduler(totalUnits = 3_000)
        val thermostaat = Thermostaat()

        val simulatie = simuleer(scheduler) { now, completed -> thermostaat.meet(now, completed) }

        simulatie.voortgang.zipWithNext().forEach { (vorige, volgende) ->
            assertTrue(volgende >= vorige, "voortgang zakte van $vorige naar $volgende")
        }
        assertEquals(1.0, simulatie.voortgang.last(), "eindstand was ${simulatie.voortgang.last()}")
    }

    @Test
    fun `permanente kritiek levert geen voortgang op maar ook geen schade`() {
        val scheduler = ChunkScheduler(totalUnits = 1_000)

        val simulatie = simuleer(scheduler, maxStappen = 50) { _, _ -> ThermalStatus.CRITICAL }

        assertEquals(0, scheduler.completedUnits, "was ${scheduler.completedUnits}")
        assertEquals(50, simulatie.pauzes, "elke stap is een pauze, was ${simulatie.pauzes}")
        assertTrue(simulatie.elapsedMs > 0L, "de klok moet vooruit lopen, was ${simulatie.elapsedMs}")
    }

    @Test
    fun `een klus die op kritiek begint werkt niet meteen`() {
        val scheduler = ChunkScheduler(totalUnits = 100)

        val plan = assertIs<ChunkPlan.Pause>(
            scheduler.next(ThermalStatus.CRITICAL, nowMs = 0L),
            "er hoort niet gewerkt te worden",
        )

        assertEquals(PauseReason.OVERHEATED, plan.reason, "was ${plan.reason}")
        assertEquals(60_000L, plan.waitMs, "diepe koeltijd was ${plan.waitMs}")
        assertEquals(0, scheduler.completedUnits, "was ${scheduler.completedUnits}")
    }

    @Test
    fun `een klus zonder werk is meteen klaar`() {
        val scheduler = ChunkScheduler(totalUnits = 0)

        val plan = scheduler.next(ThermalStatus.CRITICAL, 0L)

        assertEquals(ChunkPlan.Done, plan, "was $plan")
        assertTrue(scheduler.isDone, "een lege klus is af")
        assertEquals(1.0, scheduler.progress, "was ${scheduler.progress}")
    }

    @Test
    fun `bij afsluiten stopt de scheduler definitief`() {
        val scheduler = ChunkScheduler(totalUnits = 1_000)

        val simulatie = simuleer(scheduler) { _, _ -> ThermalStatus.SHUTDOWN }

        assertTrue(simulatie.gestopt, "de scheduler hoort te stoppen, niet te wachten")
        assertEquals(0, scheduler.completedUnits, "was ${scheduler.completedUnits}")
        val nogEens = scheduler.next(ThermalStatus.SHUTDOWN, 100_000L)
        assertTrue(nogEens is ChunkPlan.Stopped, "blijft gestopt, was $nogEens")
    }

    @Test
    fun `een negatieve klusgrootte wordt geweigerd`() {
        assertFailsWith<IllegalArgumentException> { ChunkScheduler(totalUnits = -1) }
    }
}

class ChunkBoekhoudingTest {

    @Test
    fun `twee metingen achter elkaar geven niet twee verschillende blokken uit`() {
        val scheduler = ChunkScheduler(totalUnits = 5_000)

        val eerste = scheduler.next(ThermalStatus.NONE, 0L) as ChunkPlan.Work
        val tweede = scheduler.next(ThermalStatus.SEVERE, 1_000L) as ChunkPlan.Work

        assertEquals(eerste.chunk, tweede.chunk, "was ${tweede.chunk} in plaats van ${eerste.chunk}")
        assertEquals(0, scheduler.completedUnits, "nog niets voltooid, was ${scheduler.completedUnits}")
    }

    @Test
    fun `hetzelfde blok twee keer terugmelden telt maar een keer`() {
        val scheduler = ChunkScheduler(totalUnits = 5_000)
        val plan = scheduler.next(ThermalStatus.NONE, 0L) as ChunkPlan.Work

        scheduler.complete(plan.chunk, 1_000L)
        scheduler.complete(plan.chunk, 2_000L)

        assertEquals(600, scheduler.completedUnits, "was ${scheduler.completedUnits}")
        assertEquals(1, scheduler.chunksCompleted, "was ${scheduler.chunksCompleted}")
    }

    @Test
    fun `een blok dat niet aansluit wordt geweigerd`() {
        val scheduler = ChunkScheduler(totalUnits = 5_000)

        val fout = assertFailsWith<IllegalArgumentException> {
            scheduler.complete(WorkChunk(2_000, 2_600), 1_000L)
        }
        assertTrue(fout.message!!.contains("sluit niet aan"), "boodschap was: ${fout.message}")
    }

    @Test
    fun `een leeg blok bestaat niet`() {
        assertFailsWith<IllegalArgumentException> { WorkChunk(10, 10) }
    }

    @Test
    fun `een losgelaten blok wordt opnieuw uitgegeven`() {
        val scheduler = ChunkScheduler(totalUnits = 5_000)
        val eerste = (scheduler.next(ThermalStatus.NONE, 0L) as ChunkPlan.Work).chunk

        scheduler.abandon()
        val opnieuw = (scheduler.next(ThermalStatus.NONE, 1_000L) as ChunkPlan.Work).chunk

        assertEquals(eerste, opnieuw, "was $opnieuw in plaats van $eerste")
        assertEquals(0, scheduler.completedUnits, "een losgelaten blok telt niet, was ${scheduler.completedUnits}")
    }

    @Test
    fun `een halverwege afgebroken blok laat de rest openstaan`() {
        val scheduler = ChunkScheduler(totalUnits = 5_000)
        val chunk = (scheduler.next(ThermalStatus.NONE, 0L) as ChunkPlan.Work).chunk

        scheduler.completePartially(chunk, unitsDone = 250, nowMs = 1_000L)
        val volgende = (scheduler.next(ThermalStatus.NONE, 2_000L) as ChunkPlan.Work).chunk

        assertEquals(250, scheduler.completedUnits, "was ${scheduler.completedUnits}")
        assertEquals(250, volgende.start, "moet aansluiten op 250, was ${volgende.start}")
        assertEquals(0, scheduler.chunksCompleted, "het blok is niet af, was ${scheduler.chunksCompleted}")
    }

    @Test
    fun `meer eenheden terugmelden dan het blok groot is wordt geweigerd`() {
        val scheduler = ChunkScheduler(totalUnits = 5_000)
        val chunk = (scheduler.next(ThermalStatus.NONE, 0L) as ChunkPlan.Work).chunk

        assertFailsWith<IllegalArgumentException> {
            scheduler.completePartially(chunk, unitsDone = chunk.size + 1, nowMs = 1_000L)
        }
    }

    @Test
    fun `de blokken zijn nooit kleiner dan de ondergrens, behalve het laatste`() {
        val scheduler = ChunkScheduler(totalUnits = 4_321)
        val thermostaat = Thermostaat()

        val simulatie = simuleer(scheduler) { now, completed -> thermostaat.meet(now, completed) }

        simulatie.chunks.dropLast(1).forEach { chunk ->
            assertTrue(chunk.size >= scheduler.policy.minChunkSize, "te klein blok: $chunk")
        }
        assertDekkend(simulatie.chunks, 4_321)
    }
}

class HervattenTest {

    @Test
    fun `hervatten pakt precies op waar het gebleven was`() {
        val eerste = ChunkScheduler(totalUnits = 5_000)
        val gedaan = mutableListOf<WorkChunk>()
        var now = 0L
        repeat(3) {
            val plan = eerste.next(ThermalStatus.NONE, now) as ChunkPlan.Work
            gedaan += plan.chunk
            now += 1_000L
            eerste.complete(plan.chunk, now)
        }

        val hersteld = ChunkScheduler.restore(eerste.snapshot())
        val simulatie = simuleer(hersteld) { _, _ -> ThermalStatus.NONE }

        assertEquals(1_800, eerste.completedUnits, "was ${eerste.completedUnits}")
        assertEquals(
            1_800,
            simulatie.chunks.first().start,
            "hervat op ${simulatie.chunks.first().start} in plaats van 1.800",
        )
        assertDekkend(gedaan + simulatie.chunks, 5_000)
        assertTrue(hersteld.isDone, "hersteld: ${hersteld.completedUnits}/${hersteld.totalUnits}")
    }

    @Test
    fun `een blok dat onderhanden was gaat niet verloren maar wordt overgedaan`() {
        val eerste = ChunkScheduler(totalUnits = 5_000)
        val plan = eerste.next(ThermalStatus.NONE, 0L) as ChunkPlan.Work

        // De momentopname bevat bewust geen onderhanden werk: niet teruggemeld is niet gedaan.
        val hersteld = ChunkScheduler.restore(eerste.snapshot())
        val opnieuw = (hersteld.next(ThermalStatus.NONE, 0L) as ChunkPlan.Work).chunk

        assertEquals(plan.chunk, opnieuw, "was $opnieuw in plaats van ${plan.chunk}")
        assertEquals(0, hersteld.completedUnits, "was ${hersteld.completedUnits}")
    }

    @Test
    fun `de thermische toestand overleeft het hervatten`() {
        val eerste = ChunkScheduler(totalUnits = 5_000)
        eerste.next(ThermalStatus.SEVERE, 0L)

        val hersteld = ChunkScheduler.restore(eerste.snapshot())
        val plan = assertIs<ChunkPlan.Pause>(
            hersteld.next(ThermalStatus.NONE, 10_000L),
            "de pauze hoort het herstel te overleven",
        )

        assertEquals(PauseReason.COOLING_DOWN, plan.reason, "was ${plan.reason}")
        assertEquals(10_000L, plan.waitMs, "resterende koeltijd was ${plan.waitMs}")
    }

    @Test
    fun `een momentopname die verder is dan de aanroeper denkt telt niet dubbel`() {
        val scheduler = ChunkScheduler.restore(SchedulerState(totalUnits = 5_000, completedUnits = 1_200))

        // Een blok van vóór de momentopname wordt stil genegeerd.
        scheduler.complete(WorkChunk(600, 1_200), 1_000L)

        assertEquals(1_200, scheduler.completedUnits, "was ${scheduler.completedUnits}")
        val volgende = (scheduler.next(ThermalStatus.NONE, 2_000L) as ChunkPlan.Work).chunk
        assertEquals(1_200, volgende.start, "was ${volgende.start}")
    }

    @Test
    fun `een momentopname met meer voltooid dan totaal wordt geweigerd`() {
        assertFailsWith<IllegalArgumentException> {
            SchedulerState(totalUnits = 100, completedUnits = 101)
        }
    }
}

/**
 * Drie gevallen uit een code review. Geen ervan liet een bestaande test falen;
 * ze gaven alle drie een verkeerd antwoord zonder klacht.
 */
class HerstelEnAfslutenTest {

    @Test
    fun `een uitschakelend toestel breekt ook een onderhanden blok af`() {
        val planner = ChunkScheduler(totalUnits = 5_000)
        assertIs<ChunkPlan.Work>(planner.next(ThermalStatus.NONE, nowMs = 0L))

        val plan = planner.next(ThermalStatus.SHUTDOWN, nowMs = 1_000L)

        assertIs<ChunkPlan.Stopped>(
            plan,
            "zolang er een blok in de lucht hing was Stopped onbereikbaar; kreeg $plan",
        )
    }

    @Test
    fun `een onderhanden blok komt bij gewone hitte gewoon terug`() {
        val planner = ChunkScheduler(totalUnits = 5_000)
        val eerste = assertIs<ChunkPlan.Work>(planner.next(ThermalStatus.NONE, nowMs = 0L))

        val tweede = planner.next(ThermalStatus.CRITICAL, nowMs = 1_000L)

        assertEquals(
            eerste.chunk,
            assertIs<ChunkPlan.Work>(tweede).chunk,
            "hetzelfde blok hoort terug te komen, anders raakt werk zoek",
        )
    }

    /**
     * Na een herstart houdt de aanroeper vaak nog het blok van vóór de
     * momentopname vast. Werd dat als "uitgegeven op tijdstip 0" geteld, dan
     * kwam de hele wandkloktijd sinds epoch als werktijd binnen.
     */
    @Test
    fun `een blok van voor de herstart blaast de tijdschatting niet op`() {
        val planner = ChunkScheduler.restore(SchedulerState(totalUnits = 5_000))

        planner.complete(WorkChunk(start = 0, endExclusive = 600), nowMs = 1_000_000L)

        val schatting = planner.report(nowMs = 1_000_000L).estimatedRemainingMs
        assertTrue(
            schatting == null || schatting < 60_000L,
            "onzinnige schatting van ${schatting}ms na herstel",
        )
        assertEquals(600, planner.completedUnits, "de voortgang zelf telt wél mee")
    }

    @Test
    fun `blokken die deze planner zelf uitgeeft worden wel gemeten`() {
        val planner = ChunkScheduler(totalUnits = 5_000)
        val blok = assertIs<ChunkPlan.Work>(planner.next(ThermalStatus.NONE, nowMs = 0L)).chunk

        planner.complete(blok, nowMs = 2_000L)

        assertNotNull(
            planner.report(nowMs = 2_000L).estimatedRemainingMs,
            "zonder meting kan er geen schatting zijn, maar deze is wél gemeten",
        )
    }

    /**
     * Een balk die stilstaat zonder uitleg leest als een vastgelopen app — precies
     * wat deze module wilde voorkomen.
     */
    @Test
    fun `een herstelde gepauzeerde planner meldt dat hij gepauzeerd is`() {
        val heet = ChunkScheduler(totalUnits = 5_000)
        assertIs<ChunkPlan.Pause>(heet.next(ThermalStatus.SEVERE, nowMs = 0L))

        val hersteld = ChunkScheduler.restore(heet.snapshot())
        val verslag = hersteld.report(nowMs = 1_000L)

        assertTrue(verslag.paused, "verslag: ${verslag.message}")
        assertNotNull(verslag.reason, "zonder reden staat er alleen een stilstaande balk")
    }

    @Test
    fun `een verse planner meldt gewoon dat hij bezig is`() {
        val verslag = ChunkScheduler(totalUnits = 5_000).report(nowMs = 0L)

        assertFalse(verslag.paused, "verslag: ${verslag.message}")
    }
}
