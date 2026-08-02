package nl.artifation.videoeditor.model

/**
 * Vragen die de renderlaag per frame stelt.
 *
 * Deze functies horen hier en niet in `:core-render` om dezelfde reden als
 * [sourcePtsFor]: het is pure rekenkunde, en pure rekenkunde die per frame
 * draait is precies waar een fout stil blijft. Een crop die één keyframe te
 * vroeg omslaat of een ondertitel die op de grens flikkert, zie je op een
 * toestel pas als je ernaar zoekt — en hier in een test meteen.
 *
 * De renderlaag roept ze aan met een **brontijd**, dus met het resultaat van
 * [sourcePtsFor]: keyframes en cues horen bij de bronclip, niet bij de tijdlijn.
 */

/**
 * De crop op [atUs], lineair geïnterpoleerd tussen de omliggende keyframes.
 *
 * Buiten het pad wordt het eerste respectievelijk laatste keyframe vastgehouden.
 * Dat is bewust: een crop die aan het begin of eind naar een ongedefinieerde
 * waarde springt, is een zichtbare schok in beeld.
 */
public fun List<Keyframe<PlannedEffect.Crop>>.cropAt(atUs: Us): PlannedEffect.Crop? {
    if (isEmpty()) return null
    if (atUs <= first().atUs) return first().value
    if (atUs >= last().atUs) return last().value

    val nextIndex = indexOfFirst { it.atUs > atUs }
    val a = this[nextIndex - 1]
    val b = this[nextIndex]
    val span = (b.atUs - a.atUs).toFloat()
    val t = if (span <= 0f) 0f else (atUs - a.atUs) / span

    return PlannedEffect.Crop(
        left = lerp(a.value.left, b.value.left, t),
        right = lerp(a.value.right, b.value.right, t),
        bottom = lerp(a.value.bottom, b.value.bottom, t),
        top = lerp(a.value.top, b.value.top, t),
    )
}

/**
 * De cue die op [atUs] in beeld hoort, of `null` als er op dat moment niets staat.
 *
 * Het einde van een cue telt niet mee: twee cues die op elkaar aansluiten mogen
 * op de grens niet allebei actief zijn, anders knippert er een frame met de
 * verkeerde regel.
 */
public fun List<Cue>.activeAt(atUs: Us): Cue? =
    firstOrNull { atUs >= it.startUs && atUs < it.endUs }

/**
 * De index van [activeAt], of −1. Voor de renderlaag, die per cue een bitmap
 * bewaart en alleen wil weten of hij dezelfde nog kan gebruiken.
 */
public fun List<Cue>.activeIndexAt(atUs: Us): Int =
    indexOfFirst { atUs >= it.startUs && atUs < it.endUs }

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
