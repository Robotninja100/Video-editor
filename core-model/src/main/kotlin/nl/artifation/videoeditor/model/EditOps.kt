package nl.artifation.videoeditor.model

/**
 * Bewerkingen op de tijdlijn. Allemaal pure functies die een nieuwe [Sequence]
 * teruggeven — undo/redo is daardoor niets meer dan snapshots in een deque, en
 * immutable data classes geven structural sharing gratis.
 */

/**
 * Splitst het item dat [timelineUs] overlapt in tweeën.
 *
 * Op een itemgrens gebeurt er niets (er valt daar niets te splitsen).
 * De rechterhelft van een clip krijgt een afgeleide, deterministische id, zodat
 * hetzelfde project altijd dezelfde ids oplevert — belangrijk voor tests en diffs.
 */
public fun Sequence.splitAt(timelineUs: Us): Sequence {
    val located = locate(timelineUs) ?: return this
    val (index, item, offsetUs) = located
    if (offsetUs <= 0L || offsetUs >= item.durationUs) return this

    val (left, right) = when (item) {
        is Gap -> Gap(offsetUs) to Gap(item.durationUs - offsetUs)
        is Clip -> {
            val cutUs = item.inPointUs + (offsetUs * item.speed).toLong()
            if (cutUs <= item.inPointUs || cutUs >= item.outPointUs) return this
            item.copy(outPointUs = cutUs) to
                item.copy(id = "${item.id}@$cutUs", inPointUs = cutUs)
        }
    }

    return copy(
        items = buildList {
            addAll(items.subList(0, index))
            add(left)
            add(right)
            addAll(items.subList(index + 1, items.size))
        },
    )
}

/** Verwijdert een item; alles erachter schuift naar links (ripple delete). */
public fun Sequence.rippleDelete(index: Int): Sequence {
    requireValidIndex(index)
    return copy(items = items.filterIndexed { i, _ -> i != index })
}

/** Haalt een item weg maar laat het gat staan; niets verschuift (lift). */
public fun Sequence.lift(index: Int): Sequence {
    requireValidIndex(index)
    val item = items[index]
    if (item is Gap) return this
    return copy(items = items.toMutableList().apply { set(index, Gap(item.durationUs)) })
}

public fun Sequence.insertAt(index: Int, item: TimelineItem): Sequence {
    require(index in 0..items.size) { "index $index buiten bereik 0..${items.size}" }
    return copy(items = items.toMutableList().apply { add(index, item) })
}

/** Verplaatst een item naar een andere positie in de volgorde. */
public fun Sequence.moveItem(fromIndex: Int, toIndex: Int): Sequence {
    requireValidIndex(fromIndex)
    require(toIndex in items.indices) { "toIndex $toIndex buiten bereik ${items.indices}" }
    if (fromIndex == toIndex) return this
    return copy(
        items = items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) },
    )
}

/**
 * Plaatst [item] op tijdstip [atUs] en overschrijft wat daar stond.
 *
 * Dit is de drag-and-drop-semantiek van een echte editor: de tijdlijn wordt op
 * beide grenzen gesplitst en alles wat volledig binnen het venster valt, wijkt.
 * Ligt [atUs] voorbij het einde, dan wordt er een gat aangevuld.
 */
public fun Sequence.overwriteAt(atUs: Us, item: TimelineItem): Sequence {
    require(atUs >= 0) { "atUs moet >= 0 zijn, was $atUs" }
    val endUs = atUs + item.durationUs

    val padded = if (atUs > durationUs) {
        copy(items = items + Gap(atUs - durationUs))
    } else {
        this
    }

    val split = padded.splitAt(atUs).splitAt(endUs)
    val starts = split.itemStartsUs()

    val before = split.items.filterIndexed { i, item -> starts[i] + item.durationUs <= atUs }
    val after = split.items.filterIndexed { i, _ -> starts[i] >= endUs }

    return split.copy(items = before + item + after)
}

/**
 * Verplaatst de clip op [index] naar tijdstip [targetStartUs]: eerst liften
 * (het gat blijft staan), dan overschrijvend neerzetten.
 */
public fun Sequence.moveClipTo(index: Int, targetStartUs: Us): Sequence {
    requireValidIndex(index)
    val item = items[index]
    return lift(index).overwriteAt(targetStartUs, item)
}

/**
 * Past de in- en uitpunten van een clip aan. Verandert de duur, dus alles
 * erachter verschuift.
 */
public fun Sequence.trimClip(index: Int, newInPointUs: Us, newOutPointUs: Us): Sequence {
    requireValidIndex(index)
    val clip = items[index]
    require(clip is Clip) { "item op index $index is geen Clip" }
    require(newOutPointUs > newInPointUs) {
        "outPoint ($newOutPointUs) moet groter zijn dan inPoint ($newInPointUs)"
    }
    require(newInPointUs >= 0) { "inPoint moet >= 0 zijn, was $newInPointUs" }
    return copy(
        items = items.toMutableList().apply {
            set(index, clip.copy(inPointUs = newInPointUs, outPointUs = newOutPointUs))
        },
    )
}

/**
 * Ruimt de tijdlijn op: aangrenzende gaten worden samengevoegd en items zonder
 * duur verdwijnen. Verandert de totale duur niet.
 */
public fun Sequence.normalized(): Sequence {
    val out = mutableListOf<TimelineItem>()
    for (item in items) {
        if (item.durationUs <= 0L) continue
        val last = out.lastOrNull()
        if (item is Gap && last is Gap) {
            out[out.lastIndex] = Gap(last.durationUs + item.durationUs)
        } else {
            out.add(item)
        }
    }
    return copy(items = out)
}

private fun Sequence.requireValidIndex(index: Int) {
    require(index in items.indices) { "index $index buiten bereik ${items.indices}" }
}

/**
 * Interpoleert een crop-pad lineair op [atUs].
 *
 * Buiten het pad wordt het eerste respectievelijk laatste keyframe vastgehouden,
 * zodat de crop nooit naar een ongedefinieerde waarde springt.
 */
public fun List<Keyframe<NormRect>>.interpolateAt(atUs: Us): NormRect? {
    if (isEmpty()) return null
    if (atUs <= first().atUs) return first().value
    if (atUs >= last().atUs) return last().value

    val nextIndex = indexOfFirst { it.atUs > atUs }
    val a = this[nextIndex - 1]
    val b = this[nextIndex]
    val span = (b.atUs - a.atUs).toFloat()
    val t = if (span <= 0f) 0f else (atUs - a.atUs) / span

    return NormRect(
        left = a.value.left + (b.value.left - a.value.left) * t,
        top = a.value.top + (b.value.top - a.value.top) * t,
        right = a.value.right + (b.value.right - a.value.right) * t,
        bottom = a.value.bottom + (b.value.bottom - a.value.bottom) * t,
    )
}
