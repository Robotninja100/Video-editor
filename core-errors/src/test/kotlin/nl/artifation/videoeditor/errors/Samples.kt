package nl.artifation.videoeditor.errors

/**
 * Eén voorbeeld per variant uit de taxonomie.
 *
 * `EditorErrorCoverageTest` bewaakt dat deze lijst compleet blijft, zodat een
 * nieuwe variant niet stilletjes langs de teksttests glipt.
 */
internal const val SAMPLE_PATH: String = "/storage/emulated/0/DCIM/Camera/vakantie-dag3.mp4"
internal const val SAMPLE_FILE_NAME: String = "vakantie-dag3.mp4"

internal val ALL_ERRORS: List<EditorError> = listOf(
    EditorError.OutOfStorage(requiredBytes = 3L * GB, availableBytes = 1L * GB),
    EditorError.FileMissing(SAMPLE_PATH),
    EditorError.FileUnreadable(SAMPLE_PATH, detail = "unexpected end of stream"),
    EditorError.UnsupportedMedia(SAMPLE_PATH, formatHint = "prores"),
    EditorError.ProjectDamaged(detail = "unexpected end of input at offset 812"),
    EditorError.OutdatedApp(fileVersion = 4, supportedVersion = 3),
    EditorError.StorageBusy(detail = "autosave bezig"),
    EditorError.CodecFailure(Stage.EXPORT, SAMPLE_PATH, detail = "encoder released"),
    EditorError.NetworkUnavailable(RemoteService.TRANSCRIPTION),
    EditorError.ServiceTimeout(RemoteService.SEGMENTATION, waitedMs = 30_000L),
    EditorError.ServiceFailure(RemoteService.AUTO_EDIT, statusCode = 502),
    EditorError.RateLimited(RemoteService.TRANSCRIPTION, retryAfterMs = 90_000L),
    EditorError.AuthenticationRejected(RemoteService.TRANSCRIPTION),
    EditorError.InvalidInput(InputProblem.EMPTY_TIMELINE),
    EditorError.Cancelled(Stage.EXPORT),
    EditorError.Overheated(measuredCelsius = 47),
    EditorError.StageFailure(Stage.TRANSCRIPTION, EditorError.NetworkUnavailable()),
    EditorError.Multiple(
        listOf(
            EditorError.FileMissing(SAMPLE_PATH),
            EditorError.NetworkUnavailable(RemoteService.AUTO_EDIT),
        ),
    ),
)

/**
 * De randgevallen erbij: de takken die pas ontstaan als een veld leeg is of een
 * statuscode de andere kant op valt. Ook die teksten moeten deugen.
 */
internal val ALL_MESSAGE_CASES: List<EditorError> = ALL_ERRORS +
    listOf(
        EditorError.OutOfStorage(requiredBytes = 100L, availableBytes = 0L),
        EditorError.NetworkUnavailable(service = null),
        EditorError.ServiceTimeout(service = null),
        EditorError.ServiceFailure(RemoteService.TRANSCRIPTION, statusCode = 400),
        EditorError.RateLimited(RemoteService.SEGMENTATION, retryAfterMs = null),
        EditorError.Cancelled(stage = null),
        EditorError.Overheated(measuredCelsius = null),
        EditorError.CodecFailure(Stage.ANALYSIS),
    ) +
    InputProblem.entries.map { EditorError.InvalidInput(it) } +
    Stage.entries.map { EditorError.StageFailure(it, EditorError.Overheated()) } +
    Stage.entries.map { EditorError.Cancelled(it) }
