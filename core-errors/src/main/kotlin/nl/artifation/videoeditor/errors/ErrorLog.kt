package nl.artifation.videoeditor.errors

/**
 * De technische kant van een fout: één regel voor het log.
 *
 * Twee dingen komen er nooit in terecht. Een sleutel of een token, want een log
 * wordt gedeeld, geplakt in een issue en meegestuurd in een crashrapport. En een
 * volledig pad, want dat bevat mapnamen die niets met de fout te maken hebben.
 */
public object ErrorLog {

    /** Waar de details van een dienst ophouden interessant te zijn. */
    private const val MAX_DETAIL_LENGTH = 120

    private const val MASK = "***"

    /**
     * Patronen die op een geheim wijzen.
     *
     * Liever een keer te veel wegstrepen dan een sleutel in een log. De
     * vervangingen zijn zo gekozen dat ze zichzelf niet opnieuw raken: [redact]
     * twee keer draaien geeft hetzelfde resultaat.
     */
    private val SECRET_PATTERNS: List<Pair<Regex, String>> = listOf(
        // Authorization: Bearer <token>
        Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=\-]{6,}""") to "$1 $MASK",
        // api_key=..., "token": "...", secret: ...
        // De vooruitblik laat "Authorization: Bearer ..." aan het patroon hierboven
        // over; anders verdwijnt daar het woord "Bearer" en zie je niet meer wat
        // voor soort toegang het was.
        Regex(
            """(?i)\b(api[-_]?key|apikey|access[-_]?token|token|secret|password|wachtwoord|authorization)\b""" +
                """(["']?\s*[:=]\s*["']?)(?!bearer\b|basic\b)([^\s,;"'&}]+)""",
        ) to "$1$2$MASK",
        // Sleutels met een herkenbaar voorvoegsel, ook als ze los in een tekst staan.
        Regex("""\b(sk|gsk|xoxb|ghp|pk)[-_][A-Za-z0-9_\-]{8,}""") to MASK,
        // ...?key=...&sig=...
        Regex("""(?i)([?&](?:key|token|api_key|apikey|sig|signature)=)[^&\s]+""") to "$1$MASK",
    )

    /** Haalt alles weg wat op een sleutel lijkt. Idempotent. */
    public fun redact(text: String): String =
        SECRET_PATTERNS.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    /**
     * Eén logregel als `sleutel=waarde`-paren.
     *
     * De gebruikerstekst staat er bewust niet in: die verandert bij elke
     * herformulering en maakt oude regels onvergelijkbaar. De [EditorError.code]
     * is wat je in een log zoekt.
     */
    public fun line(error: EditorError): String = buildList {
        add("code=${error.code}")
        add("retryable=${error.retryable}")
        addAll(fieldsOf(error))
    }.joinToString(" ")

    private fun fieldsOf(error: EditorError): List<String> = when (error) {
        is EditorError.OutOfStorage -> listOf(
            "required=${error.requiredBytes}",
            "available=${error.availableBytes}",
        )
        is EditorError.FileMissing -> listOf("file=${fileNameOf(error.path)}")
        is EditorError.FileUnreadable -> listOfNotNull(
            "file=${fileNameOf(error.path)}",
            error.detail?.let { "detail=${detail(it)}" },
        )
        is EditorError.UnsupportedMedia -> listOfNotNull(
            "file=${fileNameOf(error.path)}",
            error.formatHint?.let { "format=${detail(it)}" },
        )
        is EditorError.ProjectDamaged -> listOfNotNull(error.detail?.let { "detail=${detail(it)}" })
        is EditorError.OutdatedApp -> listOf(
            "fileVersion=${error.fileVersion}",
            "supported=${error.supportedVersion}",
        )
        is EditorError.StorageBusy -> listOfNotNull(error.detail?.let { "detail=${detail(it)}" })
        is EditorError.CodecFailure -> listOfNotNull(
            "stage=${error.stage.name}",
            error.path?.let { "file=${fileNameOf(it)}" },
            error.detail?.let { "detail=${detail(it)}" },
        )
        is EditorError.NetworkUnavailable -> listOfNotNull(error.service?.let { "service=${it.name}" })
        is EditorError.ServiceTimeout -> listOfNotNull(
            error.service?.let { "service=${it.name}" },
            error.waitedMs?.let { "waitedMs=$it" },
        )
        is EditorError.ServiceFailure -> listOfNotNull(
            "service=${error.service.name}",
            "status=${error.statusCode}",
            error.retryAfterMs?.let { "retryAfterMs=$it" },
            error.detail?.let { "detail=${detail(it)}" },
        )
        is EditorError.RateLimited -> listOfNotNull(
            "service=${error.service.name}",
            error.retryAfterMs?.let { "retryAfterMs=$it" },
        )
        is EditorError.AuthenticationRejected -> listOf("service=${error.service.name}")
        is EditorError.InvalidInput -> listOf("problem=${error.problem.name}")
        is EditorError.Cancelled -> listOfNotNull(error.stage?.let { "stage=${it.name}" })
        is EditorError.Overheated -> listOfNotNull(error.measuredCelsius?.let { "celsius=$it" })
        // Genest, zodat de oorzaak in dezelfde regel meekomt en niet in een los bericht.
        is EditorError.StageFailure -> listOf("stage=${error.stage.name}", "cause=[${line(error.cause)}]")
        is EditorError.Multiple -> listOf(
            "count=${error.errors.size}",
            "causes=[${error.errors.joinToString("; ") { line(it) }}]",
        )
    }

    /** Geschoond, ingekort en zonder regeleindes — anders is het geen regel meer. */
    private fun detail(raw: String): String {
        val cleaned = redact(raw).replace(Regex("""\s+"""), " ").trim()
        return if (cleaned.length <= MAX_DETAIL_LENGTH) cleaned else cleaned.take(MAX_DETAIL_LENGTH) + "..."
    }
}
