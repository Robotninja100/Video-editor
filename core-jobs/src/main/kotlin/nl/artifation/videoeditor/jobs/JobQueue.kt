package nl.artifation.videoeditor.jobs

import nl.artifation.videoeditor.model.Us

/**
 * De wachtrij voor export- en analysetaken.
 *
 * Bewust synchroon en zonder eigen threads: de wachtrij bepaalt alleen *wat* er
 * moet gebeuren en in welke volgorde. Het daadwerkelijke uitvoeren, plannen en
 * pollen doet de laag erboven (op Android WorkManager), die na elk blok werk
 * terugmeldt. Daardoor is dit geheel deterministisch te testen.
 *
 * Alle tijdstippen komen van buiten binnen ([Us], microseconden); de klok wordt
 * hier nooit gelezen.
 */
public class JobQueue(
    private val gate: ThermalGate,
    initial: List<Job> = emptyList(),
    /**
     * Hoeveel taken er tegelijk mogen draaien. Eén is de juiste keuze op een
     * telefoon: twee encoders naast elkaar maken het geheel trager én heter.
     */
    private val maxRunning: Int = 1,
) {
    init {
        require(maxRunning >= 1) { "maxRunning moet >= 1 zijn, was $maxRunning" }
    }

    /**
     * Invoegvolgorde is de laatste scheidsrechter bij gelijke prioriteit én
     * gelijk tijdstip; `put` op een bestaande sleutel laat die volgorde staan,
     * dus een taak schuift niet naar achteren door een voortgangsmelding.
     */
    private val jobs = LinkedHashMap<String, Job>()

    init {
        for (job in initial) {
            require(job.id !in jobs) { "dubbele taak-id in de begintoestand: ${job.id}" }
            jobs[job.id] = job
        }
    }

    /** Alle taken, in wachtrijvolgorde (prioriteit, daarna wachttijd). */
    public val all: List<Job> get() = jobs.values.sortedWith(QUEUE_ORDER)

    public val size: Int get() = jobs.size

    public fun job(id: String): Job? = jobs[id]

    /** Taken die nog iets kunnen worden: `Queued`, `Running` of `Paused`. */
    public fun active(): List<Job> = all.filter { !it.isTerminal }

    public fun withState(state: JobState): List<Job> = all.filter { it.state == state }

    // ---------------------------------------------------------------- toevoegen

    /**
     * Zet een nieuwe taak in de wachtrij. Een dubbele id is een programmeerfout —
     * twee taken met dezelfde id zouden elkaars voortgang overschrijven.
     */
    public fun submit(job: Job): Job {
        require(job.id !in jobs) { "taak ${job.id} staat al in de wachtrij" }
        jobs[job.id] = job
        return job
    }

    /**
     * Zet [job] alleen in de wachtrij als hetzelfde werk er niet al in staat, en
     * geeft anders de bestaande taak terug.
     *
     * Nodig omdat dezelfde clip makkelijk twee keer aangeboden wordt — bij import
     * en nog eens zodra hij op de tijdlijn belandt. Afgeronde taken tellen niet
     * mee: opnieuw analyseren van een gewijzigd bestand moet gewoon kunnen.
     */
    public fun submitDeduplicated(job: Job): Job {
        val existing = jobs.values.firstOrNull { !it.isTerminal && it.kind == job.kind }
        return existing ?: submit(job)
    }

    // ------------------------------------------------------------------- kiezen

    /**
     * De taak die als eerste aan de beurt is, zonder de [ThermalGate] te
     * raadplegen en zonder iets te veranderen. Voor de UI ("volgende: export").
     */
    public fun nextCandidate(): Job? = jobs.values
        .filter { it.state == JobState.Queued }
        .minWithOrNull(QUEUE_ORDER)

    /**
     * Kiest de volgende taak en zet hem op `Running`.
     *
     * Geeft `null` als er niets te doen is, als er al genoeg draait, of als de
     * gate nu geen werk toestaat. De gate wordt vóór het kiezen geraadpleegd:
     * bij een geblokkeerde gate verandert er niets aan de wachtrij, zodat de
     * volgorde na het afkoelen exact hetzelfde is.
     */
    public fun startNext(nowUs: Us): JobLease? {
        if (runningCount() >= maxRunning) return null
        val allowance = gate.allowance(nowUs)
        if (!allowance.mayWork) return null
        val next = nextCandidate() ?: return null
        val started = store(next.withState(JobState.Running, nowUs))
        return JobLease(started, allowance.chunkUs)
    }

    // ------------------------------------------------------------- terugmelden

    /**
     * Voortgang van een draaiende taak, eventueel met een nieuw bewaarpunt.
     *
     * Voortgang kan alleen vooruit: een uitvoerder die per blok schat, mag de
     * balk niet terug laten springen.
     */
    public fun reportProgress(id: String, fraction: Float, resumeToken: String? = null): Job {
        val job = jobOrThrow(id)
        check(job.state == JobState.Running) {
            "voortgang van taak $id kan alleen terwijl hij draait, staat op ${job.state}"
        }
        return store(
            job.copy(
                progress = maxOf(job.progress, fraction.coerceIn(0f, 1f)),
                resumeToken = resumeToken ?: job.resumeToken,
            ),
        )
    }

    public fun succeed(id: String, nowUs: Us): Job =
        store(jobOrThrow(id).withState(JobState.Succeeded, nowUs))

    /**
     * Meldt een mislukte poging.
     *
     * @param retryable of het zin heeft het nog eens te proberen. Deze module
     *   verzint geen eigen foutentaxonomie — of een fout tijdelijk is (netwerk)
     *   of blijvend (kapot bronbestand) weet `:core-errors`, niet de wachtrij.
     */
    public fun fail(id: String, nowUs: Us, reason: String, retryable: Boolean): Job {
        val job = jobOrThrow(id)
        if (!retryable || !job.canRetry) {
            return store(job.withState(JobState.Failed, nowUs).copy(lastError = reason))
        }
        return store(requeue(job, nowUs, reason))
    }

    public fun cancel(id: String, nowUs: Us): Job =
        store(jobOrThrow(id).withState(JobState.Cancelled, nowUs))

    /** Pauzeert een taak. Een gepauzeerde taak wordt nooit gekozen. */
    public fun pause(id: String, nowUs: Us): Job =
        store(jobOrThrow(id).withState(JobState.Paused, nowUs))

    /** Zet een gepauzeerde taak terug in de wachtrij; hij is niet meteen aan de beurt. */
    public fun resume(id: String, nowUs: Us): Job =
        store(jobOrThrow(id).withState(JobState.Queued, nowUs))

    /** Pauzeert alles wat nog kan draaien, bijvoorbeeld als de gebruiker de app verlaat. */
    public fun pauseAll(nowUs: Us): List<Job> = active()
        .filter { it.state != JobState.Paused }
        .map { pause(it.id, nowUs) }

    public fun resumeAll(nowUs: Us): List<Job> =
        withState(JobState.Paused).map { resume(it.id, nowUs) }

    /**
     * Haalt afgeronde taken weg. De geschiedenis is nuttig in de UI, maar moet
     * niet eeuwig meegroeien in het opgeslagen bestand.
     */
    public fun purgeTerminal(): Int {
        val done = jobs.values.filter { it.isTerminal }.map { it.id }
        done.forEach { jobs.remove(it) }
        return done.size
    }

    public fun remove(id: String): Job? = jobs.remove(id)

    // ---------------------------------------------------------------- voortgang

    /**
     * Voortgang over de hele wachtrij, gewogen naar geschat werk.
     *
     * Ongewogen zou de balk verspringen zodra een korte analyse klaar is terwijl
     * de export van een half uur nog moet beginnen. Geannuleerde en definitief
     * mislukte taken tellen helemaal niet mee: dat werk gebeurt nooit meer, en
     * meetellen zou de balk voorgoed onder de 100% houden.
     *
     * De breuk kán dalen als er tijdens het draaien werk bij komt — het plan
     * groeit dan. Wat nooit daalt is [QueueProgress.doneWorkUs]; daar hangt een
     * "x van y klaar"-tekst aan die niet achteruit loopt.
     */
    public fun progress(): QueueProgress {
        var totalWorkUs = 0L
        var doneWorkUs = 0L
        val counts = JobState.entries.associateWithTo(mutableMapOf()) { 0 }

        for (job in jobs.values) {
            counts[job.state] = counts.getValue(job.state) + 1
            when (job.state) {
                JobState.Cancelled, JobState.Failed -> Unit
                JobState.Succeeded -> {
                    totalWorkUs += job.estimatedWorkUs
                    doneWorkUs += job.estimatedWorkUs
                }
                else -> {
                    totalWorkUs += job.estimatedWorkUs
                    doneWorkUs += (job.estimatedWorkUs * job.progress).toLong()
                }
            }
        }

        return QueueProgress(
            // Niets meer te doen is klaar; anders staat een lege wachtrij op 0%.
            fraction = if (totalWorkUs == 0L) 1f else (doneWorkUs.toDouble() / totalWorkUs).toFloat(),
            doneWorkUs = doneWorkUs,
            totalWorkUs = totalWorkUs,
            queued = counts.getValue(JobState.Queued),
            running = counts.getValue(JobState.Running),
            paused = counts.getValue(JobState.Paused),
            succeeded = counts.getValue(JobState.Succeeded),
            failed = counts.getValue(JobState.Failed),
            cancelled = counts.getValue(JobState.Cancelled),
        )
    }

    // -------------------------------------------------------------- persistentie

    public fun snapshot(): QueueSnapshot = QueueSnapshot(jobs = all)

    // ------------------------------------------------------------------- intern

    private fun runningCount(): Int = jobs.values.count { it.state == JobState.Running }

    private fun store(job: Job): Job {
        jobs[job.id] = job
        return job
    }

    private fun jobOrThrow(id: String): Job =
        jobs[id] ?: throw IllegalArgumentException("onbekende taak: $id")

    public companion object {

        /** Hoogste prioriteit eerst, daarbinnen wie het langst wacht. */
        private val QUEUE_ORDER: Comparator<Job> =
            compareByDescending<Job> { it.priority.weight }.thenBy { it.enqueuedAtUs }

        internal const val INTERRUPTED: String = "onderbroken door een herstart van het proces"

        /**
         * Laadt een opgeslagen wachtrij en herstelt taken die stonden te draaien
         * toen het proces stierf.
         *
         * Zo'n taak eeuwig op `Running` laten staan is de ergste uitkomst: hij
         * blokkeert dan een plek en wordt nooit meer gekozen. Hij gaat dus terug
         * de wachtrij in — mét de al verbruikte poging, want een taak die het
         * proces sloopt moet niet oneindig blijven herstarten.
         */
        public fun restore(
            snapshot: QueueSnapshot,
            nowUs: Us,
            gate: ThermalGate,
            maxRunning: Int = 1,
        ): JobQueue {
            val repaired = snapshot.jobs.map { job ->
                when {
                    job.state != JobState.Running -> job
                    job.canRetry -> requeue(job, nowUs, INTERRUPTED)
                    else -> job.withState(JobState.Failed, nowUs).copy(lastError = INTERRUPTED)
                }
            }
            return JobQueue(gate = gate, initial = repaired, maxRunning = maxRunning)
        }

        /**
         * Terug de wachtrij in na een fout of een herstart.
         *
         * Zonder bewaarpunt begint de volgende poging bij nul, dus dan moet de
         * voortgang ook terug naar nul — anders belooft de balk werk dat opnieuw
         * gedaan wordt.
         */
        private fun requeue(job: Job, nowUs: Us, reason: String): Job =
            job.withState(JobState.Queued, nowUs).copy(
                lastError = reason,
                progress = if (job.resumeToken == null) 0f else job.progress,
            )
    }
}

/**
 * Toestemming om aan één taak te werken, met de blokgrootte die de [ThermalGate]
 * op dat moment toestond. De uitvoerder werkt hoogstens dat blok af en meldt
 * daarna terug.
 */
public data class JobLease(
    val job: Job,
    val chunkUs: Us,
)

/** Samengevoegde voortgang over de hele wachtrij. */
public data class QueueProgress(
    val fraction: Float,
    val doneWorkUs: Us,
    val totalWorkUs: Us,
    val queued: Int,
    val running: Int,
    val paused: Int,
    val succeeded: Int,
    val failed: Int,
    val cancelled: Int,
) {
    public val active: Int get() = queued + running + paused
    public val isIdle: Boolean get() = active == 0
}
