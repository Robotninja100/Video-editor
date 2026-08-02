// Het bestand heet naar wat het is — de gedeelde fixtures van deze module.
// `FakeThermalGate` is er toevallig het enige type van; de rest zijn bouwers.
@file:Suppress("MatchingDeclarationName")

package nl.artifation.videoeditor.jobs

import nl.artifation.videoeditor.errors.EditorError
import nl.artifation.videoeditor.errors.Jitter
import nl.artifation.videoeditor.errors.RemoteService
import nl.artifation.videoeditor.errors.RetryPolicy
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.model.Us

/**
 * Een gate die precies teruggeeft wat de test wil, en onthoudt met welk tijdstip
 * hij geraadpleegd is. De echte implementatie leest sensoren en zit in een andere
 * module; hier is alleen het koppelvlak in beeld.
 */
internal class FakeThermalGate(
    var next: WorkAllowance = WorkAllowance.of(5 * US_PER_SECOND),
) : ThermalGate {

    val calls: MutableList<Us> = mutableListOf()

    override fun allowance(nowUs: Us): WorkAllowance {
        calls.add(nowUs)
        return next
    }

    fun block(reason: String = "te warm") {
        next = WorkAllowance.blocked(reason)
    }

    fun open(chunkUs: Us = 5 * US_PER_SECOND) {
        next = WorkAllowance.of(chunkUs)
    }
}

/** Gate die altijd volop werk toestaat; voor tests waarin pacing niet het onderwerp is. */
internal fun openGate(): FakeThermalGate = FakeThermalGate()

internal fun analysisJob(
    id: String,
    enqueuedAtUs: Us = 0L,
    priority: JobPriority = JobPriority.Normal,
    clipDurationUs: Us = 60 * US_PER_SECOND,
    maxAttempts: Int = Job.DEFAULT_MAX_ATTEMPTS,
    sourceUri: String = "content://clips/$id",
): Job = Job(
    id = id,
    kind = JobKind.ClipAnalysis(sourceUri = sourceUri, clipDurationUs = clipDurationUs),
    enqueuedAtUs = enqueuedAtUs,
    priority = priority,
    maxAttempts = maxAttempts,
)

internal fun exportJob(
    id: String,
    enqueuedAtUs: Us = 0L,
    priority: JobPriority = JobPriority.High,
    outputDurationUs: Us = 600 * US_PER_SECOND,
    maxAttempts: Int = Job.DEFAULT_MAX_ATTEMPTS,
): Job = Job(
    id = id,
    kind = JobKind.Export(
        projectId = "project-1",
        outputUri = "content://export/$id",
        outputDurationUs = outputDurationUs,
    ),
    enqueuedAtUs = enqueuedAtUs,
    priority = priority,
    maxAttempts = maxAttempts,
)

/** Zet een taak op `Running` zonder de wachtrij; handig voor toestandstests. */
internal fun Job.running(nowUs: Us = 1L): Job = withState(JobState.Running, nowUs)

/** Een storing die overwaait: hierna hoort een taak terug in de wachtrij te komen. */
internal val NETWERK_WEG: EditorError = EditorError.NetworkUnavailable()

/** Een fout die door herhalen niet beter wordt. */
internal val BRON_KAPOT: EditorError = EditorError.FileUnreadable(path = "content://clips/export")

/** Een dienst die zelf zegt hoe lang je weg moet blijven. */
internal fun teVaak(retryAfterMs: Long?): EditorError =
    EditorError.RateLimited(service = RemoteService.TRANSCRIPTION, retryAfterMs = retryAfterMs)

/**
 * Een vaste jitterwaarde. `0.5` is het midden van het bereik en laat de berekende
 * wachttijd onveranderd; andere waarden schuiven hem omlaag of omhoog.
 */
internal fun jitterVan(value: Double): Jitter = Jitter { value }

/**
 * Beleid zonder wachttijd, voor tests waarin alleen de boekhouding rond opnieuw
 * proberen het onderwerp is. Zo hoeft daar geen tijd vooruitgezet te worden.
 */
internal val ZONDER_WACHTTIJD: RetryPolicy = RetryPolicy(baseDelayMs = 0L, maxDelayMs = 0L)
