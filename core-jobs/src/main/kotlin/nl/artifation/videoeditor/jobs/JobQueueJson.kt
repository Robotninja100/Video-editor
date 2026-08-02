package nl.artifation.videoeditor.jobs

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * De hele wachtrij als één serialiseerbaar geheel.
 *
 * De volgorde in [jobs] is de wachtrijvolgorde op het moment van opslaan; die
 * blijft bij het laden bewaard, zodat twee taken met exact dezelfde prioriteit
 * en indientijd na een herstart niet van plek wisselen.
 */
@Serializable
public data class QueueSnapshot(
    val jobs: List<Job>,
    /** Meegeschreven zodat een later formaat een oud bestand kan herkennen. */
    val version: Int = CURRENT_VERSION,
) {
    public companion object {
        /**
         * Twee sinds `lastError` een `EditorError` is en geen vrije tekst meer:
         * een bestand van versie 1 heeft daar een string staan en is dus niet
         * meer te lezen. Het nummer is er juist om dat te kunnen zien.
         */
        public const val CURRENT_VERSION: Int = 2
    }
}

/**
 * Persistentie van de wachtrij.
 *
 * `encodeDefaults = true`, anders dan bij projectbestanden: een afgeleide
 * standaardwaarde (`estimatedWorkUs` volgt uit `kind`) zou bij weglaten stilletjes
 * veranderen zodra de schatting in de code wordt bijgesteld, en dan verspringt de
 * balk van een taak die al half klaar is. `ignoreUnknownKeys` houdt een bestand
 * van een oudere versie leesbaar.
 */
public object JobQueueJson {

    @OptIn(ExperimentalSerializationApi::class)
    public val format: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }

    public fun encode(snapshot: QueueSnapshot): String = format.encodeToString(snapshot)

    public fun decode(text: String): QueueSnapshot = format.decodeFromString(text)
}
