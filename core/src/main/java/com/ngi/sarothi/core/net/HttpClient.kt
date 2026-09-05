package com.ngi.sarothi.core.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Minimal HTTP client on `HttpURLConnection`.
 *
 * No third-party HTTP library is pulled in: Sarothi targets 3 GB phones, and
 * OkHttp plus its Kotlin/Okio dependency chain is a few hundred kilobytes of APK
 * and a non-trivial method count for four endpoints. `HttpURLConnection` handles
 * redirects, chunked encoding and TLS through the platform, which is all the
 * downloader and the connector REST calls need.
 */
class HttpClient(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectTimeoutMillis: Int = 20_000,
    private val readTimeoutMillis: Int = 60_000,
) {

    data class Response(
        val statusCode: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
        val finalUrl: String,
    ) {
        val isSuccess: Boolean get() = statusCode in 200..299

        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        fun bodyText(): String = body.toString(Charsets.UTF_8)

        override fun equals(other: Any?): Boolean =
            other is Response && statusCode == other.statusCode && body.contentEquals(other.body) &&
                finalUrl == other.finalUrl && headers == other.headers

        override fun hashCode(): Int =
            (((statusCode * 31) + body.contentHashCode()) * 31) + finalUrl.hashCode()
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), acceptGzip: Boolean = true): Response {
        val connection = open(url, "GET", headers, acceptGzip)
        try {
            val statusCode = connection.responseCode
            val body = readBody(connection, statusCode, acceptGzip)
            return Response(statusCode, sanitiseHeaders(connection), body, connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }

    fun post(
        url: String,
        body: ByteArray?,
        contentType: String? = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val allHeaders = headers.toMutableMap()
        if (contentType != null) allHeaders["Content-Type"] = contentType
        val connection = open(url, "POST", allHeaders, acceptGzip = true)
        try {
            connection.doOutput = body != null
            if (body != null) {
                connection.outputStream.use { it.write(body) }
            }
            val statusCode = connection.responseCode
            return Response(
                statusCode,
                sanitiseHeaders(connection),
                readBody(connection, statusCode, acceptGzip = true),
                connection.url.toString(),
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams a URL into [sink], resuming from [startByte] when the server honours
     * `Range`.
     *
     * Transparent gzip is disabled on purpose (`Accept-Encoding: identity`): a
     * decompressing stream would make byte offsets meaningless and corrupt a
     * resumed download.
     *
     * @param onChunk invoked with the number of bytes just written; return false to abort.
     * @return the total number of bytes appended to [sink].
     * @throws IOException on a transport failure — the caller resumes from the new
     *   file length against the next source.
     */
    fun streamTo(
        url: String,
        sink: OutputStream,
        startByte: Long,
        expectedTotalBytes: Long,
        headers: Map<String, String> = emptyMap(),
        onChunk: (bytesWrittenSoFar: Long, totalBytes: Long) -> Boolean,
    ): StreamResult {
        val connection = open(url, "GET", headers, acceptGzip = false)
        try {
            if (startByte > 0) {
                connection.setRequestProperty("Range", "bytes=$startByte-")
            }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                return StreamResult.HttpError(statusCode, connection.responseMessage ?: "HTTP $statusCode")
            }

            val resumed = statusCode == HttpURLConnection.HTTP_PARTIAL && startByte > 0
            if (startByte > 0 && !resumed) {
                // The server ignored Range and is sending the whole file. Appending
                // would corrupt it, so the caller must truncate and start over.
                return StreamResult.RangeNotHonoured
            }

            val contentLength = connection.contentLengthLong
            val total = if (resumed) {
                startByte + (if (contentLength >= 0) contentLength else expectedTotalBytes - startByte)
            } else {
                if (contentLength >= 0) contentLength else expectedTotalBytes
            }

            val input: InputStream = connection.inputStream
            var written = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            input.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    if (!onChunk(written, total)) return StreamResult.Cancelled(written)
                }
            }
            sink.flush()
            return StreamResult.Completed(written, resumed)
        } catch (failure: IOException) {
            return StreamResult.TransportError(bytesWritten = 0L, cause = failure)
        } finally {
            connection.disconnect()
        }
    }

    sealed interface StreamResult {
        /** [bytesWritten] were appended; [resumed] says whether a Range was honoured. */
        data class Completed(val bytesWritten: Long, val resumed: Boolean) : StreamResult
        data class Cancelled(val bytesWritten: Long) : StreamResult
        data class HttpError(val statusCode: Int, val message: String) : StreamResult
        data class TransportError(val bytesWritten: Long, val cause: IOException) : StreamResult
        data object RangeNotHonoured : StreamResult
    }

    /**
     * `HttpURLConnection.headerFields` is keyed by a nullable String because the
     * status line has no header name; that entry is dropped here so callers get a
     * plain `Map<String, List<String>>`.
     */
    private fun sanitiseHeaders(connection: HttpURLConnection): Map<String, List<String>> =
        connection.headerFields
            .filterKeys { it != null }
            .mapKeys { (key, _) -> key as String }

    private fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
        acceptGzip: Boolean,
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.requestMethod = method
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty(
            "Accept-Encoding",
            if (acceptGzip) "gzip, identity" else "identity",
        )
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        return connection
    }

    private fun readBody(connection: HttpURLConnection, statusCode: Int, acceptGzip: Boolean): ByteArray {
        val raw: InputStream = if (statusCode in 400..599) {
            connection.errorStream ?: return ByteArray(0)
        } else {
            connection.inputStream
        }
        val encoding = connection.contentEncoding
        val stream = if (acceptGzip && encoding != null && encoding.contains("gzip", true)) {
            GZIPInputStream(raw)
        } else {
            raw
        }
        return stream.use { it.readBytes() }
    }

    companion object {
        const val DEFAULT_USER_AGENT = "Sarothi/1.0 (Android; on-device agent)"
        private const val BUFFER_SIZE = 64 * 1024
    }
}
