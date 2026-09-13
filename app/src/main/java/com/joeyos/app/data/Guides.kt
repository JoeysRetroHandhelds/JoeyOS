package com.joeyos.app.data

import android.os.Environment
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.joeyos.app.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/*
 * Game guides for the second screen, ported from Chameleon (GuideStore, GuideDownloader,
 * GuideSearch, GuideBrowserBlocklist). A guide is a walkthrough — the plain-text FAQ
 * GameFAQs has carried for decades, or a page saved from a guide site — read on the second screen
 * beside the game. JoeyOS never fetches one by itself: only when you choose to.
 */

private const val TAG = "Guides"

/** A game to find a guide for: the names it goes by, its console, and its ROM when known. */
data class GuideTarget(
    /** Best first: RetroAchievements' title, then the name JoeyOS launched it by. */
    val titles: List<String>,
    val consoleName: String?,
    /** RetroAchievements' console id, which says where in the GameFAQs archive to look. */
    val raConsoleId: Int?,
    val romPath: String?,
) {
    val title: String get() = titles.first()
    /** A stable key for per-game settings (the reading position). */
    val key: String get() = (consoleName.orEmpty() + "|" + Guides.archiveKey(title))
}

/**
 * A search (or page) to open in the guide browser, and its link. [appSearch] is set for YouTube:
 * searched in the YouTube app when it's installed, the browser otherwise.
 */
data class GuideSource(val site: String, val url: String, val appSearch: String? = null)

object Guides {

    /** Text wins over a saved page when both are there: it's the archive's format. */
    private val Extensions = listOf("txt", "html", "htm")

    val root: File get() = File(Environment.getExternalStorageDirectory(), "JoeyOS/guides")

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    private fun folderFor(t: GuideTarget) = File(root, safe(t.consoleName ?: "Other"))

    fun isHtml(file: File) = file.extension.lowercase() in setOf("html", "htm")

    /**
     * The guide for [t], or null. Looks next to the ROM first (`Game Name.txt`, where Guide Watch
     * on OnionOS and its web tool put them, so a Miyoo card of guides just works), then in
     * JoeyOS's own guides folder under each name the game goes by.
     */
    fun find(t: GuideTarget): File? {
        // Every name the game went by when its guide was saved (see [remember]).
        val aliases = readAliases()
        for (k in keysOf(t)) aliases.optString(k).takeIf { it.isNotBlank() }?.let(::File)
            ?.takeIf { it.isFile && it.length() > 0 }?.let { return it }
        t.romPath?.let(::File)?.takeIf { it.isFile }?.let { rom ->
            val name = rom.name.substringBeforeLast('.')
            Extensions.flatMap { listOf("$name.$it", "$name.${it.uppercase()}") }
                .map { File(rom.parentFile, it) }
                .firstOrNull { it.isFile && it.length() > 0 }?.let { return it }
        }
        val folder = folderFor(t)
        for (title in t.titles) for (ext in Extensions) {
            File(folder, safe(title) + "." + ext).takeIf { it.isFile && it.length() > 0 }?.let { return it }
        }
        // Any guide saved under any name the game goes by, in any console's folder. Found on
        // device: a game is named by its ROM ("Disney's Aladdin (USA)") until RetroAchievements
        // answers with its own title and console ("Aladdin", SNES); a guide saved under one wasn't
        // found under the other, so it was offered — and downloaded — again every launch.
        val keys = keysOf(t)
        return root.walkTopDown().maxDepth(2)
            .filter { it.isFile && it.length() > 0 && it.extension.lowercase() in Extensions }
            .firstOrNull { f ->
                val k = archiveKey(f.nameWithoutExtension)
                k in keys || keys.any { key -> key.split(' ').let { w -> w.size >= 2 && k.split(' ').containsAll(w) } }
            }
    }

    private fun keysOf(t: GuideTarget): Set<String> =
        (t.titles + listOfNotNull(t.romPath?.let { File(it).name.substringBeforeLast('.') }))
            .map(::archiveKey).filter { it.isNotBlank() }.toSet()

    private val aliasFile get() = File(root, ".names.json")
    private fun readAliases(): JSONObject = runCatching { JSONObject(aliasFile.readText()) }.getOrElse { JSONObject() }

    /**
     * Notes every name [t] goes by against its guide [file], so the next launch finds it whichever
     * name it has at that moment (the ROM's before RetroAchievements answers, RA's after).
     */
    fun remember(t: GuideTarget, file: File) {
        runCatching {
            val a = readAliases()
            keysOf(t).forEach { a.put(it, file.absolutePath) }
            root.mkdirs(); aliasFile.writeText(a.toString())
        }
    }

    /** Where a guide for [t] is saved: JoeyOS's guides folder, never the ROM folders. */
    fun destination(t: GuideTarget, extension: String): File =
        File(folderFor(t).apply { mkdirs() }, safe(t.title) + "." + extension)

    /** Saves a page from the guide browser as [t]'s guide. */
    fun saveHtml(t: GuideTarget, html: String): File? = runCatching {
        destination(t, "html").also { it.writeText(html); remember(t, it) }
    }.onSuccess { AppLog.i(TAG, "Saved a guide page for '${t.title}': ${it.absolutePath}") }
        .onFailure { AppLog.w(TAG, "Couldn't save a guide page for '${t.title}'", it) }.getOrNull()

    // ── The GameFAQs archive ─────────────────────────────────────────────────────────────

    private const val ArchiveItem = "Gamespot_Gamefaqs_TXTs"
    private const val IndexSite = "https://guides.retromodlab.com/app/guides-index"

    /** RetroAchievements console id → the Guide Watch index's platform names, best first. */
    private val ArchivePlatforms: Map<Int, List<String>> = mapOf(
        7 to listOf("nes"), 81 to listOf("famicomds", "nes"), 3 to listOf("snes"), 2 to listOf("n64"),
        4 to listOf("gameboy"), 6 to listOf("gbc", "gameboy"), 5 to listOf("gba"), 18 to listOf("ds"),
        28 to listOf("virtualboy"), 1 to listOf("genesis"), 9 to listOf("segacd"), 10 to listOf("sega32x"),
        11 to listOf("sms"), 15 to listOf("gamegear", "sms"), 39 to listOf("saturn"), 40 to listOf("dreamcast"),
        33 to listOf("sg1000"), 12 to listOf("ps"), 21 to listOf("ps2"), 41 to listOf("psp"),
        8 to listOf("tg16"), 76 to listOf("tg16"), 14 to listOf("ngp", "ngpocket"), 13 to listOf("lynx"),
        17 to listOf("jaguar"), 25 to listOf("atari2600"), 50 to listOf("atari5200"), 51 to listOf("atari7800"),
        53 to listOf("wonderswan", "wsc"), 29 to listOf("msx"), 30 to listOf("c64"), 35 to listOf("amiga"),
        43 to listOf("3do"), 44 to listOf("colecovision"), 45 to listOf("intellivision"),
        16 to listOf("gamecube"), 19 to listOf("wii"),
    )

    private val loadedIndexes = mutableMapOf<String, JSONObject>()

    /** Whether the archive covers this game's console at all (so the button is worth offering). */
    fun archiveCovers(t: GuideTarget) = t.raConsoleId?.let { it in ArchivePlatforms } == true

    /**
     * Downloads the game's GameFAQs guide from the archive: the Guide Watch index (per console,
     * keyed by a normalised name) says which archived text file it is, and archive.org extracts
     * just that file from its 7z. Where the index has several, the longest is taken (a
     * walkthrough, not a cheat list). Only ever run when the user asks. Null when not found.
     */
    suspend fun downloadFromArchive(t: GuideTarget, cacheDir: File): File? = withContext(Dispatchers.IO) {
        val platforms = t.raConsoleId?.let { ArchivePlatforms[it] }.orEmpty()
        if (platforms.isEmpty()) return@withContext null
        val names = buildList {
            t.titles.forEach { add(it) }
            t.romPath?.let { add(File(it).name.substringBeforeLast('.')) }
            t.titles.forEach { n -> if (n.contains(" - ")) { add(n.substringAfter(" - ")); add(n.substringBefore(" - ")) } }
            t.titles.forEach { n -> if (n.contains(": ")) { add(n.substringAfter(": ")); add(n.substringBefore(": ")) } }
        }.map(::archiveKey).filter { it.isNotBlank() }.distinct()

        for (platform in platforms) {
            val index = archiveIndex(platform, cacheDir) ?: continue
            // Exact keys first; then, for each name, the closest key that contains all its words
            // with at most two more. RetroAchievements says "Aladdin" where the archive files it
            // as "Disney's Aladdin" (found testing), and an exact match alone missed it.
            val keys = index.keys().asSequence().toList()
            val tryKeys = names + names.mapNotNull { name ->
                val words = name.split(' ').toSet()
                keys.filter { k -> val kw = k.split(' '); kw.containsAll(words) && kw.size - words.size in 1..2 }
                    .minByOrNull { it.length }
            }
            for (name in tryKeys.distinct()) {
                val entries = index.optJSONArray(name) ?: continue
                // An entry is [title, archive, path inside, size KB]; several are nested when a game has more.
                val candidates = if (entries.optJSONArray(0) != null)
                    (0 until entries.length()).mapNotNull { entries.optJSONArray(it) } else listOf(entries)
                val best = candidates.maxByOrNull { it.optDouble(3, 0.0) } ?: continue
                val archive = best.optString(1).takeIf { it.isNotBlank() } ?: continue
                val inside = best.optString(2).takeIf { it.isNotBlank() } ?: continue
                val target = destination(t, "txt")
                if (extractFromArchive(archive, inside, target)) {
                    remember(t, target)
                    AppLog.i(TAG, "Downloaded the GameFAQs guide for '${t.title}' ($platform/$archive)")
                    return@withContext target
                }
            }
        }
        AppLog.i(TAG, "No GameFAQs archive guide for '${t.title}' (tried ${names.take(3)})")
        null
    }

    private fun archiveIndex(platform: String, cacheDir: File): JSONObject? {
        synchronized(loadedIndexes) { loadedIndexes[platform] }?.let { return it }
        val file = File(File(cacheDir, "guides-index"), "$platform.json").apply { parentFile?.mkdirs() }
        val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < 30L * 24 * 3_600_000
        val text = if (fresh) runCatching { file.readText() }.getOrNull() else {
            httpText("$IndexSite/$platform.json")?.also { runCatching { file.writeText(it) } }
                ?: runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
        }
        return text?.let { runCatching { JSONObject(it) }.getOrNull() }?.also { synchronized(loadedIndexes) { loadedIndexes[platform] = it } }
    }

    /** archive.org extracts one file from inside a 7z on request; an HTML answer is an error page. */
    private fun extractFromArchive(archive: String, inside: String, target: File): Boolean {
        val number = archive.removePrefix("gen").removeSuffix(".7z").toIntOrNull() ?: return false
        val member = "gamefaqs.gamespot.com.txt.faqs.$number.gen.7z"
        val url = "https://archive.org/download/$ArchiveItem/$ArchiveItem%2F$member/" +
            URLEncoder.encode(inside, "UTF-8").replace("+", "%20")
        return runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 120_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "JoeyOS")
            }
            c.inputStream.use { s ->
                val body = s.readBytes()
                if (body.size < 200 || body.first().toInt().toChar() == '<') return@runCatching false
                target.parentFile?.mkdirs(); target.writeBytes(body); true
            }
        }.getOrElse { AppLog.w(TAG, "Archive download failed: $archive/$inside", it); false }
    }

    /**
     * A name reduced to the archive index's key: lowercase, tags dropped, apostrophes deleted
     * (the index keys "Diddy's" as "diddys"), other punctuation spaced, leading articles dropped,
     * roman numerals as figures. The index's rules, so a key made any other way misses.
     */
    fun archiveKey(name: String): String {
        var text = name.lowercase()
        while (true) {
            val next = text.replace(Regex("\\([^()]*\\)"), " ").replace(Regex("\\[[^\\[\\]]*]"), " ")
            if (next == text) break
            text = next
        }
        text = text.replace("'", "").replace("’", "")
        return text.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotBlank() && it !in setOf("the", "a", "an", "and") }
            .map { RomanNumerals[it] ?: it }.joinToString(" ")
    }

    private val RomanNumerals = listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x",
        "xi", "xii", "xiii", "xiv", "xv", "xvi").mapIndexed { i, n -> n to (i + 1).toString() }.toMap()

    // ── Search links ─────────────────────────────────────────────────────────────────────

    /** Searches that work for every game: GameFAQs' own, guide sites via a web search, YouTube. */
    fun searchLinks(title: String): List<GuideSource> {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        // DuckDuckGo, not Google: Google kept putting captchas in front of the in-app browser
        // (it distrusts an embedded browser with no history), found on device.
        fun web(q: String) = "https://duckduckgo.com/?q=" + enc(q)
        return listOf(
            GuideSource("Search GameFAQs", "https://gamefaqs.gamespot.com/search?game=" + enc(title)),
            GuideSource("Search IGN", web("$title walkthrough site:ign.com")),
            GuideSource("Search Neoseeker", web("$title walkthrough site:neoseeker.com")),
            GuideSource("Search StrategyWiki", web("$title walkthrough site:strategywiki.org")),
            GuideSource("Search YouTube", "https://www.youtube.com/results?search_query=" + enc("$title walkthrough"),
                appSearch = "$title walkthrough"),
            GuideSource("Search the web", web("$title walkthrough")),
        )
    }

    /** A one-off search for a single thing (an achievement you're stuck on). */
    fun searchFor(query: String) = GuideSource(query, "https://duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8"))

    private fun httpText(url: String): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000; readTimeout = 30_000; setRequestProperty("User-Agent", "JoeyOS")
            if (responseCode !in 200..299) null else inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }.getOrNull()
}

/**
 * Ad and tracker blocking for the guide browser: the plain domain rules (`||host^`) from EasyList
 * and EasyPrivacy, matched on a request's host and its parent domains. Not uBlock's full engine
 * (no cosmetic hiding), but a blocked request stops the ad loading at all. The lists are fetched
 * daily with ETags and the host set cached; offline, nothing is blocked rather than held up.
 */
object GuideBlocklist {
    private val Lists = listOf("https://easylist.to/easylist/easylist.txt", "https://easylist.to/easylist/easyprivacy.txt")
    @Volatile private var hosts: Set<String>? = null
    private fun blank() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val set = hosts ?: return null
        var name = request.url?.host?.lowercase() ?: return null
        while (true) {
            if (name in set) return blank()
            val dot = name.indexOf('.')
            if (dot < 0) return null
            name = name.substring(dot + 1)
        }
    }

    suspend fun ensureLoaded(cacheDir: File) {
        if (hosts != null) return
        withContext(Dispatchers.IO) {
            val combined = File(cacheDir, "adblock-hosts.txt")
            if (combined.isFile && System.currentTimeMillis() - combined.lastModified() < 24 * 3_600_000L) {
                hosts = runCatching { combined.readLines().filter { it.isNotBlank() }.toHashSet() }.getOrNull()
                if (hosts != null) return@withContext
            }
            val rule = Regex("""^\|\|([a-z0-9.-]+)\^""")
            val fetched = HashSet<String>()
            Lists.forEachIndexed { i, url ->
                ConditionalFetch.text(url, File(cacheDir, "adblock-list-$i.txt"), File(cacheDir, "adblock-list-$i.etag"))
                    ?.lineSequence()?.forEach { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("!") || line.startsWith("@@") || "##" in line || "#@#" in line) return@forEach
                        rule.find(line)?.groupValues?.get(1)?.takeIf { '.' in it }?.let(fetched::add)
                    }
            }
            if (fetched.isNotEmpty()) {
                hosts = fetched
                runCatching { combined.writeText(fetched.joinToString("\n")) }
                AppLog.i(TAG, "Ad blocking: ${fetched.size} hosts")
            } else if (combined.isFile) {
                hosts = runCatching { combined.readLines().filter { it.isNotBlank() }.toHashSet() }.getOrNull()
            }
        }
    }
}
