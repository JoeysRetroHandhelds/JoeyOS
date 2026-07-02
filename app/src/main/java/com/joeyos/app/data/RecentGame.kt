package com.joeyos.app.data

import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "RecentGamesReader"
private const val CACHE_TTL_MS = 5 * 60 * 1000L

data class RecentGame(
    val title: String,
    val path: String,
    val emulatorPackage: String,
    val lastPlayed: Long = 0L,
    val corePath: String? = null  // RetroArch core .so path from history
)

object RecentGamesReader {

    // Per-package cache so subsequent popup opens are instant.
    // Keys = packageName, values = (timestamp, games list).
    private val pkgCacheTime    = ConcurrentHashMap<String, Long>()
    private val pkgCacheResults = ConcurrentHashMap<String, List<RecentGame>>()

    /** Clear all cached data so the next read hits the filesystem again. */
    fun invalidateCache() {
        pkgCacheTime.clear()
    }

    /**
     * Read all supported packages into the per-package cache.
     * Call this from a background coroutine (e.g., after apps load or on resume).
     */
    fun preWarm(installedApps: List<InstalledApp>, depth: Int = 20) {
        val now = System.currentTimeMillis()
        installedApps
            .filter { supportsRecentlyPlayed(it.packageName) }
            .forEach { app ->
                if (now - (pkgCacheTime[app.packageName] ?: 0L) >= CACHE_TTL_MS) {
                    val result = readForPackageUncached(app.packageName, depth)
                    pkgCacheResults[app.packageName] = result
                    pkgCacheTime[app.packageName] = now
                }
            }
    }

    private val SUPPORTED_PREFIXES = listOf(
        "org.ppsspp",
        "com.retroarch",
        "me.magnum.melonds",
        "me.magnum.melondualds",
        "org.dolphinemu",
        "xyz.aethersx2",
        "net.nicholaswilde.nethersx2",
        "com.armsx2",
        "org.azahar_emu",
        "info.cemu",
        "dev.eden",
        "org.vita3k",
        "com.github.stenzek.duckstation",
        "com.duckstation",
        "aenu.aps3e",
        "org.mupen64plusae"
    )

    fun supportsRecentlyPlayed(packageName: String): Boolean =
        SUPPORTED_PREFIXES.any { packageName.startsWith(it) }

    /** Cache-aware read for a single emulator package. */
    fun readForPackage(packageName: String, depth: Int = 20): List<RecentGame> {
        if (!supportsRecentlyPlayed(packageName)) return emptyList()
        val now = System.currentTimeMillis()
        val cachedTime = pkgCacheTime[packageName] ?: 0L
        if (now - cachedTime < CACHE_TTL_MS) {
            pkgCacheResults[packageName]?.let { return it.take(depth) }
        }
        return readForPackageUncached(packageName, depth).also { result ->
            pkgCacheResults[packageName] = result
            pkgCacheTime[packageName] = now
        }
    }

    /** Cache-aware read across all installed emulators. Uses per-package cache entries. */
    fun readAll(installedApps: List<InstalledApp>, depth: Int = 20): List<RecentGame> {
        val seen = mutableSetOf<String>()
        return installedApps
            .filter { supportsRecentlyPlayed(it.packageName) }
            .flatMap { app -> readForPackage(app.packageName, depth) }
            .sortedByDescending { it.lastPlayed }
            .filter { game -> seen.add("${game.emulatorPackage}::${game.title}") }
            .take(depth)
    }

    private fun readForPackageUncached(packageName: String, depth: Int = 20): List<RecentGame> {
        if (!supportsRecentlyPlayed(packageName)) return emptyList()
        return when {
            packageName.startsWith("org.ppsspp")            -> readPpsspp(packageName, depth)
            packageName.startsWith("com.retroarch")         -> readRetroArch(packageName, depth)
            packageName.startsWith("me.magnum.melonds") ||
            packageName.startsWith("me.magnum.melondualds") -> readMelonDS(packageName, depth)
            packageName.startsWith("org.dolphinemu")        -> readDolphin(packageName, depth)
            packageName.startsWith("xyz.aethersx2") ||
            packageName.startsWith("net.nicholaswilde.nethersx2") -> readNetherSX2(packageName, depth)
            packageName.startsWith("com.armsx2")                  -> readARMSX2(packageName, depth)
            packageName.startsWith("org.azahar_emu")        -> readAzahar(packageName, depth)
            packageName.startsWith("info.cemu")             -> readCemu(packageName, depth)
            packageName.startsWith("dev.eden")              -> readEden(packageName, depth)
            packageName.startsWith("org.vita3k")            -> readVita3K(packageName, depth)
            packageName.startsWith("com.github.stenzek.duckstation") ||
            packageName.startsWith("com.duckstation")       -> readDuckStation(packageName, depth)
            packageName.startsWith("aenu.aps3e")            -> readPs3(packageName, depth)
            packageName.startsWith("org.mupen64plusae")      -> readM64PlusFZ(packageName, depth)
            else -> emptyList()
        }
    }

    // ── PPSSPP ───────────────────────────────────────────────────────────────

    private fun readPpsspp(packageName: String, depth: Int = 20): List<RecentGame> {
        val roots = storageRoots()
        val candidates = roots.flatMap { root ->
            listOf(
                File(root, "PPSSPP/PSP/SYSTEM/ppsspp.ini"),
                File(root, "Android/data/$packageName/files/PSP/SYSTEM/ppsspp.ini"),
                File(root, "Android/data/org.ppsspp.ppsspp/files/PSP/SYSTEM/ppsspp.ini"),
                File(root, "Android/data/org.ppsspp.ppssppgold/files/PSP/SYSTEM/ppsspp.ini"),
            )
        }
        Log.d(TAG, "readPpsspp: checking candidates=${candidates.map { "${it.absolutePath} exists=${it.exists()}" }}")
        val ini = candidates.firstOrNull { it.exists() && it.canRead() }
        if (ini == null) {
            Log.d(TAG, "readPpsspp: no ppsspp.ini found, falling back to SAVEDATA scan")
            return readPpssppSaveData(packageName, roots, depth)
        }
        Log.d(TAG, "readPpsspp: reading ${ini.absolutePath}")
        val paths = mutableListOf<Pair<Int, String>>()
        var inRecent = false
        ini.forEachLine { raw ->
            val line = raw.trim()
            when {
                line == "[Recent]"               -> inRecent = true
                inRecent && line.startsWith("[") -> inRecent = false
                inRecent && line.startsWith("FileName") -> {
                    val idx = line.substringAfter("FileName")
                        .substringBefore(" ").substringBefore("=").trim()
                        .toIntOrNull() ?: return@forEachLine
                    val path = line.substringAfter("=").trim()
                    if (path.isNotEmpty()) paths += idx to path
                }
            }
        }
        Log.d(TAG, "readPpsspp: found ${paths.size} recent paths")
        // Anchor to the ini's real last-modified time rather than read time, so cross-emulator
        // sorting (readAll) reflects when PPSSPP actually last wrote to its recent list — not
        // whenever JoeyOS happened to read/cache this file.
        val now = ini.lastModified()
        val seen = mutableSetOf<String>()
        return paths
            .sortedBy { it.first }
            .mapNotNull { (_, path) ->
                // If path is a content:// or file:// URI, don't URL-decode it — the URI
                // scheme chars would get corrupted. Only decode plain percent-encoded file paths.
                val decodedPath = if (path.startsWith("content://") || path.startsWith("file://"))
                    path
                else
                    java.net.URLDecoder.decode(path, "UTF-8")
                if (!seen.add(decodedPath)) return@mapNotNull null
                // For content URIs the "file name" is the last path segment; for file paths it's nameWithoutExtension.
                val displayName = if (decodedPath.startsWith("content://"))
                    Uri.parse(decodedPath).lastPathSegment?.substringAfterLast('/')
                        ?.substringBeforeLast('.') ?: decodedPath
                else
                    File(decodedPath).nameWithoutExtension
                decodedPath to displayName
            }
            .take(depth)
            .mapIndexed { index, (decodedPath, displayName) ->
                // ppsspp.ini's [Recent] list has no real per-entry timestamps, only relative
                // order. Index 0 gets the file's real mtime (accurate); every entry after that
                // assumes a conservative 1-day gap so a cluster of recent-but-not-that-recent
                // PPSSPP plays doesn't outrank a genuinely recent play on another emulator in
                // the merged "Recent" view. This only affects cross-emulator ranking — within
                // PPSSPP's own list, order is unchanged (still most-recent-first).
                RecentGame(
                    title           = cleanTitle(displayName),
                    path            = decodedPath,
                    emulatorPackage = packageName,
                    lastPlayed      = now - index * 86_400_000L
                )
            }
    }

    private fun readPpssppSaveData(packageName: String, roots: List<File>, depth: Int = 20): List<RecentGame> {
        val saveDirs = roots.flatMap { root ->
            listOf(
                File(root, "PPSSPP/PSP/SAVEDATA"),
                File(root, "Android/data/$packageName/files/PSP/SAVEDATA"),
            )
        }
        Log.d(TAG, "readPpssppSaveData: scanning $saveDirs")
        val seen = mutableSetOf<String>()
        return saveDirs
            .flatMap { dir -> dir.listFiles()?.filter { it.isDirectory } ?: emptyList() }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { dir ->
                // PPSSPP save dir name: 9-char product code + truncated title (e.g. ULUS10512MYTITLE)
                val raw = if (dir.name.length > 9) dir.name.drop(9) else dir.name
                val title = cleanTitle(raw).ifBlank { dir.name }
                if (seen.add(title)) RecentGame(title, dir.absolutePath, packageName, dir.lastModified()) else null
            }
            .take(depth)
    }

    // ── RetroArch ────────────────────────────────────────────────────────────

    private fun retroArchRoots(packageName: String): List<File> {
        val candidates = listOf(
            File("/storage/emulated/0/RetroArch"),
            File("/storage/emulated/0/Android/data/$packageName/files"),
            File("/storage/emulated/0/Android/data/com.retroarch/files"),
            File("/storage/emulated/0/Android/data/com.retroarch.aarch64/files"),
            File("/storage/emulated/0/Android/data/com.retroarch.ra32/files")
        )
        Log.d(TAG, "retroArchRoots: checking ${candidates.size} candidates for pkg=$packageName")
        val found = candidates.filter { it.exists() }
        Log.d(TAG, "retroArchRoots: found roots = ${found.map { it.absolutePath }}")
        return found
    }

    private fun readRetroArch(packageName: String, depth: Int = 20): List<RecentGame> {
        Log.d(TAG, "readRetroArch: starting for pkg=$packageName")
        val fromHistory = readRetroArchHistory(packageName, depth)
        return if (fromHistory.isNotEmpty()) {
            Log.d(TAG, "readRetroArch: using history, ${fromHistory.size} games")
            fromHistory
        } else {
            Log.d(TAG, "readRetroArch: history empty, falling back to save files")
            readRetroArchSaveFiles(packageName, depth)
        }
    }

    private fun readRetroArchHistory(packageName: String, depth: Int = 20): List<RecentGame> {
        val roots = retroArchRoots(packageName)
        val lplCandidates = roots.map { File(it, "playlists/content_history.lpl") }
        Log.d(TAG, "readRetroArchHistory: checking lpl paths = ${lplCandidates.map { "${it.absolutePath} exists=${it.exists()} canRead=${it.canRead()}" }}")
        val lpl = lplCandidates.firstOrNull { it.exists() && it.canRead() }
        if (lpl == null) {
            Log.d(TAG, "readRetroArchHistory: no readable content_history.lpl found")
            return emptyList()
        }
        Log.d(TAG, "readRetroArchHistory: reading ${lpl.absolutePath}")
        return try {
            val json  = JSONObject(lpl.readText())
            val items = json.optJSONArray("items")
            if (items == null) {
                Log.d(TAG, "readRetroArchHistory: no 'items' array in JSON")
                return emptyList()
            }
            Log.d(TAG, "readRetroArchHistory: items array has ${items.length()} entries")
            val seen  = mutableSetOf<String>()
            val games = mutableListOf<RecentGame>()
            // Anchor to the lpl's real last-modified time rather than read time, so cross-emulator
            // sorting (readAll) reflects when RetroArch actually last wrote its history — not
            // whenever JoeyOS happened to read/cache this file.
            val now   = lpl.lastModified()
            for (i in 0 until items.length()) {
                if (games.size >= depth) break
                val item  = items.getJSONObject(i)
                val path     = item.optString("path").takeIf { it.isNotEmpty() } ?: continue
                val label    = item.optString("label").takeIf { it.isNotEmpty() }
                    ?: File(path).nameWithoutExtension
                val corePath = item.optString("core_path").takeIf { it.isNotEmpty() && it != "DETECT" }
                if (seen.add(label)) {
                    // content_history.lpl has no real per-entry timestamps either — same
                    // conservative 1-day-per-position gap as PPSSPP's [Recent] list, see there
                    // for why.
                    games += RecentGame(
                        title           = label,
                        path            = path,
                        emulatorPackage = packageName,
                        lastPlayed      = now - i * 86_400_000L,
                        corePath        = corePath
                    )
                    Log.d(TAG, "readRetroArchHistory: added '$label'")
                }
            }
            Log.d(TAG, "readRetroArchHistory: returning ${games.size} games")
            games
        } catch (e: Exception) {
            Log.e(TAG, "readRetroArchHistory: exception parsing lpl", e)
            emptyList()
        }
    }

    private fun readRetroArchSaveFiles(packageName: String, depth: Int = 20): List<RecentGame> {
        val validExtensions = setOf(
            "srm", "sav", "saveram",
            "state", "state0", "state1", "state2", "state3", "state4",
            "state5", "state6", "state7", "state8", "state9"
        )
        val roots = retroArchRoots(packageName)
        val dirs = roots.flatMap { root ->
            listOf(File(root, "saves"), File(root, "states"))
        }
        Log.d(TAG, "readRetroArchSaveFiles: scanning dirs = ${dirs.map { "${it.absolutePath} exists=${it.exists()}" }}")
        // Pair each file with the subdirectory name (= core name) if it came from one
        data class SaveEntry(val file: File, val coreHint: String?)
        val allEntries = dirs.flatMap { dir ->
            val top = dir.listFiles()?.toList() ?: emptyList()
            top.flatMap { entry ->
                if (entry.isDirectory) {
                    val sub = entry.listFiles()?.toList() ?: emptyList()
                    Log.d(TAG, "readRetroArchSaveFiles: subdir ${entry.name} -> ${sub.map { it.name }}")
                    sub.map { SaveEntry(it, entry.name) }
                } else listOf(SaveEntry(entry, null))
            }
        }
        val matched = allEntries.filter { it.file.isFile && it.file.extension.lowercase() in validExtensions }
        Log.d(TAG, "readRetroArchSaveFiles: ${matched.size} files matched")
        val seen = mutableSetOf<String>()
        val result = matched
            .sortedByDescending { it.file.lastModified() }
            .mapNotNull { (file, coreHint) ->
                val name = cleanTitle(file.nameWithoutExtension
                    .replace(Regex("\\.state\\d*$"), "")
                    .trim())
                if (name.isNotEmpty() && seen.add(name)) {
                    Log.d(TAG, "readRetroArchSaveFiles: adding '$name' coreHint=$coreHint")
                    RecentGame(name, file.absolutePath, packageName, file.lastModified(), corePath = coreHint)
                } else null
            }
            .take(depth)
        Log.d(TAG, "readRetroArchSaveFiles: returning ${result.size} games")
        return result
    }

    // ── melonDS / melonDS Dual ───────────────────────────────────────────────

    private val MELON_EXTENSIONS = setOf(
        "sav", "dsv", "srm",
        "mst", "state", "st0", "st1", "st2", "st3", "st4",
        "st5", "st6", "st7", "st8", "st9"
    )

    private fun readMelonDS(packageName: String, depth: Int = 20): List<RecentGame> {
        val roots = storageRoots()
        val scanDirs = mutableListOf<File>()
        for (root in roots) {
            for (name in listOf("melonDS", "MelonDualDS", "melonds")) {
                val base = File(root, name)
                if (base.isDirectory) {
                    scanDirs += File(base, "saves")
                    scanDirs += File(base, "states")
                    scanDirs += base
                }
            }
            for (rel in listOf("Roms/NDS", "Roms/nds", "ROMs/NDS", "ROMs/nds",
                               "roms/NDS", "roms/nds", "NDS", "nds")) {
                val dir = File(root, rel)
                if (dir.isDirectory) scanDirs += dir
            }
        }
        Log.d(TAG, "readMelonDS: scanDirs=${scanDirs.map { "${it.absolutePath} exists=${it.exists()}" }}")
        val allFiles = scanDirs.flatMap { dir -> dir.listFiles()?.toList() ?: emptyList() }
        Log.d(TAG, "readMelonDS: allFiles=${allFiles.map { it.name }}")
        val matchedFiles = allFiles.filter { it.isFile && it.extension.lowercase() in MELON_EXTENSIONS }
        Log.d(TAG, "readMelonDS: save files matched=${matchedFiles.map { it.absolutePath }}")
        val seen = mutableSetOf<String>()
        return matchedFiles
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                val name = cleanTitle(file.nameWithoutExtension
                    .replace(Regex("\\.(sav|dsv|mst|state|st\\d)$", RegexOption.IGNORE_CASE), "")
                    .trim())
                if (name.isNotEmpty() && seen.add(name)) {
                    Log.d(TAG, "readMelonDS: adding '$name' path=${file.absolutePath}")
                    RecentGame(name, file.absolutePath, packageName, file.lastModified())
                } else null
            }
            .take(depth)
    }

    // ── Dolphin (GameCube / Wii) ─────────────────────────────────────────────

    private fun readDolphin(packageName: String, depth: Int = 20): List<RecentGame> {
        val roots = storageRoots()
        val dolphinRoots = roots.mapNotNull { root ->
            File(root, "Android/data/$packageName/files").takeIf { it.isDirectory }
        }
        Log.d(TAG, "readDolphin: roots=$dolphinRoots")

        data class Entry(val time: Long, val id: String, val path: String)
        val entries = mutableListOf<Entry>()

        for (dolphinRoot in dolphinRoots) {
            File(dolphinRoot, "StateSaves").listFiles()
                ?.filter { it.isFile && it.extension.lowercase().let { e -> e.startsWith("s") && e.length <= 3 } }
                ?.forEach { entries += Entry(it.lastModified(), it.nameWithoutExtension.take(6).uppercase(), it.absolutePath) }

            File(dolphinRoot, "GC").listFiles()
                ?.filter { it.isFile && it.extension.lowercase() == "gci" }
                ?.forEach { entries += Entry(it.lastModified(), it.nameWithoutExtension.take(6).uppercase(), it.absolutePath) }

            File(dolphinRoot, "Wii/title/00010000").listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { dir ->
                    val code = hexToAscii4(dir.name) ?: return@forEach
                    entries += Entry(latestModified(dir), code, dir.absolutePath)
                }
        }

        Log.d(TAG, "readDolphin: found ${entries.size} raw entries")
        val seen = mutableSetOf<String>()
        return entries
            .sortedByDescending { it.time }
            .mapNotNull { (time, id, path) ->
                val title = if (id.length == 6) GameDatabase.lookupGcWii(id) ?: id
                            else GameDatabase.lookupGcWiiByCode(id) ?: id
                if (seen.add(id)) RecentGame(title, path, packageName, time) else null
            }
            .take(depth)
    }

    private fun hexToAscii4(hex: String): String? {
        if (hex.length != 8) return null
        return try {
            val bytes = ByteArray(4) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            val s = String(bytes, Charsets.US_ASCII)
            if (s.all { it.isLetterOrDigit() }) s else null
        } catch (_: Exception) { null }
    }

    // ── NetherSX2 / AetherSX2 (PS2) ─────────────────────────────────────────

    private val PS2_SERIAL = Regex("[A-Z]{2,4}-\\d{5}")

    private fun readNetherSX2(packageName: String, depth: Int = 20): List<RecentGame> {
        val roots = storageRoots()
        val memcardsDirs = roots.flatMap { root ->
            listOf("AetherSX2", "NetherSX2").map { app -> File(root, "$app/memcards") }
        }
        Log.d(TAG, "readNetherSX2: memcardsDirs=$memcardsDirs")
        data class Entry(val time: Long, val serial: String, val path: String)
        val entries = mutableListOf<Entry>()
        for (memcardsDir in memcardsDirs) {
            // Folder-based memory cards: memcards/<card>.ps2/<BASCUS-97130>/
            memcardsDir.listFiles()
                ?.filter { it.isDirectory && it.name.endsWith(".ps2", ignoreCase = true) }
                ?.forEach { card ->
                    card.listFiles()?.filter { it.isDirectory }?.forEach { saveDir ->
                        // Save dir name encodes the serial with a 2-char region prefix (e.g. BA+SCUS-97130)
                        val serial = PS2_SERIAL.find(saveDir.name)?.value?.uppercase() ?: return@forEach
                        entries += Entry(latestModified(saveDir), serial, saveDir.absolutePath)
                    }
                }
        }
        Log.d(TAG, "readNetherSX2: found ${entries.size} raw entries")
        val seen = mutableSetOf<String>()
        return entries
            .sortedByDescending { it.time }
            .mapNotNull { (time, serial, path) ->
                val title = cleanTitle(GameDatabase.lookupPs2(serial) ?: serial)
                if (seen.add(serial)) RecentGame(title, path, packageName, time) else null
            }
            .take(depth)
    }

    // ── ARMSX2 (PS2) ─────────────────────────────────────────────────────────

    private fun readARMSX2(packageName: String, depth: Int = 20): List<RecentGame> {
        val roots = storageRoots()
        val memcardsDirs = roots.map { root -> File(root, "ARMSX2/memcards") }
        Log.d(TAG, "readARMSX2: memcardsDirs=$memcardsDirs")
        data class Entry(val time: Long, val serial: String, val path: String)
        val entries = mutableListOf<Entry>()
        for (memcardsDir in memcardsDirs) {
            // Folder-based memory cards: memcards/<card>.ps2/<SERIAL>/
            memcardsDir.listFiles()
                ?.filter { it.isDirectory && it.name.endsWith(".ps2", ignoreCase = true) }
                ?.forEach { card ->
                    card.listFiles()?.filter { it.isDirectory }?.forEach { saveDir ->
                        val serial = PS2_SERIAL.find(saveDir.name)?.value?.uppercase() ?: return@forEach
                        entries += Entry(latestModified(saveDir), serial, saveDir.absolutePath)
                    }
                }
            // Flat .mcd files alongside folder cards
            memcardsDir.listFiles()
                ?.filter { it.isFile && it.extension.equals("mcd", ignoreCase = true) }
                ?.forEach { file ->
                    val serial = PS2_SERIAL.find(file.nameWithoutExtension)?.value?.uppercase()
                        ?: return@forEach
                    entries += Entry(file.lastModified(), serial, file.absolutePath)
                }
        }
        // Also check sstates for any additional games not yet in memcards
        val sstatesDirs = roots.map { root -> File(root, "ARMSX2/sstates") }
        for (sstatesDir in sstatesDirs) {
            sstatesDir.listFiles()
                ?.filter { it.isFile }
                ?.forEach { file ->
                    val serial = PS2_SERIAL.find(file.nameWithoutExtension)?.value?.uppercase()
                        ?: return@forEach
                    entries += Entry(file.lastModified(), serial, file.absolutePath)
                }
        }
        Log.d(TAG, "readARMSX2: found ${entries.size} raw entries")
        val seen = mutableSetOf<String>()
        return entries
            .sortedByDescending { it.time }
            .mapNotNull { (time, serial, path) ->
                val title = cleanTitle(GameDatabase.lookupPs2(serial) ?: serial)
                if (seen.add(serial)) RecentGame(title, path, packageName, time) else null
            }
            .take(depth)
    }

    // ── Azahar (3DS) ─────────────────────────────────────────────────────────

    private fun readAzahar(packageName: String, depth: Int = 20): List<RecentGame> {
        val roots = storageRoots()
        val azaharRoots = roots.mapNotNull { root ->
            File(root, "Azahar").takeIf { it.isDirectory }
        }
        Log.d(TAG, "readAzahar: roots=$azaharRoots")
        data class Entry(val time: Long, val id: String, val path: String)
        val entries = mutableListOf<Entry>()
        for (azahar in azaharRoots) {
            File(azahar, "states").listFiles()?.filter { it.isFile }?.forEach { file ->
                val id = file.nameWithoutExtension.substringBefore("-").lowercase()
                    .takeIf { it.matches(Regex("[0-9a-f]{16}")) } ?: return@forEach
                entries += Entry(file.lastModified(), id, file.absolutePath)
            }
            val n3ds = File(azahar, "sdmc/Nintendo 3DS")
            n3ds.listFiles()?.filter { it.isDirectory }?.forEach { lvl1 ->
                lvl1.listFiles()?.filter { it.isDirectory }?.forEach { lvl2 ->
                    File(lvl2, "title/00040000").listFiles()
                        ?.filter { it.isDirectory }
                        ?.forEach { titleDir ->
                            val id = "00040000${titleDir.name}".lowercase()
                                .takeIf { it.matches(Regex("[0-9a-f]{16}")) } ?: return@forEach
                            entries += Entry(latestModified(titleDir), id, titleDir.absolutePath)
                        }
                }
            }
        }
        Log.d(TAG, "readAzahar: found ${entries.size} raw entries")
        val seen = mutableSetOf<String>()
        return entries
            .sortedByDescending { it.time }
            .mapNotNull { (time, id, path) ->
                // 3ds.csv titles are formatted "English Name(Japanese Name)" — strip the
                // parenthetical via cleanTitle like every other DB-backed reader does.
                val title = GameDatabase.lookup3ds(id)?.let { cleanTitle(it) } ?: id
                Log.d(TAG, "readAzahar: id=$id title=$title")
                if (seen.add(id)) RecentGame(title, path, packageName, time) else null
            }
            .take(depth)
    }

    // ── Cemu (Wii U) ─────────────────────────────────────────────────────────

    private fun readCemu(packageName: String, depth: Int = 20): List<RecentGame> {
        val roots = storageRoots()
        val cemuRoots = roots.flatMap { root ->
            listOf("Cemu", "cemu").mapNotNull { File(root, it).takeIf { f -> f.isDirectory } }
        }
        val saveBases = cemuRoots.map { File(it, "mlc01/usr/save/00050000") }
        val seen = mutableSetOf<String>()
        return saveBases
            .flatMap { base -> base.listFiles()?.filter { it.isDirectory } ?: emptyList() }
            .map { dir -> Pair(latestModified(dir), dir) }
            .sortedByDescending { it.first }
            .mapNotNull { (time, dir) ->
                val id = "00050000${dir.name}".lowercase()
                    .takeIf { it.matches(Regex("[0-9a-f]{16}")) } ?: return@mapNotNull null
                // wiiu.csv titles are formatted "English Name(Japanese Name)" like 3ds.csv.
                val title = GameDatabase.lookupWiiU(id)?.let { cleanTitle(it) } ?: dir.name
                Log.d(TAG, "readCemu: id=$id title=$title")
                if (seen.add(id)) RecentGame(title, dir.absolutePath, packageName, time) else null
            }
            .take(depth)
    }

    // ── Eden (Switch) ─────────────────────────────────────────────────────────

    private fun readEden(packageName: String, depth: Int = 20): List<RecentGame> {
        val roots = storageRoots()
        data class Entry(val time: Long, val id: String, val path: String)
        val entries = mutableListOf<Entry>()
        for (root in roots) {
            val base = File(root, "Android/data/$packageName/files/nand/user/save/0000000000000000")
            Log.d(TAG, "readEden: checking $base exists=${base.isDirectory}")
            base.listFiles()?.filter { it.isDirectory }
                ?.flatMap { it.listFiles()?.filter { d -> d.isDirectory } ?: emptyList() }
                ?.forEach { dir ->
                    val id = dir.name.lowercase()
                        .takeIf { it.matches(Regex("[0-9a-f]{16}")) } ?: return@forEach
                    entries += Entry(latestModified(dir), id, dir.absolutePath)
                }
        }
        Log.d(TAG, "readEden: found ${entries.size} raw entries")
        val seen = mutableSetOf<String>()
        return entries
            .sortedByDescending { it.time }
            .mapNotNull { (time, id, path) ->
                // switch.csv has a handful of titles formatted "English Name(Japanese Name)".
                val title = GameDatabase.lookupSwitch(id)?.let { cleanTitle(it) } ?: id
                Log.d(TAG, "readEden: id=$id title=$title")
                if (seen.add(id)) RecentGame(title, path, packageName, time) else null
            }
            .take(depth)
    }

    // ── Vita3K ───────────────────────────────────────────────────────────────

    private fun readVita3K(packageName: String, depth: Int = 20): List<RecentGame> {
        val vitaIdRegex = Regex("^PC[A-Z]{2}\\d{5}$")
        val roots = storageRoots()

        // Candidate base dirs where Vita3K keeps its ux0 tree.
        // User can name the folder anything; we look for any dir whose name
        // contains "vita" (case-insensitive) under each storage root.
        val vita3kDirs = mutableListOf<File>()
        for (root in roots) {
            root.listFiles()
                ?.filter { it.isDirectory && it.name.contains("vita", ignoreCase = true) }
                ?.forEach { vita3kDirs += it }
        }

        data class Entry(val modified: Long, val titleId: String, val vita3kDir: File)
        val entries = mutableListOf<Entry>()
        for (vita3kDir in vita3kDirs) {
            val saveRoot = File(vita3kDir, "ux0/user/00/savedata")
            if (!saveRoot.isDirectory) continue
            Log.d(TAG, "readVita3K: scanning ${saveRoot.absolutePath}")
            saveRoot.listFiles()
                ?.filter { it.isDirectory && vitaIdRegex.matches(it.name) }
                ?.forEach { dir -> entries += Entry(latestModified(dir), dir.name, vita3kDir) }
        }
        Log.d(TAG, "readVita3K: found ${entries.size} raw entries")

        // All candidate ux0/app roots across every possible Vita3K base dir
        val appRoots: List<File> = vita3kDirs.flatMap { d ->
            listOf(File(d, "ux0/app"), File(d, "pref/ux0/app"), File(d, "app"))
        }.filter { it.isDirectory }

        val seen = mutableSetOf<String>()
        return entries
            .sortedByDescending { it.modified }
            .mapNotNull { (time, titleId, _) ->
                if (!seen.add(titleId)) return@mapNotNull null
                // Search all app roots for this title's param.sfo
                val sfo = appRoots
                    .map { File(it, "$titleId/sce_sys/param.sfo") }
                    .firstOrNull { it.exists() }
                val title = (if (sfo != null) readParamSfo(sfo) else null)
                    ?: cleanTitle(GameDatabase.lookupVita(titleId) ?: "").takeIf { it.isNotBlank() }
                    ?: titleId
                Log.d(TAG, "readVita3K: $titleId → \"$title\" sfo=${sfo?.absolutePath ?: "none"}")
                RecentGame(title, titleId, packageName, time)
            }
            .take(depth)
    }

    /**
     * Parse a PS Vita/PS4 param.sfo binary and return the TITLE field, or null if unavailable.
     * SFO layout: 4-byte magic (0x00PSF), 4-byte version, key-table offset, data-table offset,
     * entry count, then 16-byte index entries each pointing into the key and data tables.
     */
    private fun readParamSfo(file: File): String? {
        if (!file.exists()) return null
        return try {
            val b = file.readBytes()
            if (b.size < 20) return null
            if (b[0] != 0x00.toByte() || b[1] != 'P'.code.toByte() ||
                b[2] != 'S'.code.toByte() || b[3] != 'F'.code.toByte()) return null
            val keyBase = sfoU32(b, 8)
            val datBase = sfoU32(b, 12)
            val count   = sfoU32(b, 16)
            var fallback: String? = null  // TITLE_00 result if no plain TITLE found
            for (i in 0 until count) {
                val e       = 20 + i * 16
                if (e + 16 > b.size) break
                val keyOff  = sfoU16(b, e)
                val dataLen = sfoU32(b, e + 4)   // actual used length (e+8 is max/allocated)
                val dataOff = sfoU32(b, e + 12)
                val ks = keyBase + keyOff
                var ke = ks
                while (ke < b.size && b[ke] != 0.toByte()) ke++
                val key = String(b, ks, ke - ks, Charsets.US_ASCII)
                if (key == "TITLE" || key == "TITLE_00") {
                    if (dataLen <= 0) continue
                    val absEnd = datBase + dataOff + dataLen
                    if (absEnd > b.size) continue
                    // dataLen includes the NUL terminator; strip it explicitly.
                    val strLen = if (b[datBase + dataOff + dataLen - 1] == 0.toByte())
                        dataLen - 1 else dataLen
                    val result = String(b, datBase + dataOff, strLen, Charsets.UTF_8)
                        .trim().takeIf { it.isNotBlank() }
                    if (key == "TITLE") return result
                    if (fallback == null) fallback = result
                }
            }
            fallback
        } catch (_: Exception) { null }
    }

    private fun sfoU16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun sfoU32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
        ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    // ── DuckStation ──────────────────────────────────────────────────────────

    private fun readDuckStation(packageName: String, depth: Int = 20): List<RecentGame> {
        val slotSuffix = Regex("_\\d+$")
        val savExts    = setOf("sav", "mcd", "mcr", "mc")
        val dsRoots = storageRoots().mapNotNull { root ->
            File(root, "duckstation").takeIf { it.isDirectory }
        }
        Log.d(TAG, "readDuckStation: roots=$dsRoots")
        data class Entry(val modified: Long, val title: String, val path: String)
        val entries = mutableListOf<Entry>()
        for (root in dsRoots) {
            for (subdir in listOf("memcards")) {
                val dir = File(root, subdir)
                if (!dir.isDirectory) continue
                dir.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in savExts && !it.name.startsWith("shared_card") }
                    ?.forEach { file ->
                        val title = cleanTitle(slotSuffix.replace(file.nameWithoutExtension, "").trim())
                        if (title.isNotBlank()) entries += Entry(file.lastModified(), title, file.absolutePath)
                    }
            }
        }
        Log.d(TAG, "readDuckStation: found ${entries.size} raw entries")
        val seen = mutableSetOf<String>()
        return entries
            .sortedByDescending { it.modified }
            .mapNotNull { (time, title, path) ->
                if (seen.add(title)) RecentGame(title, path, packageName, time) else null
            }
            .take(depth)
    }

    // ── PS3 (APS3E / RPCSX) ──────────────────────────────────────────────────

    private fun readPs3(packageName: String, depth: Int = 20): List<RecentGame> {
        val titleIdRegex = Regex("[A-Z]{4}\\d{5}")
        val roots = storageRoots()
        val candidates = when {
            packageName.startsWith("aenu.aps3e") -> roots.flatMap { root ->
                listOf(
                    File(root, "Android/data/aenu.aps3e/files/aps3e/config/dev_hdd0/home/00000001/savedata"),
                    File(root, "Android/data/aenu.aps3e/files/dev_hdd0/home/00000001/savedata"),
                    File(root, "Android/data/aenu.aps3e/files/aps3e/dev_hdd0/home/00000001/savedata"),
                )
            }
            else -> roots.flatMap { root ->
                listOf(
                    File(root, "Android/data/net.rpcsx/files/config/dev_hdd0/home/00000001/savedata"),
                    File(root, "Android/data/net.rpcsx/files/dev_hdd0/home/00000001/savedata"),
                )
            }
        }
        Log.d(TAG, "readPs3: candidates=${candidates.map { "${it.absolutePath} exists=${it.isDirectory}" }}")
        val saveRoot = candidates.firstOrNull { it.isDirectory }
        if (saveRoot == null) {
            Log.d(TAG, "readPs3: no save directory found")
            return emptyList()
        }
        Log.d(TAG, "readPs3: scanning ${saveRoot.absolutePath}")
        val dirs = saveRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.also { Log.d(TAG, "readPs3: found ${it.size} dirs: ${it.map { d -> d.name }}") }
            ?: return emptyList()
        val seen = mutableSetOf<String>()
        return dirs
            .mapNotNull { dir ->
                val titleId = titleIdRegex.find(dir.name)?.value ?: return@mapNotNull null
                Triple(latestModified(dir), titleId, dir)
            }
            .sortedByDescending { it.first }
            .mapNotNull { (time, titleId, dir) ->
                if (!seen.add(titleId)) return@mapNotNull null
                val title = GameDatabase.lookupPs3(titleId) ?: titleId
                RecentGame(title, dir.absolutePath, packageName, time)
            }
            .take(depth)
    }

    // ── M64Plus FZ (N64) ─────────────────────────────────────────────────────

    // Dir names: "<Title> (U) <32-hex-char hash>" — strip the trailing hash to get the title.
    private val M64_HASH_SUFFIX = Regex("\\s+[0-9A-Fa-f]{32}$")

    private fun readM64PlusFZ(packageName: String, depth: Int = 20): List<RecentGame> {
        val roots = storageRoots()
        // M64Plus stores game data under M64Plus/GameData/<title + hash>/
        val gameDirs = roots.flatMap { root ->
            File(root, "M64Plus/GameData")
                .listFiles()
                ?.filter { it.isDirectory }
                ?: emptyList()
        }
        Log.d(TAG, "readM64PlusFZ: found ${gameDirs.size} game dirs")

        val seen = mutableSetOf<String>()
        return gameDirs
            .sortedByDescending { latestModified(it) }
            .mapNotNull { dir ->
                // Strip the trailing 32-char hash to get a search hint, then find the actual ROM.
                val hint = M64_HASH_SUFFIX.replace(dir.name, "").trim()
                val romPath = RomFinder.findRomByTitle(hint, "n64") ?: return@mapNotNull null
                // Use the ROM filename as the display title so casing and formatting match the file.
                val title = cleanTitle(File(romPath).nameWithoutExtension)
                if (title.isNotEmpty() && seen.add(title)) {
                    Log.d(TAG, "readM64PlusFZ: adding '$title' rom=$romPath")
                    RecentGame(title, romPath, packageName, latestModified(dir))
                } else null
            }
            .take(depth)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Strip region tags like (USA), version tags like (v1.00), and No-Intro flags like [!]. */
    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("\\s*\\([^)]*\\)"), "")
           .replace(Regex("\\s*\\[[^]]*]"), "")
           .trim()

    private fun latestModified(dir: File): Long =
        dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: dir.lastModified()

    private fun storageRoots(): List<File> {
        val roots = mutableListOf(File("/storage/emulated/0"))
        File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
            ?.forEach { roots += it }
        return roots
    }
}

