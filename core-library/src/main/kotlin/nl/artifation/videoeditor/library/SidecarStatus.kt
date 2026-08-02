package nl.artifation.videoeditor.library

/** Waarom een bestaande sidecar niet meer bruikbaar is. */
public enum class OutdatedReason {
    /** Gemaakt met een oudere analyseversie; een beter model is beschikbaar. */
    STALE_VERSION,

    /** Het bronbestand is gewijzigd sinds de analyse. */
    SOURCE_CHANGED,

    /** Het record hoort bij een ander asset — een restant van een hergebruikte map. */
    MISMATCHED_ASSET,
}

public sealed interface SidecarState {

    public data object Missing : SidecarState

    public data class Ready(val record: SidecarRecord) : SidecarState

    public data class Outdated(val record: SidecarRecord, val reason: OutdatedReason) : SidecarState

    public val isUsable: Boolean get() = this is Ready

    /** Zowel ontbrekend als verouderd betekent: opnieuw laten analyseren. */
    public val needsAnalysis: Boolean get() = this !is Ready
}

/** Wat er voor één asset aan analyses klaarstaat, ontbreekt of verouderd is. */
public data class AssetSidecarStatus(
    val assetId: String,
    val states: Map<AnalysisKind, SidecarState>,
    /** Maskvideo's per onderwerp-index; leeg als er nooit getrackt is. */
    val masks: Map<Int, SidecarState> = emptyMap(),
) {
    val ready: List<AnalysisKind> get() = kindsWhere { it is SidecarState.Ready }

    val missing: List<AnalysisKind> get() = kindsWhere { it is SidecarState.Missing }

    val outdated: List<AnalysisKind> get() = kindsWhere { it is SidecarState.Outdated }

    /** Precies het werk dat de analysepipeline nog moet doen, in enum-volgorde. */
    val needsAnalysis: List<AnalysisKind> get() = kindsWhere { it.needsAnalysis }

    val isComplete: Boolean get() = needsAnalysis.isEmpty()

    val staleMaskIndices: List<Int>
        get() = masks.filterValues { it.needsAnalysis }.keys.sorted()

    public fun state(kind: AnalysisKind): SidecarState = states[kind] ?: SidecarState.Missing

    public fun reason(kind: AnalysisKind): OutdatedReason? =
        (states[kind] as? SidecarState.Outdated)?.reason

    private fun kindsWhere(predicate: (SidecarState) -> Boolean): List<AnalysisKind> =
        AnalysisKind.entries.filter { predicate(state(it)) }
}

/**
 * Leest de sidecars van een asset en zegt welke bruikbaar zijn.
 *
 * De invalidatie zit hier en niet in de analysemodules, zodat het ophogen van
 * een modelversie één plek raakt en de rest van de app er niets van merkt.
 */
public class SidecarIndex(
    public val storage: SidecarStorage,
    private val versions: AnalysisVersions = AnalysisVersions(),
) {

    public fun record(assetId: String, kind: AnalysisKind): SidecarRecord? =
        storage.read(SidecarPaths.of(assetId, kind))

    /** Stempelt de huidige analyseversie en de bronrevisie van [asset] op het resultaat. */
    public fun write(
        asset: MediaAsset,
        kind: AnalysisKind,
        payload: String = "",
        writtenAtEpochMs: Long = 0L,
    ): SidecarRecord {
        val record = SidecarRecord(
            assetId = asset.id,
            kind = kind,
            analysisVersion = versions.of(kind),
            sourceRevision = asset.sourceRevision,
            payload = payload,
            writtenAtEpochMs = writtenAtEpochMs,
        )
        storage.write(SidecarPaths.of(asset.id, kind), record)
        return record
    }

    /**
     * Legt vast dat er een maskvideo voor onderwerp [index] bestaat.
     *
     * Maskvideo's dragen de versie van [AnalysisKind.SUBJECTS]: ze komen uit
     * dezelfde segmentatie, dus een beter segmentatiemodel maakt zowel de json
     * als de maskvideo's ongeldig.
     */
    public fun writeMask(
        asset: MediaAsset,
        index: Int,
        writtenAtEpochMs: Long = 0L,
    ): SidecarRecord {
        val record = SidecarRecord(
            assetId = asset.id,
            kind = AnalysisKind.SUBJECTS,
            analysisVersion = versions.of(AnalysisKind.SUBJECTS),
            sourceRevision = asset.sourceRevision,
            writtenAtEpochMs = writtenAtEpochMs,
        )
        storage.write(SidecarPaths.mask(asset.id, index), record)
        return record
    }

    public fun state(asset: MediaAsset, kind: AnalysisKind): SidecarState =
        stateOf(asset, kind, SidecarPaths.of(asset.id, kind))

    public fun maskState(asset: MediaAsset, index: Int): SidecarState =
        stateOf(asset, AnalysisKind.SUBJECTS, SidecarPaths.mask(asset.id, index))

    public fun status(asset: MediaAsset): AssetSidecarStatus = AssetSidecarStatus(
        assetId = asset.id,
        states = AnalysisKind.entries.associateWith { state(asset, it) },
        masks = maskIndices(asset.id).associateWith { maskState(asset, it) },
    )

    public fun statuses(catalog: MediaCatalog): List<AssetSidecarStatus> =
        catalog.assets.map(::status)

    /** De assets waarvoor nog analysewerk openstaat, in catalogusvolgorde. */
    public fun pending(catalog: MediaCatalog): List<AssetSidecarStatus> =
        statuses(catalog).filterNot { it.isComplete }

    public fun maskIndices(assetId: String): List<Int> {
        val prefix = SidecarPaths.directory(assetId) + "/"
        return storage.paths()
            .filter { it.startsWith(prefix) }
            .mapNotNull(SidecarPaths::maskIndexOf)
            .sorted()
    }

    /** Gooit één analyse weg, bijvoorbeeld omdat de gebruiker hem wil overdoen. */
    public fun invalidate(assetId: String, kind: AnalysisKind): Boolean =
        storage.delete(SidecarPaths.of(assetId, kind))

    /** Verwijdert alles wat bij een asset hoort; geeft de verwijderde paden terug. */
    public fun purge(assetId: String): List<String> {
        val prefix = SidecarPaths.directory(assetId) + "/"
        val victims = storage.paths().filter { it.startsWith(prefix) }
        victims.forEach(storage::delete)
        return victims
    }

    private fun stateOf(asset: MediaAsset, kind: AnalysisKind, path: String): SidecarState {
        val record = storage.read(path) ?: return SidecarState.Missing
        val reason = outdatedReason(record, asset, kind)
        return if (reason == null) {
            SidecarState.Ready(record)
        } else {
            SidecarState.Outdated(record, reason)
        }
    }

    /** De eerste reden waarom [record] niet meer bruikbaar is, of null. */
    private fun outdatedReason(
        record: SidecarRecord,
        asset: MediaAsset,
        kind: AnalysisKind,
    ): OutdatedReason? = when {
        record.assetId != asset.id -> OutdatedReason.MISMATCHED_ASSET

        // Eerst de bron: is het bestand gewijzigd, dan klopt de analyse sowieso
        // niet meer, ook al is hij met de huidige versie gemaakt.
        record.sourceRevision != asset.sourceRevision -> OutdatedReason.SOURCE_CHANGED

        // Een nieuwere versie dan de onze is geen probleem: dat resultaat komt
        // van een beter model en blijft bruikbaar na een downgrade van de app.
        record.analysisVersion < versions.of(kind) -> OutdatedReason.STALE_VERSION

        else -> null
    }
}
