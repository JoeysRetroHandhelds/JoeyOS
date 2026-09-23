package com.joeyos.app.data

import android.os.Environment
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.joeyos.app.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
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
    private val Extensions = listOf("txt", "mht", "html", "htm")

    val root: File get() = File(Environment.getExternalStorageDirectory(), "JoeyOS/guides")

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    private fun folderFor(t: GuideTarget) = File(root, safe(t.consoleName ?: "Other"))

    /** A page saved from a site (a complete web archive, or plain HTML from before v1.0.20). */
    fun isHtml(file: File) = file.extension.lowercase() in setOf("mht", "html", "htm")

    /**
     * Every guide saved for [t] — the GameFAQs one, pages saved from sites, one next to the ROM —
     * so "Change guide" can open one again rather than downloading it again (found on device).
     */
    fun savedFor(t: GuideTarget): List<File> {
        val keys = keysOf(t)
        val aliased = readAliases().let { a -> keys.mapNotNull { a.optString(it).takeIf { p -> p.isNotBlank() }?.let(::File) } }
        val beside = t.romPath?.let(::File)?.takeIf { it.isFile }?.let { rom ->
            val name = rom.name.substringBeforeLast('.')
            Extensions.map { File(rom.parentFile, "$name.$it") }
        }.orEmpty()
        val inFolder = folderFor(t).listFiles().orEmpty().filter { f ->
            val k = archiveKey(f.nameWithoutExtension.substringBefore(" - "))
            k in keys || keys.any { key -> k.split(' ').containsAll(key.split(' ')) }
        }
        return (aliased + beside + inFolder).filter { it.isFile && it.length() > 0 && it.extension.lowercase() in Extensions }
            .distinctBy { it.absolutePath }
    }

    /** Where a page saved from a site goes: named for the game and the page, so pages don't overwrite each other. */
    fun pageDestination(t: GuideTarget, pageTitle: String): File {
        val page = safe(pageTitle).take(60).ifBlank { "Saved page" }
        return File(folderFor(t).apply { mkdirs() }, safe(t.title) + " - " + page + ".mht")
    }

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
            val response = Http.get(url, userAgent = "JoeyOS", connectMs = 20_000, readMs = 120_000)
            // A failed answer is logged with the others below.
            val body = response.bytes ?: throw java.io.IOException("HTTP ${response.code}")
            if (body.size < 200 || body.first().toInt().toChar() == '<') return@runCatching false
            target.parentFile?.mkdirs(); target.writeBytes(body); true
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

    // ── Live GameFAQs (the current site) ───────────────────────────────────────────────────

    private const val GameFaqs = "https://gamefaqs.gamespot.com"
    // A real desktop browser, not "JoeyOS": GameFAQs serves an anti-bot stripped page to anything
    // else. This is a native app, so there's no CORS to work around (a web tool needs a proxy only
    // because the browser blocks the cross-origin fetch); we can talk to the site directly.
    private const val LiveUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /**
     * RetroAchievements console id → the platform slug GameFAQs uses in its URLs
     * (gamefaqs.gamespot.com/<slug>/<id>-<name>). A partial map, so that when a console isn't here
     * the search results aren't filtered by platform — the title check alone picks the game.
     * PS3, Vita, Switch and Wii U have no RetroAchievements console id, so they can't be mapped
     * here; the title check keeps those from picking an unrelated game.
     */
    private val LivePlatforms: Map<Int, String> = mapOf(
        7 to "nes", 3 to "snes", 5 to "gba", 6 to "gbc", 4 to "gameboy", 2 to "n64",
        16 to "gamecube", 19 to "wii", 1 to "genesis", 39 to "saturn", 40 to "dreamcast",
        15 to "gamegear", 11 to "sms", 12 to "ps", 21 to "ps2", 41 to "psp", 18 to "ds",
        62 to "3ds", 9 to "segacd", 8 to "tg16", 76 to "tg16", 14 to "ngp", 13 to "lynx",
        17 to "jaguar", 25 to "atari2600", 53 to "wonderswan", 29 to "msx", 10 to "sega32x",
        28 to "virtualboy",
    )

    /** Where a guide fetched live is saved: its own file, so it sits beside the archive's copy rather than replacing it. */
    fun liveDestination(t: GuideTarget): File =
        File(folderFor(t).apply { mkdirs() }, safe(t.title) + LiveSuffix + ".txt")

    /** The name ending that marks a guide fetched live (see [liveDestination]). */
    const val LiveSuffix = " - GameFAQs (latest)"

    /** Whether [file] is a guide fetched live from GameFAQs rather than the archive's. */
    fun isLive(file: File) = file.nameWithoutExtension.endsWith(LiveSuffix)

    /**
     * Downloads the game's guide straight from the current gamefaqs.gamespot.com, rather than the
     * archive's snapshot: searches the site, opens the game's FAQs list, reads the plain text out of
     * the first real full FAQ, and saves it. Only ever run when the user asks. Best-effort at every
     * step (each network call is guarded); returns null and logs when a step yields nothing, and
     * never throws.
     */
    suspend fun downloadLiveGameFaqs(t: GuideTarget, cacheDir: File): File? = withContext(Dispatchers.IO) {
        val title = t.titles.firstOrNull()?.takeIf { it.isNotBlank() } ?: run {
            AppLog.w(TAG, "Live GameFAQs: no title to search for"); return@withContext null
        }

        // 1. Search. The result links look like /<platform>/<id>-<slug>. Only the results list is
        //    read — the page's header, nav and "trending" links are other games entirely — and a
        //    result counts only when its slug carries the title's words, so a search that doesn't
        //    find the game gives nothing rather than a guide for something else.
        val searchUrl = "$GameFaqs/search?game=" + URLEncoder.encode(title, "UTF-8")
        val searchHtml = liveGet(searchUrl) ?: run {
            AppLog.w(TAG, "Live GameFAQs: search request failed for '$title'"); return@withContext null
        }
        val resultRe = Regex("""/([a-z0-9]+)/(\d+)-([a-z0-9-]+)""")
        val results = LinkedHashSet<String>()   // de-duped, in the order the page lists them
        for (m in resultRe.findAll(searchResultsRegion(searchHtml))) {
            if (slugMatches(title, m.groupValues[3])) results.add("/${m.groupValues[1]}/${m.groupValues[2]}-${m.groupValues[3]}")
        }
        if (results.isEmpty()) {
            AppLog.i(TAG, "Live GameFAQs: no matching GameFAQs game for '$title'"); return@withContext null
        }
        val wantPlatform = t.raConsoleId?.let { LivePlatforms[it] }
        val gamePath = results.firstOrNull { wantPlatform != null && it.startsWith("/$wantPlatform/") }
            ?: results.first()
        AppLog.i(TAG, "Live GameFAQs: '$title' → $gamePath (${results.size} matching results, want ${wantPlatform ?: "any"})")
        currentCoroutineContext().ensureActive()

        // 2. FAQs list. Collect the FAQ links (/<gamePath>/faqs/<id>). Push anything labelled a full
        //    FAQ/Walkthrough ahead of Cheats/Trophy/Achievement pages; otherwise keep the page's order.
        val faqsHtml = liveGet("$GameFaqs$gamePath/faqs") ?: run {
            AppLog.w(TAG, "Live GameFAQs: FAQs list request failed ($gamePath)"); return@withContext null
        }
        val faqLinkRe = Regex(Regex.escape(gamePath) + """/faqs/([A-Za-z0-9]+)"[^>]*>([^<]*)""")
        data class Candidate(val path: String, val label: String)
        val candidates = LinkedHashMap<String, Candidate>()   // path → label, de-duped
        for (m in faqLinkRe.findAll(faqsHtml)) {
            val path = "$gamePath/faqs/${m.groupValues[1]}"
            val label = m.groupValues[2].trim()
            if (path !in candidates) candidates[path] = Candidate(path, label)
        }
        if (candidates.isEmpty()) {
            AppLog.i(TAG, "Live GameFAQs: no FAQ links on the list page ($gamePath)"); return@withContext null
        }
        // A full walkthrough/FAQ is what we want; a cheats or trophy list isn't a guide to read.
        val ordered = candidates.values.sortedByDescending { c ->
            val l = c.label.lowercase()
            when {
                Regex("walkthrough|faq/walkthrough|faq/move|game script|guide|faq").containsMatchIn(l) -> 2
                Regex("cheat|trophy|achievement|save|map|code").containsMatchIn(l) -> 0
                else -> 1
            }
        }

        // 3. FAQ page. Try each candidate until one yields a long enough plaintext to count as a
        //    real guide; concatenate a bounded number of pages when the FAQ is paged.
        for (c in ordered) {
            currentCoroutineContext().ensureActive()
            val text = liveFaqText(c.path)
            // Checked again here: leaving the screen mid-fetch must not leave a half guide saved.
            currentCoroutineContext().ensureActive()
            if (text != null && text.length >= 500) {
                val target = liveDestination(t)
                runCatching { target.parentFile?.mkdirs(); target.writeText(text) }
                    .onFailure { AppLog.w(TAG, "Live GameFAQs: couldn't write ${target.name}", it); return@withContext null }
                remember(t, target)
                AppLog.i(TAG, "Downloaded the latest GameFAQs guide for '$title' ($gamePath)")
                return@withContext target
            }
        }
        AppLog.i(TAG, "Live GameFAQs: no candidate FAQ yielded a full guide ($gamePath)")
        null
    }

    /** One live GameFAQs GET, browser-disguised and guarded; null on any failure or a non-2xx answer. */
    private fun liveGet(url: String): String? = runCatching {
        Http.get(url, userAgent = LiveUa, connectMs = 20_000, readMs = 60_000).takeIf { it.ok }?.text
    }.getOrElse { AppLog.w(TAG, "Live GameFAQs: request threw", it); null }

    /**
     * Reads a FAQ's plaintext from [faqPath]. Text FAQs render inside a <pre> block; when there's
     * none, the main content div is stripped to text. A FAQ comes in more than one piece two ways:
     * paged (?page=N), or — the HTML guides — split into sections at <faqPath>/<section-slug>.
     * Both are fetched and joined, up to [MaxLivePages] pages in all so a huge guide can't run on.
     */
    private suspend fun liveFaqText(faqPath: String): String? {
        val first = liveGet("$GameFaqs$faqPath") ?: return null
        val builder = StringBuilder()
        extractFaqText(first)?.let { builder.append(it) }
        var fetched = 1

        // Paged: the page selector links carry ?page=N (0-based on GameFAQs).
        val pageRe = Regex(Regex.escape(faqPath) + """\?page=(\d+)""")
        var maxPage = 0
        for (m in pageRe.findAll(first)) maxPage = maxOf(maxPage, m.groupValues[1].toIntOrNull() ?: 0)
        for (p in 1..maxPage) {
            if (fetched >= MaxLivePages) break
            currentCoroutineContext().ensureActive()
            val html = liveGet("$GameFaqs$faqPath?page=$p") ?: break
            fetched++
            extractFaqText(html)?.let { if (it.isNotBlank()) { builder.append("\n\n"); builder.append(it) } }
        }

        // Sectioned: the guide's contents link each section under the FAQ's own path. Page order,
        // de-duped, and not the page we're on; each gets a heading so the joined text reads in parts.
        val sectionRe = Regex("""href\s*=\s*"(?:https?://gamefaqs\.gamespot\.com)?""" + Regex.escape(faqPath) +
            """/([A-Za-z0-9_-]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val sections = LinkedHashMap<String, String>()   // slug → its link text
        for (m in sectionRe.findAll(first)) {
            val slug = m.groupValues[1]
            if (slug !in sections) sections[slug] = htmlToText(m.groupValues[2]).trim()
        }
        for ((slug, label) in sections) {
            if (fetched >= MaxLivePages) {
                AppLog.i(TAG, "Live GameFAQs: stopped at $MaxLivePages pages ($faqPath)"); break
            }
            currentCoroutineContext().ensureActive()
            val html = liveGet("$GameFaqs$faqPath/$slug") ?: continue
            fetched++
            val text = extractFaqText(html) ?: continue
            val heading = label.ifBlank { slug.replace('-', ' ') }
            builder.append("\n\n\n== ").append(heading).append(" ==\n\n").append(text)
        }
        return builder.toString().takeIf { it.isNotBlank() }
    }

    /** At most this many pages (and sections) of one live FAQ are fetched. */
    private const val MaxLivePages = 30

    /**
     * Just the results list of a GameFAQs search page, so the header, nav and "trending" game
     * links aren't read as results. From the first results marker to the footer; when no marker is
     * found, the whole page (the title check in [slugMatches] still keeps out other games).
     */
    private fun searchResultsRegion(html: String): String {
        val start = Regex("""class\s*=\s*"[^"]*(?:search_result|sr_)[^"]*"""", RegexOption.IGNORE_CASE)
            .find(html)?.range?.first ?: return html
        val end = Regex("""<footer|id\s*=\s*"footer"|class\s*=\s*"[^"]*footer""", RegexOption.IGNORE_CASE)
            .find(html, start)?.range?.first ?: html.length
        return html.substring(start, end)
    }

    /**
     * Whether a GameFAQs URL slug ("super-mario-world") is the game titled [title]: the title's
     * first significant word must be in it, and at least 60% of its significant words. Both are
     * reduced the way [archiveKey] reduces names, so "Super Mario Bros. 3" meets "super-mario-bros-3".
     */
    private fun slugMatches(title: String, slug: String): Boolean {
        val want = archiveKey(title).split(' ').filter { it.isNotBlank() }
        if (want.isEmpty()) return false
        val have = archiveKey(slug.replace('-', ' ')).split(' ').toSet()
        if (want.first() !in have) return false
        return want.count { it in have } * 10 >= want.size * 6
    }

    /**
     * The readable text of one FAQ page: the largest <pre> block (text FAQs), else the main content
     * div stripped to text (HTML guide pages). Null when neither yields anything.
     */
    private fun extractFaqText(html: String): String? {
        // Largest <pre>…</pre> wins: the FAQ itself, not a small snippet elsewhere on the page.
        val preRe = Regex("""<pre[^>]*>([\s\S]*?)</pre>""", RegexOption.IGNORE_CASE)
        var best: String? = null
        for (m in preRe.findAll(html)) {
            val body = unescapeHtml(m.groupValues[1])
            if (best == null || body.length > best!!.length) best = body
        }
        if (best != null && best!!.trim().length >= 200) return best!!.trim()

        // No usable <pre>: fall back to the main content region (an HTML guide page), the
        // faqwrap/ffaq/faqtext block when it's there, else the whole document.
        val text = htmlToText(contentRegion(html) ?: html)
        return text.takeIf { it.trim().length >= 200 }?.trim()
    }

    /**
     * The faqwrap/ffaq/faqtext container of an HTML guide page, whole. The guide nests divs
     * inside it, so its end is found by counting opening and closing tags of its kind rather than
     * stopping at the first close (which cut guides off after their first block). When the count
     * never balances (broken markup), it runs to the page footer, or the end of the page.
     */
    private fun contentRegion(html: String): String? {
        val open = Regex("""<(div|article|section)\b[^>]*(?:id|class)\s*=\s*"[^"]*(?:faqwrap|ffaq|faqtext)[^"]*"[^>]*>""",
            RegexOption.IGNORE_CASE).find(html) ?: return null
        val tag = open.groupValues[1].lowercase()
        val start = open.range.last + 1
        val tagRe = Regex("""<(/?)$tag\b[^>]*>""", RegexOption.IGNORE_CASE)
        var depth = 1
        for (m in tagRe.findAll(html, start)) {
            if (m.value.endsWith("/>")) continue   // self-closed, no depth
            depth += if (m.groupValues[1].isEmpty()) 1 else -1
            if (depth == 0) return html.substring(start, m.range.first)
        }
        val end = Regex("""<footer|id\s*=\s*"footer"""", RegexOption.IGNORE_CASE).find(html, start)?.range?.first
            ?: html.length
        return html.substring(start, end)
    }

    /** Strips a block of HTML to plain text: scripts/styles gone, <br> and block tags to newlines. */
    private fun htmlToText(html: String): String {
        var s = html
        s = Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE).replace(s, " ")
        s = Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE).replace(s, " ")
        s = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("""</(?:p|div|li|tr|h[1-6]|section|article|blockquote)>""", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("""<[^>]+>""").replace(s, "")   // every remaining tag
        s = unescapeHtml(s)
        // Collapse the runs of blank lines the tag stripping leaves behind.
        return Regex("""[ \t]+\n""").replace(s, "\n").let { Regex("""\n{3,}""").replace(it, "\n\n") }
    }

    /** Unescapes the HTML entities that turn up in FAQ text: the named few, and numeric &#NNN;. */
    private fun unescapeHtml(text: String): String {
        var s = text
        // Through code points, not toChar(): an emoji or other character past U+FFFF needs two
        // chars, and toChar() quietly mangled it. A number that isn't a character is left as written.
        fun char(digits: String, radix: Int, whole: String): String =
            digits.toIntOrNull(radix)?.takeIf { Character.isValidCodePoint(it) }
                ?.let { String(Character.toChars(it)) } ?: whole
        s = Regex("""&#(\d+);""").replace(s) { m -> char(m.groupValues[1], 10, m.value) }
        s = Regex("""&#[xX]([0-9a-fA-F]+);""").replace(s) { m -> char(m.groupValues[1], 16, m.value) }
        return s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }

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
            GuideSource("Search Web", web("$title walkthrough")),
        )
    }

    /** A one-off search for a single thing (an achievement you're stuck on). */
    fun searchFor(query: String) = GuideSource(query, "https://duckduckgo.com/?q=" + URLEncoder.encode(query, "UTF-8"))

    private fun httpText(url: String): String? = runCatching {
        Http.get(url, userAgent = "JoeyOS", connectMs = 15_000, readMs = 30_000).takeIf { it.ok }?.text
    }.getOrNull()
}

/**
 * Ad and tracker blocking for the guide browser: the plain domain rules (`||host^`) from EasyList
 * and EasyPrivacy, matched on a request's host and its parent domains. Not uBlock's full engine
 * (no cosmetic hiding), but a blocked request stops the ad loading at all. The lists are fetched
 * daily with ETags and the host set cached; offline, nothing is blocked rather than held up.
 *
 * The host list (tens of thousands of names) is only held while a guide browser is open: each
 * browser [acquire]s it and [release]s it when it closes, and the last one out drops it. Opening
 * one again reads it back from the day's cached file, which is quick and works offline.
 */
object GuideBlocklist {
    private val Lists = listOf("https://easylist.to/easylist/easylist.txt", "https://easylist.to/easylist/easyprivacy.txt")
    // Sorted, for a binary search: far smaller in memory than a HashSet of the same names.
    // Read from WebView's network threads, hence @Volatile.
    @Volatile private var hosts: Array<String>? = null
    private val users = java.util.concurrent.atomic.AtomicInteger(0)
    private fun blank() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** A guide browser opened: keep the list while it's open. Pair with [release]. */
    fun acquire() { users.incrementAndGet() }

    /** A guide browser closed: once none are open, let the list go. */
    fun release() {
        if (users.decrementAndGet() <= 0) { users.set(0); hosts = null }
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val set = hosts ?: return null
        var name = request.url?.host?.lowercase() ?: return null
        while (true) {
            if (java.util.Arrays.binarySearch(set, name) >= 0) return blank()
            val dot = name.indexOf('.')
            if (dot < 0) return null
            name = name.substring(dot + 1)
        }
    }

    private fun sortedHosts(names: Collection<String>): Array<String> =
        names.toTypedArray().also { java.util.Arrays.sort(it) }

    private fun readCached(combined: File): Array<String>? =
        runCatching { sortedHosts(combined.readLines().filter { it.isNotBlank() }.toHashSet()) }.getOrNull()

    /** Publishes a loaded list, unless every browser closed while it was loading. */
    private fun publish(list: Array<String>?) {
        if (list != null && users.get() > 0) hosts = list
    }

    suspend fun ensureLoaded(cacheDir: File) {
        if (hosts != null) return
        withContext(Dispatchers.IO) {
            val combined = File(cacheDir, "adblock-hosts.txt")
            if (combined.isFile && System.currentTimeMillis() - combined.lastModified() < 24 * 3_600_000L) {
                val cached = readCached(combined)
                if (cached != null) { publish(cached); return@withContext }
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
                publish(sortedHosts(fetched))
                runCatching { combined.writeText(fetched.joinToString("\n")) }
                AppLog.i(TAG, "Ad blocking: ${fetched.size} hosts")
            } else if (combined.isFile) {
                publish(readCached(combined))
            }
        }
    }
}
