package nl.artifation.videoeditor.jobs

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
