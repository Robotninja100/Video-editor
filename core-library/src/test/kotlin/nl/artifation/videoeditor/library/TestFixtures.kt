package nl.artifation.videoeditor.library

import nl.artifation.videoeditor.model.Clip
import nl.artifation.videoeditor.model.EffectSpec
import nl.artifation.videoeditor.model.Project
import nl.artifation.videoeditor.model.Sequence
import nl.artifation.videoeditor.model.US_PER_SECOND
import nl.artifation.videoeditor.model.Us

/** Asset met plausibele telefoonopname-metadata; alleen afwijkingen staan in de test zelf. */
internal fun testAsset(
    uri: String = "content://media/1",
    displayName: String = "VID_0001.mp4",
    durationUs: Us = 10 * US_PER_SECOND,
    width: Int = 1080,
    height: Int = 1920,
    frameRate: Float = 30f,
    hasAudio: Boolean = true,
    sizeBytes: Long = 12_345_678L,
    addedAtEpochMs: Long = 1_000L,
    sourceRevision: Long = 42L,
    contentHash: String? = null,
): MediaAsset = MediaAsset.create(
    uri = uri,
    displayName = displayName,
    durationUs = durationUs,
    width = width,
    height = height,
    frameRate = frameRate,
    hasAudio = hasAudio,
    sizeBytes = sizeBytes,
    addedAtEpochMs = addedAtEpochMs,
    sourceRevision = sourceRevision,
    contentHash = contentHash,
)

internal fun projectUsing(vararg uris: String, maskUris: List<String> = emptyList()): Project =
    Project(
        id = "p1",
        sequences = listOf(
            Sequence(
                id = "s1",
                items = uris.mapIndexed { index, uri ->
                    Clip(
                        id = "c$index",
                        sourceUri = uri,
                        inPointUs = 0L,
                        outPointUs = US_PER_SECOND,
                        effects = maskUris.map { EffectSpec.MaskedBlur(maskUri = it) },
                    )
                },
            ),
        ),
    )
