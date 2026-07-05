package com.joeyos.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "RomFinder"

object RomFinder {

    private val SAVE_EXTENSIONS = setOf(
        "sav", "dsv", "srm", "SaveRAM",
        "mst", "state",
        "st0", "st1", "st2", "st3", "st4",
        "st5", "st6", "st7", "st8", "st9",
        "state1", "state2", "state3", "state4",
        "state5", "state6", "state7", "state8", "state9"
    )

    /**
     * Given a save/state file path, scans ES-DE ROM folders to find the
     * matching ROM by base filename. Returns null if the path is not a save
     * file or no matching ROM is found.
     *
     * Optionally restrict the search to a specific ES-DE system folder name
     * (e.g. "nds") to avoid scanning every system.
     */
    fun resolveRomFromSave(
        savePath: String,
        systemFolder: String? = null,
        romExtensions: Set<String>? = null
    ): String? {
        val file = File(savePath)
        Log.d(TAG, "resolveRomFromSave: savePath=$savePath ext=${file.extension} romExtensions=$romExtensions")
        if (file.extension.lowercase() !in SAVE_EXTENSIONS) {
            Log.d(TAG, "resolveRomFromSave: not a save extension, returning as-is")
            return savePath  // already a ROM
        }

        val baseName = file.nameWithoutExtension
        Log.d(TAG, "resolveRomFromSave: baseName=$baseName systemFolder=$systemFolder")
        val roots = storageRoots()

        for (root in roots) {
            val romsRoot = root.listFiles()?.firstOrNull {
                it.isDirectory && it.name.equals("roms", ignoreCase = true)
            } ?: continue

            val systemDirs = if (systemFolder != null) {
                romsRoot.listFiles()?.filter {
                    it.isDirectory && it.name.equals(systemFolder, ignoreCase = true)
                } ?: emptyList()
            } else {
                romsRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
            }
            Log.d(TAG, "resolveRomFromSave: romsRoot=${romsRoot.absolutePath} systemDirs=${systemDirs.map { it.name }}")

            for (dir in systemDirs) {
                val candidates = dir.listFiles()?.filter { romFile ->
                    romFile.nameWithoutExtension.equals(baseName, ignoreCase = true) &&
                    romFile.extension.lowercase() !in SAVE_EXTENSIONS
                } ?: continue
                val rom = if (romExtensions != null) {
                    candidates.firstOrNull { it.extension.lowercase() in romExtensions }
                        ?: candidates.firstOrNull()
                } else {
                    candidates.firstOrNull()
                }
                if (rom != null) {
                    Log.d(TAG, "resolveRomFromSave: found ROM=${rom.absolutePath}")
                    return rom.absolutePath
                }
            }
        }
        Log.d(TAG, "resolveRomFromSave: ROM not found for baseName=$baseName")
        return null
    }

    /**
     * Scans an ES-DE system folder for a ROM whose filename contains [titleHint].
     * Used when the save file is named by serial rather than by game title.
     */
    fun findRomByTitle(titleHint: String, systemFolder: String): String? {
        if (titleHint.isBlank()) return null
        val normalHint = normalizeTitle(titleHint)
        // If title has a subtitle separator (e.g. "Game – Switch 2 Edition"), also try
        // just the base part — ROM files rarely include edition suffixes.
        val shortHint = titleHint.substringBefore(" – ").substringBefore(" - ")
            .let { normalizeTitle(it) }.takeIf { it != normalHint && it.isNotBlank() }
        val roots = storageRoots()
        for (root in roots) {
            val romsRoot = root.listFiles()?.firstOrNull {
                it.isDirectory && it.name.equals("roms", ignoreCase = true)
            } ?: continue
            val systemDir = romsRoot.listFiles()?.firstOrNull {
                it.isDirectory && it.name.equals(systemFolder, ignoreCase = true)
            } ?: continue
            val files = systemDir.listFiles()?.filter { it.isFile } ?: continue
            Log.d(TAG, "findRomByTitle: searching ${systemDir.absolutePath} for '$titleHint' (normalized='$normalHint' short='$shortHint') among ${files.size} files")
            val rom = pickBestMatch(files, normalHint) ?: shortHint?.let { pickBestMatch(files, it) }
            if (rom != null) {
                Log.d(TAG, "findRomByTitle: found ${rom.absolutePath}")
                return rom.absolutePath
            }
        }
        Log.d(TAG, "findRomByTitle: no match for '$titleHint' in system '$systemFolder'")
        return null
    }

    // Normalize for fuzzy filename matching: strip punctuation that differs between
    // DB titles and ROM filenames (™, colons→dashes, brackets, etc.)
    private fun normalizeTitle(title: String): String =
        title.replace(Regex("[^\\w\\s]"), " ")
             .replace(Regex("\\s+"), " ")
             .trim()

    private data class RomCandidate(val file: File, val normalized: String)

    /**
     * Rank candidate ROM files against a normalized title hint rather than picking the
     * first filesystem match (which is arbitrary order and can pick a sequel — e.g.
     * "Mario Party 2" — when the hint was just "Mario Party" and both are installed).
     * Preference order: exact match, then prefix match where the text right after the
     * hint doesn't look like a sequel number, then substring match under the same rule.
     * Ties are broken by shortest filename (closest overall match to the hint). Always
     * returns a result if any candidate matches at all — never falls back to nothing
     * just because the ranking is ambiguous.
     */
    private fun pickBestMatch(files: List<File>, hint: String): File? {
        val candidates = files.map { RomCandidate(it, normalizeTitle(it.nameWithoutExtension)) }

        candidates.firstOrNull { it.normalized.equals(hint, ignoreCase = true) }?.let { return it.file }

        val startsWith = candidates.filter { it.normalized.startsWith(hint, ignoreCase = true) }
        if (startsWith.isNotEmpty()) {
            return startsWith.minWithOrNull(
                compareBy(
                    { c: RomCandidate -> looksLikeSequel(c.normalized, hint.length) },
                    { c: RomCandidate -> c.normalized.length }
                )
            )?.file
        }

        val contains = candidates.filter { it.normalized.contains(hint, ignoreCase = true) }
        if (contains.isNotEmpty()) {
            return contains.minWithOrNull(
                compareBy(
                    { c: RomCandidate -> looksLikeSequelAt(c.normalized, hint) },
                    { c: RomCandidate -> c.normalized.length }
                )
            )?.file
        }

        return null
    }

    /** True if the text right after the matched prefix starts with a digit (likely a sequel number). */
    private fun looksLikeSequel(normalized: String, prefixLength: Int): Boolean =
        normalized.substring(prefixLength).trimStart().firstOrNull()?.isDigit() == true

    private fun looksLikeSequelAt(normalized: String, hint: String): Boolean {
        val idx = normalized.indexOf(hint, ignoreCase = true)
        if (idx < 0) return false
        return normalized.substring(idx + hint.length).trimStart().firstOrNull()?.isDigit() == true
    }

    private val N64_ROM_EXTENSIONS = setOf("z64", "n64", "v64")

    /**
     * Finds a ROM in the given system folder whose internal N64 header title matches
     * [headerTitleHint] exactly (case-insensitive, whitespace-trimmed).
     *
     * M64Plus FZ names its save-data folders after this internal header field (e.g.
     * "CONKER BFD" for Conker's Bad Fur Day, "YOSHI STORY" for Yoshi's Story) — these
     * are fixed 20-byte ASCII fields baked into every N64 ROM, often abbreviated with
     * no resemblance to the retail title or filename, so filename-based fuzzy matching
     * (findRomByTitle) can never find them.
     */
    fun findN64RomByHeaderTitle(headerTitleHint: String, systemFolder: String = "n64"): String? {
        if (headerTitleHint.isBlank()) return null
        val roots = storageRoots()
        for (root in roots) {
            val romsRoot = root.listFiles()?.firstOrNull {
                it.isDirectory && it.name.equals("roms", ignoreCase = true)
            } ?: continue
            val systemDir = romsRoot.listFiles()?.firstOrNull {
                it.isDirectory && it.name.equals(systemFolder, ignoreCase = true)
            } ?: continue
            val files = systemDir.listFiles()?.filter { it.isFile } ?: continue
            for (file in files) {
                val title = readN64RomHeaderTitle(file) ?: continue
                if (title.equals(headerTitleHint, ignoreCase = true)) {
                    Log.d(TAG, "findN64RomByHeaderTitle: matched '$headerTitleHint' -> ${file.absolutePath}")
                    return file.absolutePath
                }
            }
        }
        Log.d(TAG, "findN64RomByHeaderTitle: no match for '$headerTitleHint' in system '$systemFolder'")
        return null
    }

    /** Reads the 20-byte ASCII title field from an N64 ROM's header (plain file or inside a zip). */
    private fun readN64RomHeaderTitle(file: File): String? {
        val headerBytes = try {
            if (file.extension.equals("zip", ignoreCase = true)) {
                java.util.zip.ZipFile(file).use { zip ->
                    val entry = zip.entries().asSequence()
                        .firstOrNull { e -> N64_ROM_EXTENSIONS.any { ext -> e.name.endsWith(".$ext", ignoreCase = true) } }
                        ?: return null
                    zip.getInputStream(entry).use { readExactly(it, 0x34) }
                }
            } else if (file.extension.lowercase() in N64_ROM_EXTENSIONS) {
                file.inputStream().use { readExactly(it, 0x34) }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } ?: return null
        return decodeN64HeaderTitle(headerBytes)
    }

    private fun readExactly(stream: java.io.InputStream, len: Int): ByteArray? {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = stream.read(buf, off, len - off)
            if (n < 0) return null
            off += n
        }
        return buf
    }

    /**
     * N64 ROMs come in three byte orders depending on dump/format: z64 (native big-endian),
     * v64 (16-bit byte-swapped), and n64 (32-bit little-endian). The title field is plain
     * ASCII, but byte-swapped formats scramble it just like every other field, so it must
     * be un-swapped before reading.
     */
    private fun decodeN64HeaderTitle(headerBytes: ByteArray): String? {
        if (headerBytes.size < 0x34) return null
        val b0 = headerBytes[0].toInt() and 0xFF
        val b1 = headerBytes[1].toInt() and 0xFF
        val normalized = ByteArray(headerBytes.size)
        when {
            b0 == 0x80 && b1 == 0x37 -> headerBytes.copyInto(normalized)
            b0 == 0x37 && b1 == 0x80 -> {
                var i = 0
                while (i + 1 < headerBytes.size) {
                    normalized[i] = headerBytes[i + 1]; normalized[i + 1] = headerBytes[i]
                    i += 2
                }
            }
            b0 == 0x40 && b1 == 0x12 -> {
                var i = 0
                while (i + 3 < headerBytes.size) {
                    normalized[i] = headerBytes[i + 3]; normalized[i + 1] = headerBytes[i + 2]
                    normalized[i + 2] = headerBytes[i + 1]; normalized[i + 3] = headerBytes[i]
                    i += 4
                }
            }
            else -> return null
        }
        val titleBytes = normalized.copyOfRange(0x20, 0x34)
        return String(titleBytes, Charsets.US_ASCII).trim { it <= ' ' }.takeIf { it.isNotBlank() }
    }

    /**
     * Convert a stored path (plain file path, file://, or content:// URI string) to a Uri
     * that can be granted to another app via FLAG_GRANT_READ_URI_PERMISSION.
     *
     * SAF externalstorage URIs (content://com.android.externalstorage.documents/...) are
     * scoped to the app that originally acquired them and cannot be re-granted. We decode
     * them back to a real file path and re-wrap via our own FileProvider.
     */
    fun pathToGrantableUri(context: Context, path: String): Uri {
        if (path.startsWith("file://")) return Uri.parse(path)
        if (path.startsWith("content://")) {
            val real = safExternalStorageToPath(Uri.parse(path))
            if (real != null) {
                val file = File(real)
                if (file.exists()) {
                    return FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                }
            }
            return Uri.parse(path)
        }
        return FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(path)
        )
    }

    /**
     * Decode a content://com.android.externalstorage.documents/... URI to a real path.
     * Document ID format: "primary:relative/path" or "VOLUME_ID:relative/path".
     */
    fun safExternalStorageToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val uriPath = uri.path ?: return null
        val docId = uriPath.substringAfterLast("/document/")
            .takeIf { it.contains(":") } ?: return null
        val decoded = java.net.URLDecoder.decode(docId, "UTF-8")
        val colon = decoded.indexOf(':')
        if (colon < 0) return null
        val volumeId = decoded.substring(0, colon)
        val relative  = decoded.substring(colon + 1)
        val root = if (volumeId == "primary") "/storage/emulated/0" else "/storage/$volumeId"
        return "$root/$relative"
    }

    fun storageRoots(): List<File> {
        val roots = mutableListOf(File("/storage/emulated/0"))
        File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
            ?.forEach { roots += it }
        return roots
    }
}
