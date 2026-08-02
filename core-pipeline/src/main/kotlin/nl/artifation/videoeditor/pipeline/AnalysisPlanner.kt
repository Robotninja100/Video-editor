package nl.artifation.videoeditor.pipeline

import nl.artifation.videoeditor.jobs.Job
import nl.artifation.videoeditor.jobs.JobKind
import nl.artifation.videoeditor.jobs.JobPriority
import nl.artifation.videoeditor.library.AnalysisKind
import nl.artifation.videoeditor.library.MediaAsset
import nl.artifation.videoeditor.library.SidecarIndex
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.model.Us

/**
 * Bepaalt welke analyses een clip nog nodig heeft, en zet die om in taken.
 *
 * Dit is het scharnier tussen `:core-library` (wat is er al geanalyseerd?) en
 * `:core-jobs` (wat moet er nog gebeuren?). Beide modules kennen elkaar niet;
 * zonder deze vertaling weet de wachtrij nooit wat er te doen valt.
 *
 * Er wordt alleen gepland wat ontbreekt of verouderd is. Een analyse opnieuw
 * draaien die er al goed staat, kost bij segmentatie echt geld — daar hangt een
 * cloud-dienst aan met een prijs per frame.
 */
public object AnalysisPlanner {

    /**
     * @param trackingFps framerate waarop gesegmenteerd wordt; lager is
     *   goedkoper en visueel nauwelijks te onderscheiden bij zachte blur-randen
     */
    public fun plan(
        asset: MediaAsset,
        index: SidecarIndex,
        nowUs: Us,
        trackingFps: Int = 10,
        includeSegmentation: Boolean = false,
    ): List<Job> {
        require(trackingFps > 0) { "trackingFps moet positief zijn, was $trackingFps" }

        val status = index.status(asset)

        return buildList {
            if (status.state(AnalysisKind.SILENCES).needsAnalysis ||
                status.state(AnalysisKind.SCENES).needsAnalysis
            ) {
                // Stiltes en scenes komen uit dezelfde doorloop over het
                // materiaal; ze apart plannen zou het bestand twee keer decoderen.
                add(
                    job(
                        id = "${asset.id}:clip-analyse",
                        kind = JobKind.ClipAnalysis(asset.uri, asset.durationUs),
                        nowUs = nowUs,
                        priority = JobPriority.High,
                    ),
                )
            }

            if (status.state(AnalysisKind.TRANSCRIPT).needsAnalysis) {
                add(
                    job(
                        id = "${asset.id}:transcriptie",
                        kind = JobKind.Transcription(asset.uri, asset.durationUs),
                        nowUs = nowUs,
                    ),
                )
            }

            // Segmentatie alleen op verzoek: het is de enige analyse die per
            // frame betaald wordt, dus die hoort niet bij elke import te draaien.
            //
            // Een verouderde maskvideo telt ook mee. De masks komen uit dezelfde
            // segmentatie als subjects.json, dus een mask van een oudere
            // bronrevisie betekent dat die segmentatie over moet — ook al ziet de
            // json er nog vers uit. Zonder deze regel meldt `SidecarIndex.pending`
            // het asset wel als openstaand werk, maar levert de planner er geen
            // enkele taak voor op: openstaand werk dat niemand oppakt.
            val segmentatieVerouderd = status.state(AnalysisKind.SUBJECTS).needsAnalysis ||
                status.staleMaskIndices.isNotEmpty()

            if (includeSegmentation && segmentatieVerouderd) {
                add(
                    job(
                        id = "${asset.id}:segmentatie",
                        kind = JobKind.Segmentation(
                            sourceUri = asset.uri,
                            frameCount = frameCountFor(asset.durationUs, trackingFps),
                        ),
                        nowUs = nowUs,
                        priority = JobPriority.Low,
                    ),
                )
            }
        }
    }

    /** Wat de hele bibliotheek nog nodig heeft, op volgorde van de wachtrij. */
    public fun planAll(
        assets: Iterable<MediaAsset>,
        index: SidecarIndex,
        nowUs: Us,
        trackingFps: Int = 10,
    ): List<Job> = assets.flatMap { plan(it, index, nowUs, trackingFps) }

    internal fun frameCountFor(durationUs: Us, fps: Int): Int {
        val frames = durationUs * fps / US_PER_SECOND
        // Ook een clip korter dan één frame moet één frame opleveren, anders
        // vraagt de aanroeper een segmentatie aan die niets teruggeeft.
        return frames.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun job(
        id: String,
        kind: JobKind,
        nowUs: Us,
        priority: JobPriority = JobPriority.Normal,
    ) = Job(id = id, kind = kind, enqueuedAtUs = nowUs, priority = priority)
}
