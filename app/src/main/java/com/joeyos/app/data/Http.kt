package com.joeyos.app.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap

/**
 * Plain HTTP GETs, written once. Every fetch used to open its own HttpURLConnection, and some
 * forgot to disconnect or to check the answer's code. These always disconnect, and only read a
 * body for a 2xx answer. Network errors are thrown, so each caller keeps its own handling.
 *
 * Never log a whole URL from here: RetroAchievements URLs carry the API key.
 */
object Http {

    /** [bytes] is the body of a 2xx answer, null otherwise (a 304, an error). */
    class Response(val code: Int, val bytes: ByteArray?, private val headers: Map<String, List<String>>) {
        val ok: Boolean get() = code in 200..299
        val text: String? get() = bytes?.toString(Charsets.UTF_8)
        /** A response header by name, any case (as HttpURLConnection's own lookup). */
        fun header(name: String): String? = headers[name]?.lastOrNull()
    }

    /**
     * [userAgent] null leaves Android's default: some callers never set one, and a site that
     * accepted that before is left as it was.
     */
    fun get(
        url: String,
        accept: String? = null,
        userAgent: String? = null,
        connectMs: Int = 10_000,
        readMs: Int = 10_000,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val conn = open(url, accept, userAgent, connectMs, readMs, headers)
        try {
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.use { it.readBytes() } else null
            // Case-insensitive, like getHeaderField; the null key is the status line.
            val fields = TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER)
            conn.headerFields.forEach { (k, v) -> if (k != null) fields[k] = v }
            return Response(code, body, fields)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams a 2xx answer into [target] without holding it in memory, calling [onBytes] with the
     * running total. Returns the bytes written; throws on a non-2xx answer or a network error.
     */
    fun download(
        url: String,
        target: File,
        accept: String? = null,
        userAgent: String? = null,
        connectMs: Int = 15_000,
        readMs: Int = 30_000,
        onBytes: (Long) -> Unit = {},
    ): Long {
        val conn = open(url, accept, userAgent, connectMs, readMs, emptyMap())
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            var written = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onBytes(written)
                    }
                }
            }
            return written
        } finally {
            conn.disconnect()
        }
    }

    private fun open(
        url: String, accept: String?, userAgent: String?, connectMs: Int, readMs: Int, headers: Map<String, String>,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = connectMs
        readTimeout = readMs
        // Followed within the same scheme: GitHub release assets and archive.org both redirect.
        instanceFollowRedirects = true
        accept?.let { setRequestProperty("Accept", it) }
        userAgent?.let { setRequestProperty("User-Agent", it) }
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
}
