package nl.artifation.videoeditor.errors

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.util.concurrent.CancellationException

/**
 * De grens tussen de buitenwereld en de taxonomie.
 *
 * Statuscodes en excepties komen van diensten en van de JVM; daar valt niet mee
 * te redeneren over "heeft opnieuw proberen zin". Alles wat binnenkomt wordt
 * hier één keer vertaald, zodat de rest van de app alleen nog [EditorError] kent.
 */
public object ErrorMapper {

    /** Boven deze lengte is het antwoord van een dienst geen detail meer maar een dump. */
    private const val MAX_DETAIL_LENGTH = 200

    /** Hoe diep we een keten van oorzaken volgen voordat we het opgeven. */
    private const val MAX_CAUSE_DEPTH = 8

    // Alleen de statussen die deze mapper apart behandelt; de rest valt in de
    // vangnet-tak. `java.net.HttpURLConnection` kent er een paar, maar niet 429,
    // en half uit de ene bron putten en half uit de andere leest slechter.
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_REQUEST_TIMEOUT = 408
    private const val HTTP_PAYLOAD_TOO_LARGE = 413
    private const val HTTP_TOO_MANY_REQUESTS = 429

    /**
     * Vertaalt een HTTP-status naar de taxonomie, of null bij een geslaagd antwoord.
     *
     * @param retryAfterSeconds de `Retry-After`-header, als de dienst die meestuurde.
     */
    public fun fromHttpStatus(
        statusCode: Int,
        service: RemoteService,
        body: String? = null,
        retryAfterSeconds: Long? = null,
    ): EditorError? {
        if (statusCode < HTTP_BAD_REQUEST) return null
        val retryAfterMs = retryAfterSeconds?.times(1_000L)
        val detail = body?.let { ErrorLog.redact(it).take(MAX_DETAIL_LENGTH) }?.ifBlank { null }

        return when (statusCode) {
            // Afgewezen toegang blijft afgewezen tot iemand de gegevens goedzet.
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> EditorError.AuthenticationRejected(service)
            HTTP_REQUEST_TIMEOUT -> EditorError.ServiceTimeout(service)
            // Te groot materiaal is een eigenschap van de invoer, geen storing.
            HTTP_PAYLOAD_TOO_LARGE -> EditorError.InvalidInput(InputProblem.FILE_TOO_LARGE)
            HTTP_TOO_MANY_REQUESTS -> EditorError.RateLimited(service, retryAfterMs)
            else -> EditorError.ServiceFailure(
                service = service,
                statusCode = statusCode,
                detail = detail,
                retryAfterMs = retryAfterMs,
            )
        }
    }

    /**
     * Leest een `Retry-After`-header uit.
     *
     * Alleen de vorm in hele seconden; de datumvorm laten we bewust liggen, want
     * die vergelijkt tegen de klok van het toestel en die klopt lang niet altijd.
     */
    public fun parseRetryAfterSeconds(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0L }

    /**
     * Vertaalt een JVM-exceptie naar de taxonomie, of null als hij niet te duiden is.
     *
     * Null is een bewuste uitkomst: een fout die we niet kennen, krijgt liever
     * geen verzonnen gebruikerstekst. De aanroeper weet wat hij aan het doen was
     * en kan daar een betere fout van maken.
     *
     * @param path het bestand waar de aanroeper mee bezig was, als dat er was.
     * @param service de dienst die hij aan het bevragen was, als dat er een was.
     */
    public fun fromThrowable(
        throwable: Throwable,
        path: String? = null,
        service: RemoteService? = null,
    ): EditorError? {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            classify(current, path, service)?.let { return it }
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return null
    }

    private fun classify(
        throwable: Throwable,
        path: String?,
        service: RemoteService?,
    ): EditorError? = when (throwable) {
        // Al vertaald: dan is die vertaling beter dan alles wat we hier zouden verzinnen.
        is EditorException -> throwable.error

        is CancellationException -> EditorError.Cancelled()

        is SecurityException ->
            EditorError.FileUnreadable(path ?: pathFromMessage(throwable), detailOf(throwable))

        // Vrijwel alles wat hier langskomt is een IOException; het onderscheid
        // zit in de subklasse, en dat is een verhaal op zich.
        is IOException -> classifyIo(throwable, path, service)

        else -> null
    }

    /**
     * De volgorde is hier niet vrij. De netwerk-subklassen zijn specifieker dan
     * de bestands-subklassen, en beide zijn specifieker dan `IOException` zelf —
     * die laatste is de vangnet-tak onderaan.
     */
    private fun classifyIo(
        throwable: IOException,
        path: String?,
        service: RemoteService?,
    ): EditorError? = when (throwable) {
        is FileNotFoundException, is NoSuchFileException ->
            EditorError.FileMissing(path ?: pathFromMessage(throwable))

        is AccessDeniedException ->
            EditorError.FileUnreadable(path ?: pathFromMessage(throwable), detailOf(throwable))

        // SocketTimeoutException is een InterruptedIOException; beide betekenen
        // hetzelfde voor de gebruiker.
        is InterruptedIOException -> EditorError.ServiceTimeout(service)

        is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketException ->
            EditorError.NetworkUnavailable(service)

        // Een afgebroken stroom betekent iets anders per kant: een half bestand
        // is stuk, een half antwoord is een verbinding die wegviel.
        is EOFException ->
            if (path != null) {
                EditorError.FileUnreadable(path, detailOf(throwable))
            } else {
                EditorError.NetworkUnavailable(service)
            }

        else -> when {
            isOutOfSpace(throwable) -> EditorError.OutOfStorage(requiredBytes = 0L, availableBytes = 0L)
            path != null -> EditorError.FileUnreadable(path, detailOf(throwable))
            service != null -> EditorError.NetworkUnavailable(service)
            else -> null
        }
    }

    /**
     * De kernel meldt een volle schijf via de tekst van een gewone IOException;
     * er bestaat geen aparte exceptie voor.
     */
    private fun isOutOfSpace(throwable: IOException): Boolean {
        val message = throwable.message?.lowercase() ?: return false
        return "no space left" in message || "enospc" in message || "disk full" in message
    }

    private fun pathFromMessage(throwable: Throwable): String =
        throwable.message?.substringBefore(" (")?.trim().orEmpty()

    /**
     * Het pad gaat er hier al af, niet pas bij het loggen.
     *
     * `detail` is een ontwikkelaarstekst die ook op een scherm terecht kan komen,
     * en de melding van de JVM begint bij een bestandsfout met het volledige pad.
     * Het echte pad zit al in `EditorError.path`; daar hoort het, en daar wordt
     * het bij het loggen ingekort.
     */
    private fun detailOf(throwable: Throwable): String? =
        throwable.message
            ?.let { shortenPaths(ErrorLog.redact(it)).take(MAX_DETAIL_LENGTH) }
            ?.ifBlank { null }
}
