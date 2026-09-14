package com.joeyos.app.ui.components

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.joeyos.app.AppLog
import com.joeyos.app.SecondScreenActivity
import com.joeyos.app.data.GuideBlocklist
import com.joeyos.app.data.GuideSource
import com.joeyos.app.data.GuideTarget
import com.joeyos.app.data.Guides
import com.joeyos.app.ui.theme.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/*
 * The second screen's Guide tab, ported from Chameleon: read a saved guide beside the game (text
 * with size, wrap, reflow, Night/Day/Sepia, find, contents and your place remembered; a saved web
 * page offline with a reader mode), or, when there's none, get one — only when you choose: from
 * the GameFAQs archive, or via a search in a built-in browser with ad blocking, saving the page to
 * read offline. Touch only, like the rest of the
 * second screen; typing (find, a site's search box) briefly lets this screen take the keyboard.
 */

/** How a guide is coloured: a daylight and a night-time question. */
enum class GuideTheme(val label: String, val ink: Color, val paper: Color) {
    Night("Dark", Color(0xFFEDEDED), Color(0xFF000000)),
    Day("Light", Color(0xFF101010), Color(0xFFF2F2F2)),
    Sepia("Sepia", Color(0xFF3B2F1E), Color(0xFFF3E6CE)),
}

private fun guidePrefs(context: Context) = context.getSharedPreferences("guides", Context.MODE_PRIVATE)

/**
 * The Guide tab for [target]. [search] opens the browser on a one-off search (an achievement you
 * are stuck on) and is consumed by [onSearchShown].
 */
@Composable
fun GuideTab(
    target: GuideTarget,
    session: Any?,
    search: GuideSource?,
    onSearchShown: () -> Unit,
    chrome: Boolean,
    onChrome: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { guidePrefs(context) }
    // What this tab kept for the game being played: switching to another tab and back reopens the
    // guide at once instead of looking it up and reading it again. A new game starts afresh.
    val memory = remember(session) { GuideMemory.forSession(session) }
    // The guide you last opened for this game, else the best one found.
    // Looked up on a background thread: finding it walks the guides folder on shared storage.
    // Not reset when the target changes: that happens once RetroAchievements names the game's
    // console a few seconds in, and it's the same game, so a guide already open stays open (it
    // used to close and reopen, found on device). A new lookup only replaces it with a different
    // file it finds.
    var guide by remember(memory) { mutableStateOf(memory.guide) }
    var looked by remember(memory) { mutableStateOf(memory.guide != null) }
    fun show(f: File) { guide = f; memory.guide = f }
    LaunchedEffect(memory, target.key) {
        // Already looked up under this name this game: coming back to the tab doesn't walk the
        // guides folder again.
        if (target.key in memory.lookedUp) { looked = true; return@LaunchedEffect }
        val found = withContext(Dispatchers.IO) {
            prefs.getString("chosen_${target.key}", null)?.let(::File)?.takeIf { it.isFile } ?: Guides.find(target)
        }
        memory.lookedUp += target.key
        if (found != null && found != guide) show(found)
        looked = true
    }
    fun choose(f: File) { show(f); prefs.edit().putString("chosen_${target.key}", f.absolutePath).apply() }
    var browsing by remember { mutableStateOf<GuideSource?>(null) }
    var finding by remember { mutableStateOf(false) }   // "Change guide" from the reader

    LaunchedEffect(search) { if (search != null) { browsing = search; onSearchShown() } }
    // Noted once per guide per game (it writes a file), not every time the tab is opened again.
    LaunchedEffect(guide) {
        val g = guide ?: return@LaunchedEffect
        if (memory.noted == g) return@LaunchedEffect
        memory.noted = g
        withContext(Dispatchers.IO) { Guides.remember(target, g) }
    }

    val open = browsing
    val file = guide
    androidx.activity.compose.BackHandler(enabled = open == null && finding && file != null) { finding = false }
    when {
        !looked && open == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Looking for a guide…", fontSize = 12.sp, color = TextFaint)
        }
        open != null -> GuideBrowser(open, target, chrome, onChrome, onClose = { browsing = null }) { saved ->
            Guides.remember(target, saved); choose(saved); finding = false; browsing = null
        }
        file != null && !finding -> if (Guides.isHtml(file)) GuideHtmlPage(file, chrome, onChrome, onChange = { finding = true })
            else GuideTextPage(file, target, memory, chrome, onChange = { finding = true })
        else -> Box(Modifier.fillMaxSize()) {
            GuideFinder(target, current = file, canGoBack = file != null, onBack = { finding = false },
                onChosen = { choose(it); finding = false },
                onDownloaded = { choose(it); finding = false }, onChrome = onChrome,
                onOpen = { s -> if (s.appSearch == null || !openInYouTube(context, s.appSearch)) browsing = s })
        }
    }
}

// ── Getting a guide ──────────────────────────────────────────────────────────────────────

@Composable
private fun GuideFinder(
    target: GuideTarget,
    current: File?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onChosen: (File) -> Unit,
    onDownloaded: (File) -> Unit,
    onChrome: (Boolean) -> Unit,
    onOpen: (GuideSource) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var archive by remember(target.key) { mutableStateOf<String?>(null) }   // status line
    var working by remember { mutableStateOf(false) }
    val saved by produceState(emptyList<File>(), target.key, current) {
        value = withContext(Dispatchers.IO) { Guides.savedFor(target) }
    }
    val hasArchive = saved.any { it.extension.equals("txt", true) }

    Column(
        Modifier.fillMaxSize().hideChromeOnScroll(onChrome).verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp).padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (canGoBack) Pill("‹  Back to the guide", active = false, onClick = onBack)
        Text("Guides for ${target.title}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

        if (saved.isNotEmpty()) {
            SectionLabel("Your guides", Modifier.padding(top = 6.dp))
            saved.forEach { f ->
                GuideChoice(
                    if (f.extension.equals("txt", true)) "GameFAQs guide" else f.nameWithoutExtension.substringAfter(" - "),
                    (if (Guides.isHtml(f)) "Saved page" else "Text guide") + if (f == current) "  ·  open now" else ""
                ) { onChosen(f) }
            }
        }
        Text("Nothing downloads until you choose. Guides are saved in Internal storage › JoeyOS › guides.",
            fontSize = 10.sp, color = TextFaint)

        if (Guides.archiveCovers(target)) {
            SectionLabel("GameFAQs archive", Modifier.padding(top = 6.dp))
            GuideChoice(if (hasArchive) "Download the GameFAQs guide again" else "Download the GameFAQs guide",
                archive ?: if (hasArchive) "You have it above. This fetches a fresh copy." else "The text walkthrough from GameFAQs' archive, to read offline.") {
                if (working) return@GuideChoice
                working = true; archive = "Looking in the archive…"
                scope.launch {
                    val f = Guides.downloadFromArchive(target, context.cacheDir)
                    working = false
                    if (f != null) onDownloaded(f) else archive = "No archived guide found for this game. Try a search below."
                }
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
        }

        SectionLabel("Search on", Modifier.padding(top = 6.dp))
        Text("Opens here: find a guide, then Save to keep the page to read offline.", fontSize = 10.sp, color = TextFaint)
        Guides.searchLinks(target.title).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    Box(Modifier.weight(1f)) {
                        GuideChoice(s.site.removePrefix("Search "), if (s.appSearch != null) "Opens the YouTube app" else null) { onOpen(s) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GuideChoice(title: String, detail: String?, onClick: () -> Unit) {
    CardRow(onClick = onClick) { f -> CardText(title, detail, f) }
}

/**
 * The guide browser: JavaScript on (a live guide site needs it), ads and trackers blocked, and
 * Save, which reads the rendered page out and files it as the game's guide for offline reading.
 */
@Composable
private fun GuideBrowser(
    source: GuideSource, target: GuideTarget, chrome: Boolean, onChrome: (Boolean) -> Unit,
    onClose: () -> Unit, onSaved: (File) -> Unit,
) {
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var webGen by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf(source.site) }
    var saving by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    // Hold the ad-block list only while this browser is open (it's large); the last one closed frees it.
    DisposableEffect(Unit) {
        GuideBlocklist.acquire()
        onDispose { GuideBlocklist.release() }
    }
    LaunchedEffect(Unit) { runCatching { GuideBlocklist.ensureLoaded(context.cacheDir) } }
    // Typing into a page (a site's search box) needs this screen to take the keyboard.
    AllowTyping(typing)
    // Back: the previous page, then out of the browser.
    androidx.activity.compose.BackHandler { web?.takeIf { it.canGoBack() }?.goBack() ?: onClose() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        if (chrome || typing || saving) Row(
            Modifier.fillMaxWidth().background(Background).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Pill("Close", active = false, onClick = onClose)
            Pill("‹", active = false) { web?.let { if (it.canGoBack()) it.goBack() } }
            Text(title, fontSize = 11.sp, color = TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Pill("Type", active = typing) { typing = !typing }
            Pill(if (saving) "Saving…" else "Save", active = true) {
                val v = web ?: return@Pill
                if (saving) return@Pill
                saving = true
                // A complete web archive (the page with its styles, images and layout inside), not
                // just its HTML: HTML alone opened offline without any of that and looked broken
                // (found on device).
                val target = Guides.pageDestination(target, title)
                v.saveWebArchive(target.absolutePath, false) { path ->
                    saving = false
                    val f = path?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
                    if (f != null) { AppLog.i("Guides", "Saved a guide page: ${f.absolutePath}"); onSaved(f) }
                    else AppLog.w("Guides", "Save: couldn't save ${v.url}")
                }
            }
        }
        key(webGen) { AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Present as the Chrome it is, not an embedded view ("; wv"), and keep cookies,
                    // so sites stop treating each visit as a suspicious stranger (captchas).
                    settings.userAgentString = settings.userAgentString.replace("; wv)", ")").replace(" Version/4.0", "")
                    val view = this
                    android.webkit.CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(view, true) }
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            title = view.title?.takeIf { it.isNotBlank() } ?: source.site
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                            GuideBlocklist.intercept(request)
                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail) =
                            webGone(view, detail) { web = null; saving = false; webGen++ }
                    }
                    hideChromeOnWebScroll(this, onChrome)
                    loadUrl(source.url)
                    web = this
                }
            },
            onRelease = { it.destroy() }
        ) }
    }
    }
}

/**
 * Hides the tabs and bar while a web page is scrolled down, and shows them at its top. A web
 * page's scrolling doesn't reach Compose, so the bars never hid on search pages (found on device).
 */
/**
 * A web page's renderer died (usually Android reclaiming memory while a game runs). Unhandled,
 * that takes all of JoeyOS down with it; instead drop this view and let [rebuild] make a new one.
 */
private fun webGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail, rebuild: () -> Unit): Boolean {
    AppLog.w("Guides", "The web page's renderer " + (if (detail.didCrash()) "crashed" else "was closed for memory") + "; reloading it")
    // Only taken out of the page here; onRelease destroys it once, as it does any web view.
    (view.parent as? android.view.ViewGroup)?.removeView(view)
    rebuild()
    return true
}

private fun hideChromeOnWebScroll(view: WebView, onChrome: (Boolean) -> Unit, onScrolled: ((Int) -> Unit)? = null) {
    var up = 0
    val reveal = (RevealUpDp * view.resources.displayMetrics.density).toInt()
    view.setOnScrollChangeListener { _, _, y, _, oldY ->
        when {
            y == 0 -> { up = 0; onChrome(true) }
            y > oldY -> { up = 0; if (y > oldY + 12) onChrome(false) }
            else -> { up += oldY - y; if (up > reveal) onChrome(true) }
        }
        onScrolled?.invoke(y)
    }
}

/** Where your place in a guide file is kept (ten-thousandths of the way through). */
private fun guidePosKey(file: File) = "pos_file_" + file.absolutePath

/** How far a web view can scroll, in its own pixels. */
@Suppress("DEPRECATION")
private fun WebView.scrollRange() = (contentHeight * scale - height).toInt()

/** Scrolling back up this far (not a nudge while reading) brings the tabs and bar back. */
private const val RevealUpDp = 120f

/** Hides the tabs and bar while reading down; a real scroll back up (or the strip) shows them. */
private class ChromeOnScroll(private val revealPx: Float, private val onChrome: (Boolean) -> Unit) :
    androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    private var up = 0f
    override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
        if (available.y < 0f) { up = 0f; if (available.y < -6f) onChrome(false) }
        else { up += available.y; if (up > revealPx) { up = 0f; onChrome(true) } }
        return androidx.compose.ui.geometry.Offset.Zero
    }
}

@Composable
private fun Modifier.hideChromeOnScroll(onChrome: (Boolean) -> Unit): Modifier {
    val px = with(LocalDensity.current) { RevealUpDp.dp.toPx() }
    return nestedScroll(remember(onChrome) { ChromeOnScroll(px, onChrome) })
}

/** While [on], the second screen can take the keyboard (it normally can't, so the game keeps its buttons). */
@Composable
internal fun AllowTyping(on: Boolean) {
    val activity = LocalContext.current as? SecondScreenActivity
    DisposableEffect(on) {
        activity?.allowTyping(on)
        onDispose { activity?.allowTyping(false) }
    }
}

// ── Reading a text guide ─────────────────────────────────────────────────────────────────

@Composable
private fun GuideTextPage(file: File, target: GuideTarget, memory: GuideMemory, chrome: Boolean, onChange: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { guidePrefs(context) }
    var size by remember { mutableIntStateOf(prefs.getInt("size", 15)) }
    var wrap by remember { mutableStateOf(prefs.getBoolean("wrap", true)) }
    var reflow by remember { mutableStateOf(prefs.getBoolean("reflow", false)) }
    var theme by remember { mutableStateOf(GuideTheme.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: GuideTheme.Night) }
    // Find stays open with its search when you come back from another tab.
    var findOpen by remember(file) { mutableStateOf(memory.views[file.absolutePath]?.findOpen == true) }
    var tocOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    fun save(block: android.content.SharedPreferences.Editor.() -> Unit) = prefs.edit().apply(block).apply()
    // The bar stays while something on it is open.
    val showBar = chrome || findOpen || tocOpen || optionsOpen

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (showBar) Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Pill("Options", optionsOpen) { optionsOpen = !optionsOpen }
                Pill("Find", findOpen) { findOpen = !findOpen }
                Pill("Contents", tocOpen) { tocOpen = !tocOpen }
                Spacer(Modifier.weight(1f))
                Pill("Change guide", false, onChange)
            }
            GuideText(file, memory, legacyKey = "pos_${target.key}", theme, size, wrap, reflow,
                findOpen = findOpen, onFindOpen = { findOpen = it }, tocOpen = tocOpen, onTocOpen = { tocOpen = it })
        }

        if (optionsOpen) GuideOptions(
            size, wrap, reflow, theme,
            onSize = { size = it.coerceIn(9, 28); save { putInt("size", size) } },
            onWrap = { wrap = it; save { putBoolean("wrap", it) } },
            onReflow = { reflow = it; save { putBoolean("reflow", it) } },
            onTheme = { theme = it; save { putString("theme", it.name) } },
            onClose = { optionsOpen = false }
        )
    }
}

/**
 * The reader's options, in a panel over the guide (not a popup window: a window on this screen
 * could take the controller from the game).
 */
@Composable
private fun GuideOptions(
    size: Int, wrap: Boolean, reflow: Boolean, theme: GuideTheme,
    onSize: (Int) -> Unit, onWrap: (Boolean) -> Unit, onReflow: (Boolean) -> Unit, onTheme: (GuideTheme) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClose)) {
        // Scrolls: the Thor's bottom screen is short, and the colours at the bottom were cut off
        // with no way to reach them (found on device).
        Column(
            Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(SheetBg).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reading options", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
                Pill("Done", false, onClose)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Text size", Modifier.weight(1f))
                Pill("A−", false) { onSize(size - 1) }
                Text("$size", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Pill("A+", false) { onSize(size + 1) }
            }
            SectionLabel("Colours")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GuideTheme.entries.forEach { t -> ThemeSwatch(t, theme == t) { onTheme(t) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { ToggleRow("Wrap lines", "Off keeps maps and tables intact.", wrap || reflow, onWrap) }
                Box(Modifier.weight(1f)) { ToggleRow("Reflow", "Fits old FAQs to the screen.", reflow, onReflow) }
            }
        }
    }
}

/** A colour choice shown as what it looks like: the page colour, "Aa" in its text colour, and its name. */
@Composable
private fun ThemeSwatch(t: GuideTheme, chosen: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(10.dp)).background(t.paper)
                .border(if (chosen) 2.dp else 1.dp, if (chosen) Accent else Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) { Text("Aa", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.ink) }
        Text(t.label, fontSize = 12.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
            color = if (chosen) Accent else TextDim)
    }
}

/** Searches YouTube in its app, on this screen. False when the app isn't installed. */
private fun openInYouTube(context: Context, query: String): Boolean {
    // Any installed YouTube app: the official one, or a patched build like ReVanced, which has its
    // own app id (found on device: only the official id was tried). YouTube Music and Kids aren't it.
    val pm = context.packageManager
    val launchable = pm.queryIntentActivities(
        android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0
    ).map { it.activityInfo.packageName }.distinct()
    val apps = launchable.filter { p ->
        val id = p.lowercase()
        "youtube" in id && "music" !in id && "kids" !in id && "creator" !in id && "studio" !in id
    }.sortedBy { if (it == "com.google.android.youtube") 1 else 0 }   // a patched one first if both
    val display = com.joeyos.app.data.DisplayTargets.currentDisplayId(context)
    val url = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8")
    for (pkg in apps) {
        val attempts = listOf(
            android.content.Intent(android.content.Intent.ACTION_SEARCH).putExtra("query", query),
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
        )
        for (intent in attempts) {
            intent.setPackage(pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent, com.joeyos.app.data.DisplayTargets.optionsFor(display)) }.isSuccess) {
                AppLog.i("Guides", "YouTube search in $pkg: $query")
                return true
            }
        }
    }
    AppLog.i("Guides", "No YouTube app to search in (found: ${apps.ifEmpty { listOf("none") }})")
    return false
}

// ── Remembered while a game is played ────────────────────────────────────────────────────

/**
 * What the Guide tab keeps while one game is played, so switching to another tab and back opens
 * the guide at once, at the same place, rather than looking it up, reading, reflowing and laying
 * it out again (a GameFAQs guide can be a megabyte, found slow on device). Only the latest game's
 * is kept; a new game starts a new one. Only used on the main thread (composition and effects).
 */
internal class GuideMemory private constructor(private val session: Any?) {
    /** The guide showing, kept across the tab being closed. */
    var guide: File? = null
    /** The guide last noted against the game's names (see [Guides.remember]). */
    var noted: File? = null
    /** The game names already looked up. */
    val lookedUp = HashSet<String>()
    /** Where you were in each guide, by file path. */
    val views = HashMap<String, ReaderView>()
    // Read and prepared guides, newest last. Two at most: the one open, and the one before it
    // (the same guide reflowed or not, or the guide you just changed from), to bound memory.
    private val docs = ArrayList<GuideDoc>()

    fun doc(file: File, reflow: Boolean): GuideDoc? = docs.lastOrNull { it.path == file.absolutePath && it.reflow == reflow }

    fun keep(d: GuideDoc) {
        docs.removeAll { it.path == d.path && it.reflow == d.reflow }
        docs.add(d)
        while (docs.size > 2) docs.removeAt(0)
    }

    companion object {
        private var latest: GuideMemory? = null
        /** The memory for [session] (the game being played), a fresh one when the game changed. */
        fun forSession(session: Any?): GuideMemory =
            latest?.takeIf { it.session == session } ?: GuideMemory(session).also { latest = it }
    }
}

/** Where the reader was in a guide when the tab closed, and its search. */
internal class ReaderView(
    val stamp: Long, val reflow: Boolean, val length: Int,
    /** The list's first item and how far into it, valid only for the same text size and wrapping. */
    val index: Int, val offset: Int, val across: Int, val size: Int, val wrapping: Boolean,
    /** The first character on screen, which works for any text size. */
    val char: Int,
    val query: String, val current: Int, val findOpen: Boolean,
)

/**
 * A text guide, read and prepared for the reader: the text as shown (reflowed or not), cut into
 * chunks at line breaks so the list only lays out what's on screen, its headings for Contents,
 * and its widest line (how wide the page is when lines don't wrap).
 */
internal class GuideDoc(
    val path: String, val stamp: Long, val reflow: Boolean, val text: String,
    /** Chunk i is text[starts[i], ends[i]); the line break between two chunks belongs to neither. */
    val starts: IntArray, val ends: IntArray,
    val headings: List<Pair<String, Int>>,
    val widest: String,
) {
    val count: Int get() = starts.size

    /** The chunk holding character [c]. */
    fun chunkFor(c: Int): Int {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= c) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** A place saved against [length] characters of this guide (reflowed or not) as a character here. */
    fun placeOf(char: Int, length: Int, reflowed: Boolean): Int = when {
        reflowed == reflow && length == text.length -> char.coerceIn(0, text.length)
        // The other layout of the text (or the file changed): the same share of the way through.
        length > 0 -> (char.toLong() * text.length / length).toInt().coerceIn(0, text.length)
        else -> 0
    }
}

// A chunk ends at a paragraph gap once it's this long, or anywhere once it's much longer (a long
// ASCII map with no gaps). Small enough that laying one out is quick, big enough to keep the list
// short.
private const val ChunkLines = 30
private const val ChunkChars = 2_500
private const val ChunkMaxLines = 80
private const val ChunkMaxChars = 8_000

/** Cuts [text] into a [GuideDoc] (on a background thread: it walks every line). */
private fun prepareGuide(path: String, stamp: Long, reflow: Boolean, text: String): GuideDoc {
    val starts = ArrayList<Int>()
    val ends = ArrayList<Int>()
    val headings = ArrayList<Pair<String, Int>>()
    var widestStart = 0
    var widestEnd = 0
    var widestCols = -1
    var chunkStart = 0
    var lines = 0
    var lineStart = 0
    var prevBlank = false
    while (true) {
        val nl = text.indexOf('\n', lineStart)
        val lineEnd = if (nl < 0) text.length else nl
        var blank = true
        var cols = 0
        for (k in lineStart until lineEnd) {
            val ch = text[k]
            if (!ch.isWhitespace()) blank = false
            cols += if (ch == '\t') 8 else 1   // a tab is about a tab stop wide, for the widest line
        }
        if (cols > widestCols) { widestCols = cols; widestStart = lineStart; widestEnd = lineEnd }
        // Break before this line: at the start of a paragraph gap once the chunk is long enough,
        // or wherever it is once it's far too long.
        val size = lineStart - chunkStart
        if (lines > 0 && (((lines >= ChunkLines || size >= ChunkChars) && blank && !prevBlank) ||
                lines >= ChunkMaxLines || size >= ChunkMaxChars)) {
            starts.add(chunkStart); ends.add(lineStart - 1)
            chunkStart = lineStart; lines = 0
        }
        if (!blank && lineEnd - lineStart <= 200) {
            val trimmed = text.substring(lineStart, lineEnd).trim()
            if (isGuideHeading(trimmed)) headings.add(trimmed to lineStart)
        }
        lines++
        prevBlank = blank
        if (nl < 0) break
        lineStart = nl + 1
    }
    starts.add(chunkStart); ends.add(text.length)
    return GuideDoc(path, stamp, reflow, text, starts.toIntArray(), ends.toIntArray(), headings,
        text.substring(widestStart, widestEnd))
}

/**
 * The guide in [file], read and prepared off the main thread (a GameFAQs guide can be a megabyte,
 * and the guides folder is on shared storage, which is slow). [cached] is reused while the file is
 * unchanged. Null when it can't be read.
 */
private suspend fun loadGuide(file: File, reflow: Boolean, cached: GuideDoc?): GuideDoc? {
    val stamp = withContext(Dispatchers.IO) { file.lastModified() }
    if (cached != null && cached.stamp == stamp) return cached
    val raw = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() } ?: return null
    return withContext(Dispatchers.Default) {
        prepareGuide(file.absolutePath, stamp, reflow, if (reflow) reflowGuide(raw) else raw)
    }
}

/** Where your place in a text guide is kept: "character/length/reflowed". */
private fun guidePlaceKey(path: String) = "place_file_$path"

/** Your saved place in [doc], from this version's precise key or an older version's fraction. */
private fun readPlace(prefs: android.content.SharedPreferences, doc: GuideDoc, legacyKey: String): Int {
    prefs.getString(guidePlaceKey(doc.path), null)?.split('/')?.let { p ->
        val c = p.getOrNull(0)?.toIntOrNull()
        val len = p.getOrNull(1)?.toIntOrNull()
        if (c != null && len != null) return doc.placeOf(c, len, p.getOrNull(2) == "1")
    }
    // Saved by an older version as ten-thousandths of the way down: near enough, by characters.
    val fraction = runCatching { prefs.getInt("pos_file_" + doc.path, prefs.getInt(legacyKey, 0)) }.getOrDefault(0)
    return (fraction.toLong() * doc.text.length / 10_000).toInt().coerceIn(0, doc.text.length)
}

/** A search's matches, with the query they're for (so a stale answer isn't shown for a newer one). */
private class FindResult(val query: String, val offsets: List<Int>)

@Composable
private fun GuideText(
    file: File, memory: GuideMemory, legacyKey: String, theme: GuideTheme, textSize: Int, wraps: Boolean, reflow: Boolean,
    findOpen: Boolean, onFindOpen: (Boolean) -> Unit, tocOpen: Boolean, onTocOpen: (Boolean) -> Unit,
) {
    // Reflowed to the screen when asked, rejoining hard-wrapped prose; find, contents and the
    // saved place all work on the text as shown. Shown at once when this game already opened it.
    var doc by remember(file, reflow) { mutableStateOf(memory.doc(file, reflow)) }
    var failed by remember(file, reflow) { mutableStateOf(false) }
    LaunchedEffect(file, reflow) {
        val cached = doc
        val fresh = loadGuide(file, reflow, cached)
        if (fresh == null) { if (cached == null) failed = true; return@LaunchedEffect }
        memory.keep(fresh)
        if (fresh !== cached) doc = fresh
    }
    Box(Modifier.fillMaxSize().background(theme.paper)) {
        val d = doc
        if (d == null) Text(if (failed) "This guide couldn't be read." else "Opening the guide…",
            color = theme.ink, fontFamily = FontFamily.Monospace, fontSize = textSize.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        // A new text (another file, or reflow switched) is a new list with its own state.
        else key(d) { GuideReader(d, memory, legacyKey, theme, textSize, reflow || wraps, findOpen, onFindOpen, tocOpen, onTocOpen) }
    }
}

/** The reader's bookkeeping that isn't shown: plain fields, so writing them doesn't recompose. */
private class ReaderPlace(var char: Int, var skipRestore: Boolean) {
    var restored = false
    var jumpToMatch = false
}

/**
 * A prepared guide in a lazy list of chunks: only the chunks on screen are laid out, so a
 * megabyte guide opens as fast as a short one. Without wrapping, the whole list scrolls sideways
 * together, as wide as the guide's widest line.
 */
@Composable
private fun GuideReader(
    doc: GuideDoc, memory: GuideMemory, legacyKey: String, theme: GuideTheme, textSize: Int, wrapping: Boolean,
    findOpen: Boolean, onFindOpen: (Boolean) -> Unit, tocOpen: Boolean, onTocOpen: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { guidePrefs(context) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Coming back from another tab: the exact list position when nothing about the layout changed
    // (it opens right there, with no jump), else the first character that was on screen.
    val saved = remember { memory.views[doc.path] }
    val exact = saved != null && saved.stamp == doc.stamp && saved.reflow == doc.reflow &&
        saved.size == textSize && saved.wrapping == wrapping && saved.index < doc.count
    val place = remember {
        ReaderPlace(saved?.let { doc.placeOf(it.char, it.length, it.reflow) } ?: readPlace(prefs, doc, legacyKey), skipRestore = exact)
    }
    // Opens on the chunk holding your place, so it's composed and measured on the first frame.
    val list = rememberLazyListState(
        if (exact) saved!!.index else doc.chunkFor(place.char), if (exact) saved!!.offset else 0)
    val across = rememberScrollState(if (exact) saved!!.across else 0)
    // Each chunk on screen's text layout, for finding a line within it. Kept as state so waiting
    // for a chunk to be measured is waiting on real state (snapshotFlow), not a guess.
    val layouts = remember { mutableStateMapOf<Int, TextLayoutResult>() }
    val size by rememberUpdatedState(textSize)
    val wraps by rememberUpdatedState(wrapping)
    val lineHeight = (textSize * 1.35f).sp
    // Text guides keep a monospace face: they're drawn as ASCII maps and tables to a fixed column.
    // No trimming at a chunk's first and last lines, so the gap between two chunks is exactly a
    // line's, as if it were one text.
    val style = remember(textSize) {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = textSize.sp, lineHeight = lineHeight,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None))
    }
    val padPx = with(density) { 12.dp.toPx() }

    fun fits(l: TextLayoutResult?): Boolean =
        l != null && l.layoutInput.style.fontSize == size.sp && l.layoutInput.softWrap == wraps

    /** The first character on screen: the start of the top line of the first chunk showing. */
    fun firstVisibleChar(): Int {
        if (doc.count == 0) return 0
        val i = list.firstVisibleItemIndex.coerceIn(0, doc.count - 1)
        val l = layouts[i]?.takeIf(::fits)
        val local = l?.let { it.getLineStart(it.getLineForVerticalPosition(list.firstVisibleItemScrollOffset.toFloat())) } ?: 0
        return doc.starts[i] + local
    }

    /** Notes where you are, for coming back later (kept as a character, so any text size finds it). */
    fun recordPlace() {
        place.char = firstVisibleChar()
        prefs.edit().putString(guidePlaceKey(doc.path), "${place.char}/${doc.text.length}/${if (doc.reflow) 1 else 0}").apply()
    }

    /**
     * Scrolls so the line holding character [c] is at the top ([linesAbove] lines down, so a find
     * bar doesn't cover it). Its chunk is scrolled in first if it isn't laid out, then this waits
     * for its layout before placing the line.
     */
    suspend fun scrollToChar(c: Int, linesAbove: Int = 0, sideways: Boolean = false) {
        if (doc.count == 0) return
        val i = doc.chunkFor(c)
        val local = (c - doc.starts[i]).coerceIn(0, doc.ends[i] - doc.starts[i])
        val l = layouts[i]?.takeIf(::fits) ?: run {
            list.scrollToItem(i)
            snapshotFlow { layouts[i]?.takeIf(::fits) }.filterNotNull().first()
        }
        val line = l.getLineForOffset(local)
        val y = l.getLineTop(line) - linesAbove * with(density) { (size * 1.35f).sp.toPx() }
        if (y >= 0f) list.scrollToItem(i, y.toInt()) else { list.scrollToItem(i); list.scrollBy(y) }
        if (sideways && !wraps && across.viewportSize > 0) {
            val box = l.getBoundingBox(local.coerceAtMost((doc.ends[i] - doc.starts[i] - 1).coerceAtLeast(0)))
            val x = box.left + padPx
            if (x < across.value || x + box.width > across.value + across.viewportSize)
                across.scrollTo((x - across.viewportSize / 3f).toInt().coerceIn(0, across.maxValue))
        }
    }

    // Back to your place, and again after the text size or wrapping changes (the same character
    // stays at the top). Waits on the chunk's layout inside scrollToChar, no polling.
    LaunchedEffect(textSize, wrapping) {
        place.restored = false
        if (place.skipRestore) place.skipRestore = false else scrollToChar(place.char)
        place.restored = true
    }
    // Saved when scrolling stops, not on every pixel.
    LaunchedEffect(Unit) {
        snapshotFlow { list.isScrollInProgress }.drop(1).filter { !it }.collect { if (place.restored) recordPlace() }
    }

    // Find: every match of the query (two characters or more), and which is current. Searched on
    // a background thread; a newer query cancels an older search.
    var query by remember { mutableStateOf(saved?.query.orEmpty()) }
    var current by remember { mutableIntStateOf(saved?.current ?: 0) }
    val found by produceState(FindResult("", emptyList()), query) {
        val q = query
        value = if (q.length < 2) FindResult(q, emptyList()) else FindResult(q, withContext(Dispatchers.Default) {
            val out = ArrayList<Int>()
            var i = doc.text.indexOf(q, 0, ignoreCase = true)
            while (i >= 0) {
                out.add(i)
                if (out.size % 256 == 0) ensureActive()
                i = doc.text.indexOf(q, i + q.length, ignoreCase = true)
            }
            out
        })
    }
    val matches = if (found.query == query) found.offsets else emptyList()
    suspend fun showMatch(n: Int) {
        matches.getOrNull(n)?.let { scrollToChar(it, linesAbove = 3, sideways = true) }
        if (place.restored) recordPlace()
    }
    // A new search jumps to its first match once its matches are in (not when you come back to
    // the tab with a search open: that keeps your place).
    LaunchedEffect(found) {
        if (found.query != query) return@LaunchedEffect
        if (current >= found.offsets.size) current = 0
        if (place.jumpToMatch) { place.jumpToMatch = false; showMatch(current) }
    }

    // Kept for coming back to this tab.
    val latestQuery by rememberUpdatedState(query)
    val latestCurrent by rememberUpdatedState(current)
    val latestFind by rememberUpdatedState(findOpen)
    DisposableEffect(Unit) {
        onDispose {
            if (place.restored) recordPlace()
            memory.views[doc.path] = ReaderView(doc.stamp, doc.reflow, doc.text.length,
                list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, across.value, size, wraps,
                place.char, latestQuery, latestCurrent, latestFind)
        }
    }

    val measurer = rememberTextMeasurer()
    // How wide the page is without wrapping: its widest line, measured once per text size.
    val widest = remember(doc, style, wrapping) {
        if (wrapping) 0 else measurer.measure(doc.widest, style, softWrap = false, maxLines = 1).size.width
    }
    val currentAt = matches.getOrNull(current) ?: -1
    val qLen = query.length

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageWidth = maxOf(maxWidth, with(density) { widest.toDp() } + 24.dp + 8.dp)
            val chunks: LazyListScope.() -> Unit = {
                items(doc.count, key = { it }, contentType = { 0 }) { i ->
                    val start = doc.starts[i]
                    val end = doc.ends[i]
                    val chunk = remember(i) { doc.text.substring(start, end) }
                    // Only the matches in this chunk are highlighted in it.
                    val hereCurrent = if (currentAt in start until end) currentAt else -1
                    val shown = remember(chunk, matches, hereCurrent, theme, qLen) {
                        highlightChunk(chunk, start, matches, hereCurrent, qLen, theme)
                    }
                    DisposableEffect(i) { onDispose { layouts.remove(i) } }
                    Text(shown, color = theme.ink, style = style, softWrap = wrapping,
                        onTextLayout = { layouts[i] = it },
                        modifier = if (wrapping) Modifier.fillMaxWidth() else Modifier)
                }
            }
            val padding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            if (wrapping) LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = padding, content = chunks)
            // One sideways scroll around the whole list, so every line moves together.
            else Box(Modifier.fillMaxSize().horizontalScroll(across)) {
                LazyColumn(Modifier.fillMaxHeight().width(pageWidth), state = list, contentPadding = padding, content = chunks)
            }
        }
        if (findOpen) {
            AllowTyping(true)
            FindBar(query, { query = it; current = 0; place.jumpToMatch = true }, matches.size, if (matches.isEmpty()) 0 else current + 1,
                onPrev = { if (matches.isNotEmpty()) { current = (current - 1 + matches.size) % matches.size; val n = current; scope.launch { showMatch(n) } } },
                onNext = { if (matches.isNotEmpty()) { current = (current + 1) % matches.size; val n = current; scope.launch { showMatch(n) } } },
                onClose = { onFindOpen(false); query = "" })
        }
        if (tocOpen) {
            Box(Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { onTocOpen(false) }) {
                // A list too: a long FAQ can have hundreds of headings.
                LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Contents", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Pill("Close", false) { onTocOpen(false) }
                        }
                    }
                    if (doc.headings.isEmpty()) item { Text("No sections found in this guide.", color = TextFaint, fontSize = 13.sp) }
                    items(doc.headings.size) { k ->
                        val (title, offset) = doc.headings[k]
                        Text(title, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    onTocOpen(false)
                                    scope.launch { scrollToChar(offset); if (place.restored) recordPlace() }
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

/** [chunk] (starting at [start] in the guide) with the matches inside it highlighted. */
private fun highlightChunk(chunk: String, start: Int, matches: List<Int>, current: Int, length: Int, theme: GuideTheme): AnnotatedString {
    if (matches.isEmpty() || length < 2) return AnnotatedString(chunk)
    val end = start + chunk.length
    var k = matches.binarySearch(start).let { if (it >= 0) it else -it - 1 }
    if (k >= matches.size || matches[k] >= end) return AnnotatedString(chunk)
    return buildAnnotatedString {
        append(chunk)
        while (k < matches.size && matches[k] < end) {
            val off = matches[k]
            val on = off == current
            addStyle(SpanStyle(background = if (on) Accent else Accent.copy(alpha = 0.33f),
                color = if (on) Color(0xFF101010) else theme.ink),
                off - start, (off - start + length).coerceAtMost(chunk.length))
            k++
        }
    }
}

/** Find in a guide (text or saved page): the box, "current/total", previous, next, Done. */
@Composable
private fun FindBar(query: String, onQuery: (String) -> Unit, total: Int, current: Int,
                    onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit,
                    placeholder: String = "Find in guide") {
    Row(
        Modifier.fillMaxWidth().background(Color(0xF2141414)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SearchField(query, onQuery, placeholder, Modifier.weight(1f),
            classicBox = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A))
                .padding(horizontal = 10.dp, vertical = 8.dp))
        Text(if (total == 0) "0" else "$current/$total", color = TextDim, fontSize = 12.sp)
        Pill("‹", false, onPrev)
        Pill("›", false, onNext)
        Pill("Done", false, onClose)
    }
}

// ── A saved web page ─────────────────────────────────────────────────────────────────────

/**
 * A guide saved from a site, read offline in a WebView. JavaScript is on for Reader mode's
 * extraction, but every non-file request is refused, so the page can't reach anything online.
 */
@Composable
private fun GuideHtmlPage(file: File, chrome: Boolean, onChrome: (Boolean) -> Unit, onChange: () -> Unit) {
    var webGen by remember { mutableIntStateOf(0) }
    val prefs = guidePrefs(LocalContext.current)
    var web by remember(file) { mutableStateOf<WebView?>(null) }
    var reader by remember(file) { mutableStateOf(false) }
    var findOpen by remember(file) { mutableStateOf(false) }
    var query by remember(file) { mutableStateOf("") }
    var matches by remember(file) { mutableIntStateOf(0) }
    var activeMatch by remember(file) { mutableIntStateOf(0) }   // which match is showing, from 0
    if (findOpen) AllowTyping(true)

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        if (chrome || findOpen) Row(
            Modifier.fillMaxWidth().background(Background).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Pill(if (reader) "Full page" else "Reader", reader) {
                reader = !reader
                web?.let { if (reader) it.evaluateJavascript(ReaderScript, null) else it.reload() }
            }
            Pill("Find", findOpen) { findOpen = !findOpen; if (!findOpen) { web?.clearMatches(); query = "" } }
            Spacer(Modifier.weight(1f))
            Pill("Change guide", false, onChange)
        }
        if (findOpen) {
            // The same find bar as text guides; the WebView does the searching and reports back.
            FindBar(query, { query = it; web?.findAllAsync(it) }, matches, activeMatch + 1,
                onPrev = { web?.findNext(false) },
                onNext = { web?.findNext(true) },
                onClose = { findOpen = false; web?.clearMatches(); query = "" },
                placeholder = "Find in page")
        }
        key(webGen) { AndroidView(
            modifier = Modifier.fillMaxSize().background(Color.White),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccess = true
                    setFindListener { active, n, _ -> activeMatch = active; matches = n }
                    webViewClient = object : WebViewClient() {
                        var restored = false
                        override fun onPageFinished(view: WebView, url: String?) {
                            val restore = !restored
                            restored = true
                            // Reader mode rewrites the page, so the place is restored after its
                            // script has run, against the page as it will be read.
                            if (reader) view.evaluateJavascript(ReaderScript) { if (restore) restorePlace(view) }
                            else if (restore) restorePlace(view)
                        }
                        /**
                         * Back to where you were, on the first frame drawn once the page has a
                         * height (contentHeight), rather than after a guessed delay.
                         */
                        fun restorePlace(view: WebView) {
                            val at = prefs.getInt(guidePosKey(file), 0)
                            if (at <= 0) return
                            view.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                                override fun onPreDraw(): Boolean {
                                    if (view.contentHeight == 0) return true
                                    view.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                                    view.scrollTo(0, (at / 10_000f * view.scrollRange()).toInt())
                                    return true
                                }
                            })
                        }
                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail) =
                            webGone(view, detail) { web = null; webGen++ }
                        // Offline: nothing from the web. A web archive's own parts (cid:, data:)
                        // and the file itself load.
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                            val scheme = request.url?.scheme.orEmpty().lowercase()
                            return if (scheme == "http" || scheme == "https") android.webkit.WebResourceResponse("text/plain", "utf-8", null)
                            else super.shouldInterceptRequest(view, request)
                        }
                    }
                    // Your place, saved once scrolling settles rather than on every pixel.
                    val savePlace = Runnable {
                        val range = scrollRange()
                        if (range > 0)
                            prefs.edit().putInt(guidePosKey(file), (scrollY.toFloat() / range * 10_000f).toInt().coerceIn(0, 10_000)).apply()
                    }
                    hideChromeOnWebScroll(this, onChrome) { removeCallbacks(savePlace); postDelayed(savePlace, 600) }
                    loadUrl(android.net.Uri.fromFile(file).toString())
                    web = this
                }
            },
            onRelease = { it.destroy() }
        ) }
    }
    }
}

/** Reader mode: keep the densest block of running text (the guide), restyled for a dark screen. */
private const val ReaderScript = """
(function(){
  try {
    var best=null, bestScore=0;
    var nodes=document.querySelectorAll('article,main,[role=main],section,div');
    for (var i=0;i<nodes.length;i++){
      var el=nodes[i];
      var text=(el.innerText||'').length;
      var paras=el.querySelectorAll('p,li,br,pre').length;
      var score=text+paras*30;
      var cls=((el.className||'')+' '+(el.id||'')).toLowerCase();
      if(/nav|menu|footer|header|sidebar|comment|share|related|promo|advert|cookie/.test(cls)) score*=0.2;
      if(score>bestScore){bestScore=score;best=el;}
    }
    if(best){document.body.innerHTML='<div id="rdr">'+best.innerHTML+'</div>';}
    var s=document.createElement('style');
    s.innerHTML='html,body{background:#0B0D12!important;color:#F3EFE4!important;margin:0;padding:14px;font-family:sans-serif;line-height:1.55;font-size:16px}#rdr img{max-width:100%;height:auto}a{color:#4DA3FF}h1,h2,h3,h4{color:#fff}table{max-width:100%}';
    document.head.appendChild(s);
  } catch(e){}
})();
"""

/**
 * Rejoins the hard-wrapped prose of an old text guide so it wraps to the screen, leaving blocks
 * whose line breaks carry meaning (tables, maps, ASCII art) alone.
 */
internal fun reflowGuide(text: String): String {
    val lines = text.split("\n")
    val out = StringBuilder()
    var i = 0
    while (i < lines.size) {
        if (lines[i].isBlank()) { out.append("\n"); i++; continue }
        val block = ArrayList<String>()
        while (i < lines.size && lines[i].isNotBlank()) { block.add(lines[i]); i++ }
        out.append(if (isProseBlock(block)) block.joinToString(" ") { it.trim() } else block.joinToString("\n"))
        out.append("\n")
    }
    return out.toString()
}

/** Hard-wrapped prose: lines near one column width, no aligned columns or box-drawing characters. */
internal fun isProseBlock(lines: List<String>): Boolean {
    if (lines.size < 2) return false
    for (line in lines) {
        if (Regex("""\S {2,}\S""").containsMatchIn(line)) return false
        if (line.count { it in "|+=_/\\<>#*~" } > line.length * 0.15f) return false
    }
    val nonLast = lines.dropLast(1)
    return nonLast.count { it.trim().length in 40..90 }.toFloat() / nonLast.size >= 0.6f
}

/** A heading in a plain-text FAQ: a short line that is mostly capitals, or numbered like an outline. */
internal fun isGuideHeading(line: String): Boolean {
    if (line.length !in 3..48) return false
    val letters = line.count { it.isLetter() }
    if (letters < 2) return false
    if (line.count { it in "=-*_~|" } > line.length / 2) return false
    val upper = line.count { it.isLetter() && it.isUpperCase() }
    return upper.toFloat() / letters >= 0.7f || Regex("""^\d+([.)]|\.\d+)*[.)]?\s+\S""").containsMatchIn(line)
}
