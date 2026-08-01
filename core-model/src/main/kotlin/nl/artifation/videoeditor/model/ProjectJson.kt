package nl.artifation.videoeditor.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Projectpersistentie.
 *
 * `encodeDefaults = false` houdt projectbestanden klein en leesbaar;
 * `ignoreUnknownKeys = true` zorgt dat een project van een oudere versie
 * blijft laden nadat er velden bij komen.
 */
public object ProjectJson {

    @OptIn(ExperimentalSerializationApi::class)
    public val format: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
    }

    public fun encode(project: Project): String = format.encodeToString(project)

    public fun decode(text: String): Project = format.decodeFromString(text)
}
