package com.joeyos.app.data

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Turns a folder or file chosen in Android's system pickers (a tree or document URI) back
 * into a path.
 *
 * JoeyOS reads files directly with all-files access, so it wants a path, not a document URI.
 * Only local storage converts: internal storage ("primary:…") and SD cards ("XXXX-XXXX:…").
 * A cloud account or another app's provider has no path, so it returns null.
 */
object TreeUriPaths {

    fun toPath(uri: Uri): String? {
        if (uri.authority != STORAGE) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        return fromDocId(docId)?.takeIf { it.isDirectory }?.absolutePath
    }

    /** The same for a single file chosen with the file picker (a document URI). */
    fun documentToFile(uri: Uri): File? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        return when (uri.authority) {
            STORAGE   -> fromDocId(docId)
            // The Downloads entry hands out "raw:/storage/…" ids for files it can map to a path.
            DOWNLOADS -> docId.takeIf { it.startsWith("raw:") }?.let { File(it.removePrefix("raw:")) }
            else      -> null
        }?.takeIf { it.isFile }
    }

    private fun fromDocId(docId: String): File? {
        if (':' !in docId) return null
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val root = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
        return if (relative.isEmpty()) File(root) else File(root, relative)
    }

    private const val STORAGE = "com.android.externalstorage.documents"
    private const val DOWNLOADS = "com.android.providers.downloads.documents"
}
