package com.joeyos.app.ui.components

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
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
import kotlinx.coroutines.launch
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
    search: GuideSource?,
    onSearchShown: () -> Unit,
    chrome: Boolean,
    onChrome: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { guidePrefs(context) }
    // The guide you last opened for this game, else the best one found.
    var guide by remember(target.key) {
        mutableStateOf(prefs.getString("chosen_${target.key}", null)?.let(::File)?.takeIf { it.isFile } ?: Guides.find(target))
    }
    fun choose(f: File) { guide = f; prefs.edit().putString("chosen_${target.key}", f.absolutePath).apply() }
    var browsing by remember(target.key) { mutableStateOf<GuideSource?>(null) }
    var finding by remember(target.key) { mutableStateOf(false) }   // "Change guide" from the reader

    LaunchedEffect(search) { if (search != null) { browsing = search; onSearchShown() } }
    LaunchedEffect(guide) { guide?.let { Guides.remember(target, it) } }

    val open = browsing
    val file = guide
    when {
        open != null -> GuideBrowser(open, target, chrome, onChrome, onClose = { browsing = null }) { saved ->
            Guides.remember(target, saved); choose(saved); finding = false; browsing = null
        }
        file != null && !finding -> if (Guides.isHtml(file)) GuideHtmlPage(file, chrome, onChrome, onChange = { finding = true })
            else GuideTextPage(file, target, chrome, onChrome, onChange = { finding = true })
        else -> Box(Modifier.fillMaxSize()) {
            GuideFinder(target, current = file, canGoBack = file != null, onBack = { finding = false },
                onChosen = { choose(it); finding = false },
                onDownloaded = { choose(it); finding = false }, onChrome = onChrome,
                onOpen = { s -> if (s.appSearch == null || !openInYouTube(context, s.appSearch)) browsing = s })
            ChromeHandle(visible = !chrome, onShow = { onChrome(true) })
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
    val saved = remember(target.key, current) { Guides.savedFor(target) }
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
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Amber)
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
    LaunchedEffect(Unit) { runCatching { GuideBlocklist.ensureLoaded(context.cacheDir) } }
    // Typing into a page (a site's search box) needs this screen to take the keyboard.
    AllowTyping(typing)

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
                            webGone(view, detail) { webGen++ }
                    }
                    hideChromeOnWebScroll(this, onChrome)
                    loadUrl(source.url)
                    web = this
                }
            },
            onRelease = { it.destroy() }
        ) }
    }
    ChromeHandle(visible = !(chrome || typing || saving), onShow = { onChrome(true) })
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
    (view.parent as? android.view.ViewGroup)?.removeView(view)
    view.destroy()
    rebuild()
    return true
}

private fun hideChromeOnWebScroll(view: WebView, onChrome: (Boolean) -> Unit, onScrolled: ((Int) -> Unit)? = null) {
    view.setOnScrollChangeListener { _, _, y, _, oldY ->
        if (y > oldY + 12) onChrome(false) else if (y == 0) onChrome(true)
        onScrolled?.invoke(y)
    }
}

/** Where your place in a guide file is kept (ten-thousandths of the way through). */
private fun guidePosKey(file: File) = "pos_file_" + file.absolutePath

/** How far a web view can scroll, in its own pixels. */
@Suppress("DEPRECATION")
private fun WebView.scrollRange() = (contentHeight * scale - height).toInt()

/** Reading down a scrolling column hides the tabs and bar; the handle brings them back. */
private fun Modifier.hideChromeOnScroll(onChrome: (Boolean) -> Unit): Modifier = nestedScroll(
    object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            if (available.y < -6f) onChrome(false)
            return androidx.compose.ui.geometry.Offset.Zero
        }
    })

/** The small handle at the top while the tabs and bar are hidden: tap to bring them back. */
@Composable
private fun BoxScope.ChromeHandle(visible: Boolean, onShow: () -> Unit) {
    if (!visible) Row(
        Modifier.align(Alignment.TopCenter).padding(top = 6.dp).clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.7f)).border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
            .clickable(onClick = onShow).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(width = 22.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.8f)))
        Text("Show tabs", fontSize = 11.sp, color = Color.White.copy(alpha = 0.9f))
    }
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
private fun GuideTextPage(file: File, target: GuideTarget, chrome: Boolean, onChrome: (Boolean) -> Unit, onChange: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { guidePrefs(context) }
    var size by remember { mutableIntStateOf(prefs.getInt("size", 15)) }
    var wrap by remember { mutableStateOf(prefs.getBoolean("wrap", true)) }
    var reflow by remember { mutableStateOf(prefs.getBoolean("reflow", false)) }
    var theme by remember { mutableStateOf(GuideTheme.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: GuideTheme.Night) }
    var findOpen by remember { mutableStateOf(false) }
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
            GuideText(file, theme, size, wrap, reflow,
                startAt = prefs.getInt(guidePosKey(file), prefs.getInt("pos_${target.key}", 0)),
                onPosition = { p -> save { putInt(guidePosKey(file), p) } },
                findOpen = findOpen, onFindOpen = { findOpen = it }, tocOpen = tocOpen, onTocOpen = { tocOpen = it },
                onChrome = onChrome)
        }
        // Hidden while reading: a small handle at the top brings the tabs and bar back.
        ChromeHandle(visible = !showBar, onShow = { onChrome(true) })

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
                .border(if (chosen) 2.dp else 1.dp, if (chosen) Amber else Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) { Text("Aa", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.ink) }
        Text(t.label, fontSize = 12.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
            color = if (chosen) Amber else TextDim)
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

@Composable
private fun GuideText(
    file: File, theme: GuideTheme, textSize: Int, wraps: Boolean, reflow: Boolean,
    startAt: Int, onPosition: (Int) -> Unit,
    findOpen: Boolean, onFindOpen: (Boolean) -> Unit, tocOpen: Boolean, onTocOpen: (Boolean) -> Unit,
    onChrome: (Boolean) -> Unit,
) {
    val raw = remember(file) { runCatching { file.readText() }.getOrNull() }
    // Reading down hides the tabs and the bar for room. Scrolling back up doesn't bring them back
    // (that got in the way of just reading, found on device): the handle at the top does, and so
    // does reaching the very top of the guide.
    val chromeOnScroll = remember(onChrome) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < -6f) onChrome(false)
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    // Reflowed to the screen when asked, rejoining hard-wrapped prose; find, contents and the
    // saved position all work on the text as shown.
    val text = remember(raw, reflow) { raw?.let { if (reflow) reflowGuide(it) else it } }
    val wrapping = reflow || wraps
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val topPadPx = with(density) { 10.dp.toPx() }

    suspend fun scrollToChar(offset: Int) {
        val l = layout ?: return
        val length = text?.length ?: return
        val box = runCatching { l.getBoundingBox(offset.coerceIn(0, (length - 1).coerceAtLeast(0))) }.getOrNull() ?: return
        runCatching { scroll.scrollTo((box.top + topPadPx).toInt().coerceIn(0, scroll.maxValue)) }
    }

    // Find: every match of the query (two characters or more), and which is current.
    var query by remember(text) { mutableStateOf("") }
    var current by remember(text) { mutableIntStateOf(0) }
    val matches = remember(text, query) {
        val t = text
        if (t == null || query.length < 2) emptyList() else buildList {
            var i = t.indexOf(query, 0, ignoreCase = true)
            while (i >= 0) { add(i); i = t.indexOf(query, i + query.length, ignoreCase = true) }
        }
    }
    LaunchedEffect(matches) { if (current >= matches.size) current = 0 }
    LaunchedEffect(current, matches, layout) { matches.getOrNull(current)?.let { scrollToChar(it) } }

    // Section headings for Contents (a plain-text FAQ has no structure, so it's a heuristic).
    val headings = remember(text) {
        val t = text ?: return@remember emptyList<Pair<String, Int>>()
        buildList {
            var offset = 0
            t.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (isGuideHeading(trimmed)) add(trimmed to offset)
                offset += line.length + 1
            }
        }
    }

    // Your place is kept as a fraction of the whole (ten-thousandths), not a pixel offset, so it
    // survives changing the text size. Restored once the text has been measured.
    var restored by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file, textSize, wrapping, text) {
        if (text == null) return@LaunchedEffect
        try {
            repeat(20) {
                val max = scroll.maxValue
                if (max > 0) { runCatching { scroll.scrollTo((startAt / 10_000f * max).toInt()) }; return@LaunchedEffect }
                kotlinx.coroutines.delay(16)
            }
        } finally { restored = true }
    }
    LaunchedEffect(scroll.value == 0) { if (scroll.value == 0) onChrome(true) }
    LaunchedEffect(scroll.value, scroll.maxValue, restored) {
        val max = scroll.maxValue
        if (restored && max > 0) onPosition((scroll.value.toFloat() / max * 10_000f).toInt().coerceIn(0, 10_000))
    }

    val display: AnnotatedString = remember(text, query, current, matches, theme) {
        val t = text ?: return@remember AnnotatedString("This guide couldn't be read.")
        if (matches.isEmpty()) AnnotatedString(t) else buildAnnotatedString {
            append(t)
            matches.forEachIndexed { i, off ->
                addStyle(SpanStyle(background = if (i == current) Amber else Amber.copy(alpha = 0.33f),
                    color = if (i == current) Color(0xFF101010) else theme.ink), off, (off + query.length).coerceAtMost(t.length))
            }
        }
    }

    // Text guides keep a monospace face: they're drawn as ASCII maps and tables to a fixed column.
    val body = @Composable {
        Text(display, color = theme.ink, fontFamily = FontFamily.Monospace, fontSize = textSize.sp,
            lineHeight = (textSize * 1.35f).sp, softWrap = wrapping, onTextLayout = { layout = it },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
    }

    Box(Modifier.fillMaxSize().background(theme.paper)) {
        Box(Modifier.fillMaxSize().nestedScroll(chromeOnScroll).verticalScroll(scroll)) {
            if (wrapping) body() else Box(Modifier.horizontalScroll(rememberScrollState())) { body() }
        }
        if (findOpen) {
            AllowTyping(true)
            FindBar(query, { query = it; current = 0 }, matches.size, if (matches.isEmpty()) 0 else current + 1,
                onPrev = { if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size },
                onNext = { if (matches.isNotEmpty()) current = (current + 1) % matches.size },
                onClose = { onFindOpen(false); query = "" })
        }
        if (tocOpen) {
            Box(Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { onTocOpen(false) }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Contents", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Pill("Close", false) { onTocOpen(false) }
                    }
                    if (headings.isEmpty()) Text("No sections found in this guide.", color = TextFaint, fontSize = 13.sp)
                    headings.forEach { (title, offset) ->
                        Text(title, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .clickable { onTocOpen(false); scope.launch { scrollToChar(offset) } }
                                .padding(horizontal = 8.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FindBar(query: String, onQuery: (String) -> Unit, total: Int, current: Int,
                    onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xF2141414)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A)).padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (query.isEmpty()) Text("Find in guide", color = TextFaint, fontSize = 13.sp)
            BasicTextField(query, onQuery, singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp, fontFamily = JoeyFont),
                cursorBrush = SolidColor(Amber), modifier = Modifier.fillMaxWidth())
        }
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
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Find in page", color = TextFaint, fontSize = 13.sp)
                    BasicTextField(query, { query = it; web?.findAllAsync(it) }, singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp, fontFamily = JoeyFont),
                        cursorBrush = SolidColor(Amber), modifier = Modifier.fillMaxWidth())
                }
                Text(if (query.isBlank()) "" else "$matches", color = TextDim, fontSize = 12.sp)
                Pill("‹", false) { web?.findNext(false) }
                Pill("›", false) { web?.findNext(true) }
            }
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
                    setFindListener { _, n, _ -> matches = n }
                    webViewClient = object : WebViewClient() {
                        var restored = false
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (reader) view.evaluateJavascript(ReaderScript, null)
                            // Back to where you were, once the page has laid out.
                            if (!restored) view.postDelayed({
                                restored = true
                                val at = prefs.getInt(guidePosKey(file), 0)
                                if (at > 0) view.scrollTo(0, (at / 10_000f * view.scrollRange()).toInt())
                            }, 400)
                        }
                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail) =
                            webGone(view, detail) { webGen++ }
                        // Offline: nothing from the web. A web archive's own parts (cid:, data:)
                        // and the file itself load.
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                            val scheme = request.url?.scheme.orEmpty().lowercase()
                            return if (scheme == "http" || scheme == "https") android.webkit.WebResourceResponse("text/plain", "utf-8", null)
                            else super.shouldInterceptRequest(view, request)
                        }
                    }
                    hideChromeOnWebScroll(this, onChrome) { y ->
                        val range = scrollRange()
                        if (range > 0)
                            prefs.edit().putInt(guidePosKey(file), (y.toFloat() / range * 10_000f).toInt().coerceIn(0, 10_000)).apply()
                    }
                    loadUrl(android.net.Uri.fromFile(file).toString())
                    web = this
                }
            },
            onRelease = { it.destroy() }
        ) }
    }
    ChromeHandle(visible = !(chrome || findOpen), onShow = { onChrome(true) })
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
    s.innerHTML='html,body{background:#0B0D12!important;color:#F3EFE4!important;margin:0;padding:14px;font-family:sans-serif;line-height:1.55;font-size:16px}#rdr img{max-width:100%;height:auto}a{color:#FFB000}h1,h2,h3,h4{color:#fff}table{max-width:100%}';
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
