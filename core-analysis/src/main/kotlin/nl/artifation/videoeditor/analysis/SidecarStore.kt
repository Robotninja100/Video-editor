package nl.artifation.videoeditor.analysis

import java.io.File
import java.security.MessageDigest

/**
 * Opslag voor [Sidecar]s. Als interface zodat tests en de app niet aan schijf vastzitten.
 */
public interface SidecarStore {

    /** `null` betekent: niet aanwezig, onleesbaar of verouderd — in alle gevallen opnieuw analyseren. */
    public fun read(sourceUri: String): Sidecar?

    public fun write(sidecar: Sidecar)

    public fun delete(sourceUri: String)
}

/**
 * Sidecars als losse JSON-bestanden in één map.
 *
 * Gebruikt `java.io.File` en geen Android-`Context`, zodat deze module pure JVM blijft
 * en zonder emulator te testen is. De app geeft er gewoon `context.cacheDir` in.
 */
public class FileSidecarStore(
    private val directory: File,
) : SidecarStore {

    override fun read(sourceUri: String): Sidecar? {
        val file = fileFor(sourceUri)
        if (!file.isFile) return null

        // Een kapot of half geschreven cachebestand is geen fout om over te struikelen:
        // opnieuw analyseren kost tijd, doorgaan met rommel kost een verkeerde export.
        val sidecar = runCatching { SidecarJson.decode(file.readText()) }.getOrNull() ?: return null

        if (sidecar.formatVersion != Sidecar.FORMAT_VERSION) return null
        if (sidecar.sourceUri != sourceUri) return null

        return sidecar
    }

    /**
     * Schrijft eerst een tijdelijk bestand en hernoemt daarna. Zo staat er nooit een
     * half bestand op schijf als het proces halverwege wordt afgeschoten.
     */
    override fun write(sidecar: Sidecar) {
        directory.mkdirs()
        val target = fileFor(sidecar.sourceUri)
        val temp = File(directory, "${target.name}.tmp")

        temp.writeText(SidecarJson.encode(sidecar))
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "kon sidecar niet op zijn plek zetten: $target" }
        }
    }

    override fun delete(sourceUri: String) {
        fileFor(sourceUri).delete()
    }

    /**
     * Bestandsnaam uit een hash van de URI: die kan van alles bevatten wat een
     * bestandssysteem niet aankan. De URI staat óók in het bestand zelf, zodat een
     * hashbotsing bij het lezen opvalt in plaats van stilletjes de verkeerde analyse
     * op te leveren.
     */
    internal fun fileFor(sourceUri: String): File = File(directory, "${hash(sourceUri)}.json")

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
