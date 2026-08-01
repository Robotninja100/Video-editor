package nl.artifation.videoeditor.library

import nl.artifation.videoeditor.model.Clip
import nl.artifation.videoeditor.model.EffectSpec
import nl.artifation.videoeditor.model.Project

public data class SweepResult(
    val catalog: MediaCatalog,
    val removedAssets: List<MediaAsset>,
    val deletedSidecars: List<String>,
)

/**
 * Opruimen van de bibliotheek.
 *
 * Alles is een aparte vraag ("wat wordt nergens gebruikt?") of een expliciete
 * opdracht ([forget]). [sweep] is bewust voorzichtig: automatisch verwijderen
 * van materiaal dat de gebruiker misschien nog nodig heeft, is erger dan een
 * paar megabyte cache laten liggen.
 */
public object LibraryMaintenance {

    /** De bron-uri's waar een project op steunt, inclusief maskvideo's uit effecten. */
    public fun referencedUris(project: Project): Set<String> = buildSet {
        project.sequences.forEach { sequence ->
            sequence.items.filterIsInstance<Clip>().forEach { clip ->
                add(clip.sourceUri)
                clip.effects.filterIsInstance<EffectSpec.MaskedBlur>().forEach { add(it.maskUri) }
            }
        }
    }

    public fun usedAssetIds(catalog: MediaCatalog, projects: List<Project>): Set<String> =
        projects.flatMap(::referencedUris)
            .mapNotNull { catalog.byUri(it)?.id }
            .toSet()

    /** Materiaal dat in geen enkel project meer voorkomt; kandidaten om op te ruimen. */
    public fun unusedAssets(catalog: MediaCatalog, projects: List<Project>): List<MediaAsset> {
        val used = usedAssetIds(catalog, projects)
        return catalog.assets.filterNot { it.id in used }
    }

    /**
     * Materiaal waarvan het bronbestand weg is. De aanwezigheidstest komt van
     * buiten, want deze module kent geen bestandssysteem.
     */
    public fun missingAssets(catalog: MediaCatalog, isPresent: (String) -> Boolean): List<MediaAsset> =
        catalog.assets.filterNot { isPresent(it.uri) }

    /**
     * Sidecars zonder asset in de catalogus.
     *
     * Paden die een project nog gebruikt — een maskvideo in een `MaskedBlur` —
     * blijven buiten schot: die weggooien breekt een bestaande montage.
     */
    public fun orphanSidecars(
        catalog: MediaCatalog,
        storage: SidecarStorage,
        projects: List<Project> = emptyList(),
    ): List<String> {
        val referenced = projects.flatMap(::referencedUris).toSet()
        return storage.paths().filter { path ->
            val assetId = SidecarPaths.assetIdOf(path) ?: return@filter false
            !catalog.contains(assetId) && path !in referenced
        }
    }

    /** Verwijdert een asset én zijn sidecars; de sidecars zouden anders wezen worden. */
    public fun forget(catalog: MediaCatalog, index: SidecarIndex, assetId: String): SweepResult {
        val asset = catalog.get(assetId)
        return SweepResult(
            catalog = catalog.remove(assetId),
            removedAssets = listOfNotNull(asset),
            deletedSidecars = if (asset == null) emptyList() else index.purge(assetId),
        )
    }

    /**
     * Veilige schoonmaak: weg is alleen wat verdwenen én ongebruikt is, plus de
     * sidecars die nergens meer bij horen. Ongebruikt materiaal dat nog op schijf
     * staat blijft staan — daar beslist de gebruiker over, niet de opruimer.
     */
    public fun sweep(
        catalog: MediaCatalog,
        index: SidecarIndex,
        projects: List<Project> = emptyList(),
        isPresent: (String) -> Boolean = { true },
    ): SweepResult {
        val used = usedAssetIds(catalog, projects)
        val doomed = catalog.assets.filter { it.id !in used && !isPresent(it.uri) }

        val remaining = catalog.removeAll(doomed.map { it.id })
        val purged = doomed.flatMap { index.purge(it.id) }
        val orphans = orphanSidecars(remaining, index.storage, projects)
        orphans.forEach(index.storage::delete)

        return SweepResult(
            catalog = remaining,
            removedAssets = doomed,
            deletedSidecars = (purged + orphans).distinct().sorted(),
        )
    }
}
