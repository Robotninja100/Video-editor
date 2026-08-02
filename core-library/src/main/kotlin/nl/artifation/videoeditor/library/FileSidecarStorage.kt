package nl.artifation.videoeditor.library

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Sidecars op schijf.
 *
 * Tot nu toe bestond alleen [InMemorySidecarStorage], en die overleeft het
 * afsluiten van de app niet — precies wat een analyse die minuten duurt niet mag
 * overkomen.
 *
 * `java.io.File` en geen Android-`Context`, zodat deze module pure JVM blijft en
 * met een tijdelijke map te testen is. De app geeft er `context.filesDir` in.
 * De paden komen van [SidecarPaths] en bevatten mapscheidingen, dus die worden
 * onder [root] gewoon als submappen aangemaakt.
 */
public class FileSidecarStorage(
    private val root: File,
    private val format: Json = DEFAULT_FORMAT,
) : SidecarStorage {

    override fun read(path: String): SidecarRecord? {
        val file = fileFor(path)
        if (!file.isFile) return null

        // Een half geschreven of kapot bestand is geen fout om over te struikelen:
        // opnieuw analyseren kost tijd, doorgaan met rommel kost een verkeerde edit.
        return runCatching { format.decodeFromString<SidecarRecord>(file.readText()) }.getOrNull()
    }

    /**
     * Schrijft eerst een tijdelijk bestand en hernoemt daarna.
     *
     * Zonder dat staat er na een crash halverwege een afgekapt JSON-bestand op
     * schijf, en dat leest als "analyse aanwezig" terwijl hij het niet is.
     */
    override fun write(path: String, record: SidecarRecord) {
        val file = fileFor(path)
        file.parentFile?.mkdirs()

        val temp = File(file.parentFile, "${file.name}$TEMP_SUFFIX")
        temp.writeText(format.encodeToString(record))

        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "kon sidecar niet op zijn plek zetten: $file" }
        }
    }

    override fun delete(path: String): Boolean = fileFor(path).delete()

    override fun exists(path: String): Boolean = fileFor(path).isFile

    /**
     * Alle sidecars onder [root], gesorteerd.
     *
     * Gesorteerd omdat de aanroeper er lijsten mee toont en opruimt: een volgorde
     * die per bestandssysteem verschilt maakt zowel de UI als een test wispelturig.
     */
    override fun paths(): List<String> {
        if (!root.isDirectory) return emptyList()

        return root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    /**
     * Weigert paden die buiten [root] wijzen.
     *
     * De paden komen van [SidecarPaths], die de asset-id al controleert, maar deze
     * klasse maakt bestanden aan en hoort niet te vertrouwen op wie hem aanroept.
     */
    private fun fileFor(path: String): File {
        val file = File(root, path).normalize()
        require(file.startsWith(root.normalize())) { "pad wijst buiten de sidecar-map: $path" }
        return file
    }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"

        val DEFAULT_FORMAT = Json {
            // Een veld erbij mag een oude sidecar niet onleesbaar maken; de
            // analyseversie in het record bepaalt of hij nog geldig is.
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
