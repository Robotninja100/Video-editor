package nl.artifation.videoeditor.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SidecarStatusTest {

    private val storage = InMemorySidecarStorage()
    private val index = SidecarIndex(storage)
    private val asset = testAsset()

    @Test
    fun `zonder sidecar ontbreekt de analyse`() {
        val state = index.state(asset, AnalysisKind.SILENCES)

        assertEquals(SidecarState.Missing, state, "was $state")
        assertTrue(state.needsAnalysis)
    }

    @Test
    fun `een net geschreven analyse is bruikbaar`() {
        index.write(asset, AnalysisKind.SILENCES, payload = "[]")

        val state = index.state(asset, AnalysisKind.SILENCES)

        assertIs<SidecarState.Ready>(state, "was $state")
        assertEquals("[]", state.record.payload)
        assertEquals(asset.sourceRevision, state.record.sourceRevision)
    }

    @Test
    fun `een hogere analyseversie maakt bestaande sidecars verouderd`() {
        index.write(asset, AnalysisKind.TRANSCRIPT)

        // Een beter transcriptiemodel: alleen dit getal gaat omhoog.
        val nieuwer = SidecarIndex(storage, AnalysisVersions().bumpedTo(AnalysisKind.TRANSCRIPT, 2))
        val state = nieuwer.state(asset, AnalysisKind.TRANSCRIPT)

        assertIs<SidecarState.Outdated>(state, "was $state")
        assertEquals(OutdatedReason.STALE_VERSION, state.reason)
    }

    @Test
    fun `een versiebump raakt alleen de betrokken analyse`() {
        index.write(asset, AnalysisKind.TRANSCRIPT)
        index.write(asset, AnalysisKind.SCENES)

        val nieuwer = SidecarIndex(storage, AnalysisVersions().bumpedTo(AnalysisKind.TRANSCRIPT, 2))

        assertEquals(listOf(AnalysisKind.TRANSCRIPT), nieuwer.status(asset).outdated)
        assertTrue(nieuwer.state(asset, AnalysisKind.SCENES).isUsable)
    }

    @Test
    fun `een resultaat van een nieuwer model blijft bruikbaar na een downgrade`() {
        val toekomst = SidecarIndex(storage, AnalysisVersions().bumpedTo(AnalysisKind.SCENES, 5))
        toekomst.write(asset, AnalysisKind.SCENES)

        val state = index.state(asset, AnalysisKind.SCENES)

        assertIs<SidecarState.Ready>(state, "was $state")
        assertEquals(5, state.record.analysisVersion)
    }

    @Test
    fun `een gewijzigd bronbestand maakt de sidecar verouderd`() {
        index.write(asset, AnalysisKind.SILENCES)

        val gewijzigd = asset.copy(sourceRevision = asset.sourceRevision + 1)
        val state = index.state(gewijzigd, AnalysisKind.SILENCES)

        assertIs<SidecarState.Outdated>(state, "was $state")
        assertEquals(OutdatedReason.SOURCE_CHANGED, state.reason)
    }

    @Test
    fun `een gewijzigde bron weegt zwaarder dan de analyseversie`() {
        index.write(asset, AnalysisKind.TRANSCRIPT)

        val nieuwer = SidecarIndex(storage, AnalysisVersions().bumpedTo(AnalysisKind.TRANSCRIPT, 2))
        val state = nieuwer.state(asset.copy(sourceRevision = 999L), AnalysisKind.TRANSCRIPT)

        assertIs<SidecarState.Outdated>(state, "was $state")
        assertEquals(OutdatedReason.SOURCE_CHANGED, state.reason, "de bron gaat voor de versie")
    }

    @Test
    fun `een sidecar van een ander asset wordt niet vertrouwd`() {
        val vreemd = SidecarRecord(
            assetId = "iemand anders",
            kind = AnalysisKind.SCENES,
            analysisVersion = AnalysisKind.SCENES.currentVersion,
            sourceRevision = asset.sourceRevision,
        )
        storage.write(SidecarPaths.of(asset.id, AnalysisKind.SCENES), vreemd)

        val state = index.state(asset, AnalysisKind.SCENES)

        assertIs<SidecarState.Outdated>(state, "was $state")
        assertEquals(OutdatedReason.MISMATCHED_ASSET, state.reason)
    }

    @Test
    fun `de status somt op wat er nog moet gebeuren`() {
        index.write(asset, AnalysisKind.SILENCES)

        val status = index.status(asset)

        assertEquals(listOf(AnalysisKind.SILENCES), status.ready)
        assertEquals(
            listOf(AnalysisKind.TRANSCRIPT, AnalysisKind.SCENES, AnalysisKind.SUBJECTS),
            status.missing,
            "missing was ${status.missing}",
        )
        assertEquals(status.missing, status.needsAnalysis, "zonder verouderde sidecars is dit hetzelfde")
        assertFalse(status.isComplete)
    }

    @Test
    fun `alle analyses aanwezig betekent klaar`() {
        AnalysisKind.entries.forEach { index.write(asset, it) }

        val status = index.status(asset)

        assertTrue(status.isComplete, "nog open: ${status.needsAnalysis}")
        assertEquals(emptyList(), status.needsAnalysis)
    }

    @Test
    fun `verouderde analyses staan ook in de werkvoorraad`() {
        AnalysisKind.entries.forEach { index.write(asset, it) }

        val nieuwer = SidecarIndex(storage, AnalysisVersions().bumpedTo(AnalysisKind.SUBJECTS, 3))
        val status = nieuwer.status(asset)

        assertEquals(listOf(AnalysisKind.SUBJECTS), status.needsAnalysis)
        assertEquals(OutdatedReason.STALE_VERSION, status.reason(AnalysisKind.SUBJECTS))
    }

    @Test
    fun `pending laat alleen de assets zien met openstaand werk`() {
        val klaar = testAsset(uri = "a", sizeBytes = 1L)
        val open = testAsset(uri = "b", sizeBytes = 2L)
        AnalysisKind.entries.forEach { index.write(klaar, it) }
        val catalog = MediaCatalog.of(listOf(klaar, open))

        val pending = index.pending(catalog)

        assertEquals(listOf(open.id), pending.map { it.assetId }, "pending: ${pending.map { it.assetId }}")
    }

    @Test
    fun `invalidate gooit precies één analyse weg`() {
        index.write(asset, AnalysisKind.SCENES)
        index.write(asset, AnalysisKind.SILENCES)

        assertTrue(index.invalidate(asset.id, AnalysisKind.SCENES))

        assertEquals(SidecarState.Missing, index.state(asset, AnalysisKind.SCENES))
        assertTrue(index.state(asset, AnalysisKind.SILENCES).isUsable)
    }

    @Test
    fun `invalidate van een ontbrekende analyse meldt dat er niets weg was`() {
        assertFalse(index.invalidate(asset.id, AnalysisKind.SCENES))
    }

    @Test
    fun `record geeft het ruwe sidecar terug`() {
        index.write(asset, AnalysisKind.TRANSCRIPT, payload = "{}", writtenAtEpochMs = 7L)

        val record = index.record(asset.id, AnalysisKind.TRANSCRIPT)

        assertEquals(7L, record?.writtenAtEpochMs, "was $record")
        assertNull(index.record(asset.id, AnalysisKind.SCENES))
    }
}

class SidecarMaskTest {

    private val storage = InMemorySidecarStorage()
    private val index = SidecarIndex(storage)
    private val asset = testAsset()

    @Test
    fun `maskvideo's worden per index bijgehouden en oplopend opgesomd`() {
        index.writeMask(asset, 2)
        index.writeMask(asset, 0)

        assertEquals(listOf(0, 2), index.maskIndices(asset.id))
    }

    @Test
    fun `maskvideo's van een ander asset tellen niet mee`() {
        val ander = testAsset(uri = "b", sizeBytes = 999L)
        index.writeMask(asset, 0)
        index.writeMask(ander, 1)

        assertEquals(listOf(0), index.maskIndices(asset.id))
        assertEquals(listOf(1), index.maskIndices(ander.id))
    }

    @Test
    fun `een nieuwer segmentatiemodel maakt ook de maskvideo's verouderd`() {
        // Masks komen uit dezelfde analyse als subjects.json en delen die versie.
        index.write(asset, AnalysisKind.SUBJECTS)
        index.writeMask(asset, 0)

        val nieuwer = SidecarIndex(storage, AnalysisVersions().bumpedTo(AnalysisKind.SUBJECTS, 2))
        val status = nieuwer.status(asset)

        assertEquals(listOf(0), status.staleMaskIndices, "masks: ${status.masks}")
        assertEquals(listOf(AnalysisKind.SUBJECTS), status.outdated)
    }

    @Test
    fun `een gewijzigde bron maakt de maskvideo's verouderd`() {
        index.writeMask(asset, 0)

        val state = index.maskState(asset.copy(sourceRevision = 100L), 0)

        assertIs<SidecarState.Outdated>(state, "was $state")
        assertEquals(OutdatedReason.SOURCE_CHANGED, state.reason)
    }

    @Test
    fun `zonder tracking is er geen maskvideo`() {
        assertEquals(emptyList(), index.maskIndices(asset.id))
        assertEquals(SidecarState.Missing, index.maskState(asset, 0))
    }

    @Test
    fun `purge verwijdert alles van één asset en laat de rest staan`() {
        val ander = testAsset(uri = "b", sizeBytes = 999L)
        AnalysisKind.entries.forEach { index.write(asset, it) }
        index.writeMask(asset, 0)
        index.write(ander, AnalysisKind.SCENES)

        val verwijderd = index.purge(asset.id)

        assertEquals(AnalysisKind.entries.size + 1, verwijderd.size, "verwijderd: $verwijderd")
        assertEquals(0, index.status(asset).ready.size, "resteert: ${storage.paths()}")
        assertTrue(index.state(ander, AnalysisKind.SCENES).isUsable, "ander asset werd meegenomen")
    }

    @Test
    fun `purge van een asset zonder sidecars doet niets`() {
        assertEquals(emptyList(), index.purge(asset.id))
    }
}
