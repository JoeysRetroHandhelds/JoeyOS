package com.joeyos.app.data

import java.io.File
import java.net.HttpURLConnection

/**
 * A cached download that only pays for changes.
 *
 * The point is a cheap daily check: the ETag from last time is sent as `If-None-Match`, and an
 * unchanged file comes back as a bodyless `304`, which costs nothing to speak of. Only a real
 * change transfers the file. So a source can be re-checked often without re-downloading it
 * often, which is what lets even the large lists refresh daily.
 *
 * The content is written to [target] and the ETag beside it; the current text is returned,
 * whether it came fresh off the wire or, on a 304, off the disk. Null only when there is
 * nothing on the wire and nothing cached.
 */
object ConditionalFetch {

    fun text(url: String, target: File, etagFile: File): String? {
        val etag = etagFile.takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }
        return runCatching {
            val conditional = if (!etag.isNullOrBlank() && target.isFile) mapOf("If-None-Match" to etag) else emptyMap()
            val response = Http.get(url, connectMs = 15_000, readMs = 60_000, headers = conditional)
            when (response.code) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    // Nothing moved. Mark the cache fresh so it is not re-checked until the
                    // next window, and hand back what is already on disk.
                    target.setLastModified(System.currentTimeMillis())
                    target.takeIf { it.isFile }?.readText()
                }
                in 200..299 -> {
                    val body = response.text ?: ""
                    runCatching {
                        target.parentFile?.mkdirs()
                        target.writeText(body)
                        response.header("ETag")?.let { etagFile.writeText(it) }
                    }
                    body
                }
                else -> null
            }
        }.getOrNull()
    }
}
