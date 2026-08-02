package nl.artifation.videoeditor.pipeline

import nl.artifation.videoeditor.library.MediaAsset
import nl.artifation.videoeditor.model.US_PER_SECOND

internal fun asset(
    id: String,
    seconds: Long = 60,
    revision: Long = 1L,
) = MediaAsset(
    id = id,
    uri = "content://media/video/$id",
    displayName = "$id.mp4",
    durationUs = seconds * US_PER_SECOND,
    width = 1920,
    height = 1080,
    frameRate = 30f,
    hasAudio = true,
    sizeBytes = seconds * 4_000_000L,
    addedAtEpochMs = 0L,
    sourceRevision = revision,
)
