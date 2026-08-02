package nl.artifation.videoeditor.analysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.artifation.videoeditor.model.Cue
import nl.artifation.videoeditor.model.Keyframe
import nl.artifation.videoeditor.model.NormRect
import nl.artifation.videoeditor.model.Us

/**
 * Het analyseresultaat van één **bronclip**.
 *
 * Bewust per bron en niet per tijdlijnpositie: staat dezelfde opname drie keer op de
 * tijdlijn, dan hoort hij één keer geanalyseerd te worden. Splitsen en trimmen
 * veranderen de sidecar dus niet — het omrekenen naar cliptijd gebeurt pas in
 * `Project.toRenderPlan()`.
 *
 * Een sidecar is **cache, geen brondata**. Alles hierin is opnieuw uit te rekenen uit
 * het bronbestand; verlies is hooguit vervelend, nooit erg. Daarom mag hij ook zonder
 * te vragen weggegooid worden als [formatVersion] niet meer klopt.
 */
@Serializable
public data class Sidecar(
    val sourceUri: String,
    val sourceDurationUs: Us,
    val formatVersion: Int = FORMAT_VERSION,
    val silences: List<SilenceInterval> = emptyList(),
    val loudness: LoudnessResult? = null,
    val cues: List<Cue> = emptyList(),
    val sceneCutsUs: List<Us> = emptyList(),
    val reframePath: List<Keyframe<NormRect>> = emptyList(),
    /** Maskvideo uit fase 6; koppelt aan `EffectSpec.MaskedBlur`. */
    val maskUri: String? = null,
) {
    public companion object {
        /** Ophogen zodra de betekenis van een veld verandert. Oude bestanden vervallen dan. */
        public const val FORMAT_VERSION: Int = 1
    }
}

/**
 * Eigen JSON-instelling, met opzet anders dan `ProjectJson`.
 *
 * `encodeDefaults = true` is hier essentieel: zonder dat blijft [Sidecar.formatVersion]
 * weg uit het bestand zodra hij gelijk is aan de default, en dan zou een oud bestand
 * later als "huidige versie" ingelezen worden. Compactheid is voor een cachebestand
 * minder waard dan die zekerheid.
 */
public object SidecarJson {

    public val format: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }

    public fun encode(sidecar: Sidecar): String = format.encodeToString(sidecar)

    public fun decode(text: String): Sidecar = format.decodeFromString(text)
}
