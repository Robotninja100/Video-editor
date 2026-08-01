package nl.artifation.videoeditor.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val config = AutosaveConfig()

class AutosavePolicyTest {

    @Test
    fun `zonder wijzigingen wordt er niets geschreven`() {
        val besluit =
            AutosavePolicy.decide(AutosaveState(hasUnsavedChanges = false), nowMs = 10_000L)

        assertEquals(
            AutosaveDecision.Skip(AutosaveReason.NOTHING_TO_SAVE),
            besluit,
            "besluit: $besluit",
        )
    }

    @Test
    fun `tijdens het slepen wordt er niet bij elke beweging geschreven`() {
        var state = AutosaveState(lastWriteAtMs = 0L)
        var schrijfacties = 0

        // Een seconde slepen op 60 Hz: elke frame een bewerking.
        for (frame in 1..60) {
            val nowMs = frame * 16L
            state = AutosavePolicy.onEdit(state, EditKind.CONTINUOUS, nowMs)
            if (AutosavePolicy.decide(state, nowMs, config) is AutosaveDecision.Write) {
                schrijfacties++
            }
        }

        assertEquals(0, schrijfacties, "er werd $schrijfacties keer geschreven tijdens het slepen")
    }

    @Test
    fun `na de rustpauze wordt er geschreven`() {
        val state = AutosaveState(
            hasUnsavedChanges = true,
            lastEditKind = EditKind.CONTINUOUS,
            lastEditAtMs = 5_000L,
            lastWriteAtMs = 0L,
        )

        val tijdens = AutosavePolicy.decide(state, nowMs = 6_000L, config = config)
        val erna = AutosavePolicy.decide(state, nowMs = 6_500L, config = config)

        assertIs<AutosaveDecision.Wait>(tijdens, "binnen de rustpauze: $tijdens")
        assertEquals(6_500L, tijdens.untilMs, "wachten tot: ${tijdens.untilMs}")
        assertEquals(
            AutosaveDecision.Write(AutosaveReason.QUIET_PERIOD),
            erna,
            "na de rustpauze: $erna",
        )
    }

    @Test
    fun `wachten wijst precies naar het moment waarop er wél geschreven wordt`() {
        val state = AutosaveState(
            hasUnsavedChanges = true,
            lastEditKind = EditKind.CONTINUOUS,
            lastEditAtMs = 2_000L,
            lastWriteAtMs = 1_000L,
        )

        val wachten = AutosavePolicy.decide(state, nowMs = 2_100L, config = config)

        assertIs<AutosaveDecision.Wait>(wachten, "besluit: $wachten")
        assertIs<AutosaveDecision.Write>(
            AutosavePolicy.decide(state, wachten.untilMs, config),
            "op het aangewezen moment moet er geschreven worden",
        )
    }

    @Test
    fun `een structurele bewerking wacht korter dan een sleepbeweging`() {
        fun wachtTot(kind: EditKind): Long {
            val state = AutosaveState(
                hasUnsavedChanges = true,
                lastEditKind = kind,
                lastEditAtMs = 5_000L,
                lastWriteAtMs = 0L,
            )
            val besluit = AutosavePolicy.decide(state, nowMs = 5_001L, config = config)
            assertIs<AutosaveDecision.Wait>(besluit, "besluit voor $kind: $besluit")
            return besluit.untilMs
        }

        assertTrue(
            wachtTot(EditKind.STRUCTURAL) < wachtTot(EditKind.CONTINUOUS),
            "knippen (${wachtTot(EditKind.STRUCTURAL)}) moet eerder landen dan slepen " +
                "(${wachtTot(EditKind.CONTINUOUS)})",
        )
    }

    @Test
    fun `de bovengrens dwingt een schrijfactie af midden in het slepen`() {
        var state = AutosaveState(lastWriteAtMs = 0L)
        var eersteSchrijfactieOpMs: Long? = null

        // Onafgebroken slepen: de rustpauze wordt nooit gehaald.
        var nowMs = 0L
        while (nowMs < 60_000L && eersteSchrijfactieOpMs == null) {
            nowMs += 16L
            state = AutosavePolicy.onEdit(state, EditKind.CONTINUOUS, nowMs)
            val besluit = AutosavePolicy.decide(state, nowMs, config)
            if (besluit is AutosaveDecision.Write) {
                assertEquals(AutosaveReason.MAX_INTERVAL, besluit.reason, "besluit: $besluit")
                eersteSchrijfactieOpMs = nowMs
            }
        }

        assertTrue(
            eersteSchrijfactieOpMs != null &&
                eersteSchrijfactieOpMs!! <= config.maxIntervalMs + 16L,
            "er werd pas geschreven op $eersteSchrijfactieOpMs ms",
        )
    }

    @Test
    fun `vlak na een schrijfactie wordt er niet meteen opnieuw geschreven`() {
        val state = AutosaveState(
            hasUnsavedChanges = true,
            lastEditKind = EditKind.STRUCTURAL,
            lastEditAtMs = 10_100L,
            lastWriteAtMs = 10_000L,
        )

        val besluit = AutosavePolicy.decide(state, nowMs = 10_400L, config = config)

        assertEquals(
            AutosaveDecision.Wait(11_000L, AutosaveReason.MIN_INTERVAL),
            besluit,
            "besluit: $besluit",
        )
    }

    @Test
    fun `een lopende schrijfactie levert een nieuwe poging op, geen tweede schrijfactie`() {
        val state = AutosaveState(
            hasUnsavedChanges = true,
            lastEditKind = EditKind.STRUCTURAL,
            lastEditAtMs = 1_000L,
            lastWriteAtMs = 0L,
            writeInProgress = true,
        )

        val besluit = AutosavePolicy.decide(state, nowMs = 9_000L, config = config)

        assertEquals(
            AutosaveDecision.Wait(9_000L + config.retryDelayMs, AutosaveReason.WRITE_IN_PROGRESS),
            besluit,
            "besluit: $besluit",
        )
    }

    @Test
    fun `een nooit opgeslagen project wacht niet op een eerdere schrijfactie`() {
        val state = AutosavePolicy.onEdit(AutosaveState(), EditKind.STRUCTURAL, nowMs = 40_000L)

        val besluit = AutosavePolicy.decide(state, nowMs = 40_300L, config = config)

        assertEquals(
            AutosaveDecision.Write(AutosaveReason.QUIET_PERIOD),
            besluit,
            "besluit: $besluit; de ondergrens hoort niet te gelden zonder eerdere schrijfactie",
        )
    }

    @Test
    fun `een nooit opgeslagen project blijft niet eindeloos ongeschreven`() {
        val state = AutosavePolicy.onEdit(AutosaveState(), EditKind.CONTINUOUS, nowMs = 100L)

        val besluit = AutosavePolicy.decide(state, nowMs = 100L + config.maxIntervalMs, config)

        assertEquals(
            AutosaveDecision.Write(AutosaveReason.MAX_INTERVAL),
            besluit,
            "besluit: $besluit",
        )
    }

    @Test
    fun `een sleepsessie met pauzes levert één schrijfactie per pauze op`() {
        var state = AutosaveState(lastWriteAtMs = 0L)
        val geschrevenOp = mutableListOf<Long>()

        // Drie sleepbewegingen van 300 ms, met 3 s stilte ertussen.
        var nowMs = 0L
        repeat(3) {
            repeat(20) {
                nowMs += 16L
                state = AutosavePolicy.onEdit(state, EditKind.CONTINUOUS, nowMs)
                if (AutosavePolicy.decide(state, nowMs, config) is AutosaveDecision.Write) {
                    geschrevenOp.add(nowMs)
                    state = AutosavePolicy.onWritten(state, nowMs, nowMs)
                }
            }
            repeat(30) {
                nowMs += 100L
                if (AutosavePolicy.decide(state, nowMs, config) is AutosaveDecision.Write) {
                    geschrevenOp.add(nowMs)
                    state = AutosavePolicy.onWritten(state, nowMs, nowMs)
                }
            }
        }

        assertEquals(3, geschrevenOp.size, "schrijfacties op: $geschrevenOp")
    }

    @Test
    fun `een bewerking tijdens het schrijven blijft onopgeslagen`() {
        val start = 1_000L
        var state = AutosaveState(
            hasUnsavedChanges = true,
            lastEditKind = EditKind.CONTINUOUS,
            lastEditAtMs = start,
            writeInProgress = true,
        )

        state = AutosavePolicy.onEdit(state, EditKind.CONTINUOUS, nowMs = start + 10L)
        state = AutosavePolicy.onWritten(state, startedAtMs = start, finishedAtMs = start + 50L)

        assertTrue(state.hasUnsavedChanges, "de bewerking van tijdens de schrijfactie is kwijt")
        assertEquals(start + 50L, state.lastWriteAtMs, "state: $state")
    }

    @Test
    fun `na een schrijfactie zonder nieuwe bewerkingen is er niets meer te doen`() {
        var state = AutosavePolicy.onEdit(AutosaveState(), EditKind.STRUCTURAL, nowMs = 100L)

        state = AutosavePolicy.onWritten(state, startedAtMs = 500L, finishedAtMs = 520L)

        assertEquals(
            AutosaveDecision.Skip(AutosaveReason.NOTHING_TO_SAVE),
            AutosavePolicy.decide(state, nowMs = 5_000L, config = config),
            "state: $state",
        )
    }
}

class AutosaveConfigTest {

    @Test
    fun `een bovengrens onder de rustpauze is onzin`() {
        val fout = assertFailsWith<IllegalArgumentException> {
            AutosaveConfig(quietPeriodMs = 5_000L, maxIntervalMs = 1_000L)
        }

        assertTrue(fout.message!!.contains("maxIntervalMs"), "boodschap: ${fout.message}")
    }

    @Test
    fun `negatieve tijden worden geweigerd`() {
        assertFailsWith<IllegalArgumentException>("een negatieve rustpauze bestaat niet") {
            AutosaveConfig(quietPeriodMs = -1L)
        }
    }

    @Test
    fun `structurele bewerkingen hebben een eigen, kortere vertraging`() {
        assertEquals(config.quietPeriodMs, config.debounceMs(EditKind.CONTINUOUS))
        assertEquals(config.structuralDelayMs, config.debounceMs(EditKind.STRUCTURAL))
        assertEquals(config.structuralDelayMs, config.debounceMs(EditKind.METADATA))
    }
}
