package nl.artifation.videoeditor.spike

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.artifation.videoeditor.model.Clip
import nl.artifation.videoeditor.model.EffectSpec
import nl.artifation.videoeditor.model.OutputSpec
import nl.artifation.videoeditor.model.Project
import nl.artifation.videoeditor.model.Sequence
import nl.artifation.videoeditor.model.toRenderPlan
import nl.artifation.videoeditor.render.toComposition
import java.io.File
import kotlin.math.hypot

/**
 * De poort van fase 0, als iets wat op het toestel te draaien is.
 *
 * Het bouwplan zag dit als een losse spike op een werkplek. Dat kan niet: er is
 * geen toestel aan een werkplek te hangen, en zonder hardwarecodecs en een echte
 * GPU bewijst een spike niets. Dus draait de meting op het toestel zelf, en is de
 * hele bijdrage van de gebruiker: installeren, op een knop drukken, aflezen.
 *
 * De twee bewijzen, letterlijk uit `docs/CHECKLIST.md`:
 *
 * 1. **Pariteit** — dezelfde compositie geëxporteerd én afgespeeld levert
 *    hetzelfde beeld op, gemeten met SSIM op tien vaste tijdstippen.
 * 2. **Masked blur** — cirkel scherp, rest geblurd, synchroon over de volle duur,
 *    ook op een getrimde clip met in-punt ≠ 0.
 *
 * Zakt bewijs 2, dan moet de maskopslag terug naar de tekentafel. Dat is precies
 * waarom deze meting in week 1 hoort en niet in week 12.
 */
internal class SpikeRunner(private val context: Context) {

    private val exporter = CompositionExporter(context)

    suspend fun run(onStatus: (String) -> Unit): SpikeReport {
        return try {
            runOrThrow(onStatus)
        } catch (failure: Exception) {
            SpikeReport(
                parity = null,
                blur = null,
                blurTrimmed = null,
                samples = emptyList(),
                failure = failure.message ?: failure::class.java.simpleName,
            )
        }
    }

    private suspend fun runOrThrow(onStatus: (String) -> Unit): SpikeReport {
        onStatus("Testmateriaal maken (dit duurt het langst)…")
        val sourceFile = File(context.filesDir, "spike/source.mp4")
        val maskFile = File(context.filesDir, "spike/mask.mp4")

        withContext(Dispatchers.Default) {
            if (!sourceFile.exists()) TestMediaFactory.createSourceVideo(sourceFile)
            if (!maskFile.exists()) TestMediaFactory.createMaskVideo(maskFile)
        }

        val sourceUri = Uri.fromFile(sourceFile).toString()
        val maskUri = Uri.fromFile(maskFile).toString()

        onStatus("Bewijs 1 — pariteit tussen export en preview…")
        val samples = mutableListOf<SampleFrames>()
        val parity = measureParity(sourceUri, samples, onStatus)

        onStatus("Bewijs 2 — masked blur…")
        val blur = measureMaskedBlur(
            sourceUri = sourceUri,
            maskUri = maskUri,
            inPointUs = 0L,
            label = "hele clip",
            samples = samples,
            onStatus = onStatus,
        )

        onStatus("Bewijs 2 — masked blur op een getrimde clip…")
        val blurTrimmed = measureMaskedBlur(
            sourceUri = sourceUri,
            maskUri = maskUri,
            inPointUs = TRIM_IN_POINT_US,
            label = "getrimd, in-punt ${TRIM_IN_POINT_US / 1_000_000}s",
            samples = samples,
            onStatus = onStatus,
        )

        return SpikeReport(
            parity = parity,
            blur = blur,
            blurTrimmed = blurTrimmed,
            samples = samples,
            failure = null,
        )
    }

    // ---------------------------------------------------------------- bewijs 1

    private suspend fun measureParity(
        sourceUri: String,
        samples: MutableList<SampleFrames>,
        onStatus: (String) -> Unit,
    ): ParityResult {
        val project = project(sourceUri, inPointUs = 0L, effects = emptyList())
        val composition = project.toRenderPlan().toComposition(context)

        val exported = File(context.filesDir, "spike/parity.mp4")
        onStatus("Bewijs 1 — exporteren…")
        exporter.export(composition, exported) { onStatus("Bewijs 1 — exporteren ($it%)…") }

        // Twee spelers op één compositie is vragen om problemen; daarom een verse
        // compositie voor de preview-kant.
        val previewComposition = project.toRenderPlan().toComposition(context)
        val grabber = PreviewFrameGrabber(
            context = context,
            composition = previewComposition,
            width = OUTPUT_WIDTH,
            height = OUTPUT_HEIGHT,
        )

        val scores = mutableListOf<ParitySample>()

        try {
            onStatus("Bewijs 1 — preview klaarzetten…")
            if (!grabber.awaitReady()) {
                return ParityResult(
                    samples = emptyList(),
                    note = "de preview kwam niet op gang; CompositionPlayer werd niet gereed",
                )
            }

            ExportedFrameGrabber(exported).use { fromFile ->
                parityTimestamps().forEachIndexed { index, timeUs ->
                    onStatus("Bewijs 1 — frame ${index + 1}/${PARITY_SAMPLE_COUNT} vergelijken…")

                    val previewFrame = grabber.frameAt(timeUs)
                    val exportFrame = fromFile.frameAt(timeUs, OUTPUT_WIDTH, OUTPUT_HEIGHT)

                    if (previewFrame == null || exportFrame == null) {
                        scores += ParitySample(timeUs, ssim = null)
                        previewFrame?.recycle()
                        exportFrame?.recycle()
                        return@forEachIndexed
                    }

                    val ssim = withContext(Dispatchers.Default) {
                        FrameMetrics.ssim(previewFrame, exportFrame)
                    }
                    scores += ParitySample(timeUs, ssim)

                    if (samples.none { it.label.startsWith("pariteit") }) {
                        samples += SampleFrames(
                            label = "pariteit op ${timeUs / 1_000_000.0}s",
                            left = thumbnail(previewFrame) to "preview",
                            right = thumbnail(exportFrame) to "export",
                        )
                    }

                    previewFrame.recycle()
                    exportFrame.recycle()
                }
            }
        } finally {
            grabber.close()
        }

        return ParityResult(samples = scores, note = null)
    }

    // ---------------------------------------------------------------- bewijs 2

    private suspend fun measureMaskedBlur(
        sourceUri: String,
        maskUri: String,
        inPointUs: Long,
        label: String,
        samples: MutableList<SampleFrames>,
        onStatus: (String) -> Unit,
    ): MaskedBlurResult {
        val project = project(
            sourceUri = sourceUri,
            inPointUs = inPointUs,
            effects = listOf(EffectSpec.MaskedBlur(maskUri = maskUri, radiusFrac = BLUR_RADIUS_FRAC)),
        )

        val exported = File(context.filesDir, "spike/blur-${inPointUs / 1_000_000}.mp4")
        exporter.export(project.toRenderPlan().toComposition(context), exported) {
            onStatus("Bewijs 2 ($label) — exporteren ($it%)…")
        }

        val measurements = mutableListOf<BlurSample>()

        ExportedFrameGrabber(exported).use { fromFile ->
            blurTimestamps(inPointUs).forEach { clipTimeUs ->
                val frame = fromFile.frameAt(clipTimeUs, OUTPUT_WIDTH, OUTPUT_HEIGHT) ?: return@forEach

                // De cirkel staat op de plek die hoort bij de **bron**tijd. Dat is de
                // hele kern van dit bewijs: bij een getrimd fragment is dat een andere
                // plek dan bij de cliptijd, en precies daar gaat het mis als het
                // in-punt onderweg verloren gaat.
                val (expectedX, expectedY) = TestMediaFactory.circleCenterAt(inPointUs + clipTimeUs)
                val (naiveX, naiveY) = TestMediaFactory.circleCenterAt(clipTimeUs)

                val insideSharpness = withContext(Dispatchers.Default) {
                    FrameMetrics.sharpness(frame, expectedX, expectedY, WINDOW_HALF_PX)
                }
                val outsideSharpness = withContext(Dispatchers.Default) {
                    val (awayX, awayY) = farFrom(expectedX, expectedY)
                    FrameMetrics.sharpness(frame, awayX, awayY, WINDOW_HALF_PX)
                }
                val naiveSharpness = withContext(Dispatchers.Default) {
                    FrameMetrics.sharpness(frame, naiveX, naiveY, WINDOW_HALF_PX)
                }

                val centresApart = hypot(expectedX - naiveX, expectedY - naiveY)

                measurements += BlurSample(
                    clipTimeUs = clipTimeUs,
                    sourceTimeUs = inPointUs + clipTimeUs,
                    insideSharpness = insideSharpness,
                    outsideSharpness = outsideSharpness,
                    // Alleen zinvol als de twee plekken ver genoeg uit elkaar liggen;
                    // vallen ze samen, dan kan deze meting niets onderscheiden.
                    naiveSharpness = if (centresApart >= MIN_CENTRE_SEPARATION) naiveSharpness else null,
                )

                if (samples.none { it.label.startsWith("blur ($label)") }) {
                    samples += SampleFrames(
                        label = "blur ($label) op ${clipTimeUs / 1_000_000.0}s",
                        left = thumbnail(frame) to "resultaat",
                        right = null,
                    )
                }

                frame.recycle()
            }
        }

        return MaskedBlurResult(label = label, inPointUs = inPointUs, samples = measurements)
    }

    // ----------------------------------------------------------------- helpers

    private fun project(sourceUri: String, inPointUs: Long, effects: List<EffectSpec>) = Project(
        id = "spike",
        sequences = listOf(
            Sequence(
                id = "main",
                items = listOf(
                    Clip(
                        id = "clip",
                        sourceUri = sourceUri,
                        inPointUs = inPointUs,
                        outPointUs = TestMediaFactory.DURATION_US,
                        effects = effects,
                    ),
                ),
            ),
        ),
        outputSpec = OutputSpec(OUTPUT_WIDTH, OUTPUT_HEIGHT, TestMediaFactory.FRAME_RATE),
    )

    /**
     * Tien tijdstippen, met de uiteinden gemeden: het allereerste en allerlaatste
     * frame verschillen tussen decoders om redenen die niets met de renderketen te
     * maken hebben.
     */
    private fun parityTimestamps(): List<Long> {
        val usable = TestMediaFactory.DURATION_US - 2 * EDGE_MARGIN_US
        return (0 until PARITY_SAMPLE_COUNT).map { index ->
            EDGE_MARGIN_US + usable * index / (PARITY_SAMPLE_COUNT - 1)
        }
    }

    private fun blurTimestamps(inPointUs: Long): List<Long> {
        val clipDuration = TestMediaFactory.DURATION_US - inPointUs
        val usable = clipDuration - 2 * EDGE_MARGIN_US
        return (0 until BLUR_SAMPLE_COUNT).map { index ->
            EDGE_MARGIN_US + usable * index / (BLUR_SAMPLE_COUNT - 1)
        }
    }

    /** Een punt ver van de cirkel, om de geblurde achtergrond te bemonsteren. */
    private fun farFrom(x: Float, y: Float): Pair<Float, Float> {
        // Diagonaal spiegelen en dan naar binnen halen, zodat het punt altijd binnen
        // het beeld valt maar nooit vlak naast de cirkel ligt.
        val mirroredX = (1f - x).coerceIn(0.15f, 0.85f)
        val mirroredY = (1f - y).coerceIn(0.15f, 0.85f)
        return if (hypot(mirroredX - x, mirroredY - y) >= MIN_CENTRE_SEPARATION) {
            mirroredX to mirroredY
        } else {
            // De cirkel staat in het midden; dan is een hoek het verst weg.
            0.15f to 0.15f
        }
    }

    private fun thumbnail(frame: Bitmap): Bitmap {
        val scale = THUMBNAIL_WIDTH.toFloat() / frame.width
        return Bitmap.createScaledBitmap(
            frame,
            THUMBNAIL_WIDTH,
            (frame.height * scale).toInt(),
            true,
        )
    }

    private companion object {
        const val OUTPUT_WIDTH = TestMediaFactory.WIDTH
        const val OUTPUT_HEIGHT = TestMediaFactory.HEIGHT

        const val PARITY_SAMPLE_COUNT = 10
        const val BLUR_SAMPLE_COUNT = 6

        const val EDGE_MARGIN_US = 500_000L
        const val TRIM_IN_POINT_US = 3_000_000L

        const val BLUR_RADIUS_FRAC = 0.03f

        /** Halve zijde van het meetvenster, in pixels van het uitvoerframe. */
        const val WINDOW_HALF_PX = 40

        /**
         * Twee plekken moeten minstens zo ver uit elkaar liggen voordat een
         * vergelijking tussen die twee iets zegt. Uitgedrukt in genormaliseerde
         * afstand; ruim meer dan de straal van de cirkel.
         */
        const val MIN_CENTRE_SEPARATION = 0.25f

        const val THUMBNAIL_WIDTH = 240
    }
}
