package com.joeyos.app.ui.components

import com.joeyos.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.joeyos.app.AppLog
import com.joeyos.app.data.RAAchievement
import com.joeyos.app.data.InstalledApp
import com.joeyos.app.data.loadInstalledApps
import com.joeyos.app.data.GuideSource
import com.joeyos.app.data.GuideTarget
import com.joeyos.app.data.Guides
import com.joeyos.app.data.RAComment
import com.joeyos.app.data.RANowPlaying
import com.joeyos.app.data.RAGameProgress
import com.joeyos.app.data.RARecentGame
import com.joeyos.app.data.RAResult
import com.joeyos.app.data.RetroAchievementsRepository
import com.joeyos.app.data.SecondScreenPrefs
import com.joeyos.app.data.SecondScreenState
import com.joeyos.app.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * What the second screen shows. Touch only — the controller always stays with the home screen or
 * the game — so everything here is a tap: the tabs, a game to open its achievements, a hidden
 * achievement to reveal it.
 *
 *  - Overview: your RetroAchievements summary. Tap a game to see its achievement list.
 *  - Now playing (while a game runs): switches here as soon as JoeyOS starts a game, then shows
 *    the achievements of the game RetroAchievements sees you playing, with its live status,
 *    refreshed every minute so new unlocks show up. Back home: back to the overview.
 */
@Composable
fun SecondScreenContent() {
    val context = LocalContext.current
    val raRepo = remember { RetroAchievementsRepository(context) }
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }

    var enabled by remember { mutableStateOf(SecondScreenPrefs.enabled(context)) }
    var configured by remember { mutableStateOf(raRepo.isConfigured) }
    var awards by remember { mutableStateOf<RAResult?>(null) }
    var recent by remember { mutableStateOf<List<RARecentGame>>(emptyList()) }
    var points by remember { mutableStateOf<Int?>(null) }

    val session by SecondScreenState.session.collectAsState()
    var nowPlaying by remember { mutableStateOf<RANowPlaying?>(null) }   // what RA sees you playing
    var lastGameId by remember { mutableStateOf<Int?>(null) }            // the previous session's game
    var openGame by remember { mutableStateOf<Int?>(null) }              // a game opened from the overview
    // 0 achievements, 1 now playing, 2 guide, 3 settings, 4 apps. Browsing JoeyOS opens on the
    // tab chosen in this screen's Settings (Apps by default).
    // Without a RetroAchievements login there are no Achievements or Now playing tabs at all.
    fun homeTab() = if (SecondScreenPrefs.homeTabIsApps(context) || !raRepo.isConfigured) 4 else 0
    var tab by remember { mutableIntStateOf(homeTab()) }
    // Logged out of RetroAchievements while on one of its tabs: go to Apps.
    LaunchedEffect(configured) { if (!configured && (tab == 0 || tab == 1)) tab = 4 }
    var chrome by remember { mutableStateOf(true) }                      // tabs shown (hidden while reading a guide)
    var wantGuide by remember { mutableStateOf(false) }                  // open the Guide once the game is known
    LaunchedEffect(tab) { chrome = true }
    var guideSearch by remember { mutableStateOf<GuideSource?>(null) }   // "find a guide for this achievement"
    // RA's own title and console for the game, which name its guide and say where to look for one.
    val raGame by produceState<RAGameProgress?>(null, nowPlaying?.gameId) {
        value = nowPlaying?.gameId?.let { raRepo.fetchGameProgress(it, maxAgeMs = 10 * 60_000) }
    }
    LaunchedEffect(guideTargetKey(session, raGame), wantGuide) {
        if (wantGuide && tab == 1 && session != null && (raGame != null || session?.title != null)) tab = 2
    }
    val guideTarget = remember(session, raGame) {
        val s = session ?: return@remember null
        val titles = listOfNotNull(raGame?.title?.takeIf { it.isNotBlank() }, s.title).distinct()
        if (titles.isEmpty()) null
        else GuideTarget(titles, raGame?.consoleName?.takeIf { it.isNotBlank() }, raGame?.consoleId?.takeIf { it > 0 }, s.romPath)
    }

    // Refresh while showing: on return from a game (resume) and every 10 minutes. The repository
    // caches (awards a day, recently played half an hour), so this rarely touches the network.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                enabled = SecondScreenPrefs.enabled(context)
                configured = raRepo.isConfigured
                if (configured) {
                    awards = raRepo.fetchAwards()
                    recent = raRepo.fetchRecentlyPlayed()
                    points = raRepo.fetchPoints()
                } else { awards = null; recent = emptyList(); points = null }
                delay(10 * 60_000L)
            }
        }
    }

    // A game started: switch to Now playing at once (named, when JoeyOS knows the name), then ask
    // RetroAchievements what's being played — every 5 seconds for the first two minutes, since the
    // answer arrives as soon as the emulator has signed in and loaded the game, then every 30 for
    // its live status line. Back home: back to the overview.
    LaunchedEffect(session, configured) {
        val s = session
        if (s == null) { nowPlaying?.let { lastGameId = it.gameId }; nowPlaying = null; tab = homeTab(); return@LaunchedEffect }
        nowPlaying?.let { lastGameId = it.gameId }
        // Open on Now playing, or the Guide if chosen in this screen's Settings. A game started
        // inside RetroArch has no name until RetroAchievements reports it, so then the Guide opens
        // as soon as it does (found on device: it stayed on Now playing).
        wantGuide = SecondScreenPrefs.openGuideOnLaunch(context)
        tab = when {
            !configured -> if (s.title != null) 2 else homeTab()   // no Now playing without RA
            wantGuide && s.title != null -> 2
            else -> 1
        }
        openGame = null; nowPlaying = null
        if (!configured) return@LaunchedEffect
        while (true) {
            raRepo.fetchNowPlaying(s.startedAt, lastGameId)?.let { np ->
                if (np.gameId != nowPlaying?.gameId) AppLog.i("SecondScreen", "Now playing: RetroAchievements game ${np.gameId}")
                nowPlaying = np
            }
            val fast = System.currentTimeMillis() - s.startedAt < 2 * 60_000L
            delay(if (nowPlaying == null && fast) 5_000L else 30_000L)
        }
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        when {
            !enabled -> Message("Second screen off", "Turn it on in Settings › Appearance › Second screen.")
            else -> Column(Modifier.fillMaxSize()) {
                // Tabs, or a back button over an opened game. Hidden while reading a guide.
                if (chrome || tab != 2) Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (openGame != null) {
                        Pill("‹  Back", active = false) { openGame = null }
                    } else {
                        // A tab you pick yourself wins over "open the Guide when a game starts".
                        Pill("Apps", active = tab == 4) { tab = 4; wantGuide = false }
                        if (configured) Pill("Achievements", active = tab == 0) { tab = 0; wantGuide = false }
                        Pill("Settings", active = tab == 3) { tab = 3; wantGuide = false }
                        if (session != null && configured) Pill("Now playing", active = tab == 1) { tab = 1; wantGuide = false }
                        if (session != null && guideTarget != null) Pill("Guide", active = tab == 2) { tab = 2; wantGuide = false }
                    }
                }
                Box(Modifier.weight(1f)) {
                    val s = session
                    val np = nowPlaying
                    when {
                        openGame != null -> GameAchievements(openGame!!, raRepo, live = null)
                        tab == 3 -> SecondScreenSettings()
                        tab == 4 -> SecondScreenApps()
                        // Logged out while on an RA tab: back to Apps.
                        (tab == 0 || tab == 1) && !configured -> SecondScreenApps()
                        tab == 2 && guideTarget != null -> GuideTab(guideTarget, guideSearch, onSearchShown = { guideSearch = null },
                            chrome = chrome, onChrome = { chrome = it })
                        tab == 1 && s != null && np != null -> GameAchievements(np.gameId, raRepo, live = LiveInfo(s, np),
                            onFindGuide = if (guideTarget != null) { q -> guideSearch = Guides.searchFor(q); tab = 2 } else null)
                        tab == 1 && s != null -> Message(s.title ?: "Now playing",
                            "Waiting for RetroAchievements to see the game. This works with emulators signed in to " +
                                "RetroAchievements, a few seconds after the game has loaded.")
                        else -> Overview(awards, recent, points, raRepo.username, currentYear) { openGame = it }
                    }
                }
            }
        }
    }
}

/**
 * Every installed app, A to Z. Tapping one opens it on this screen, beside the game or the home
 * screen. An app opened here takes the controller (it's an ordinary app); a tap on the other
 * screen gives it back.
 */
@Composable
private fun SecondScreenApps() {
    val context = LocalContext.current
    val apps by produceState<List<InstalledApp>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadInstalledApps(context) }
    }
    val list = apps
    if (list == null) { Message("Apps", "Loading…"); return }
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 84.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(list.size, key = { list[it].packageName }) { i ->
            val app = list[i]
            AppGridItem(app, onClick = { openHere(context, app) })
        }
    }
}

/** Opens an app on the second screen (the display this screen is on). */
private fun openHere(context: android.content.Context, app: InstalledApp) {
    // JoeyOS itself: bring its home screen forward on the main screen, never onto this one (home
    // on the bottom screen is what put the second screen on the wrong display, found in a log).
    if (app.packageName == context.packageName) {
        runCatching {
            context.startActivity(android.content.Intent(context, com.joeyos.app.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                com.joeyos.app.data.DisplayTargets.optionsFor(android.view.Display.DEFAULT_DISPLAY))
        }
        return
    }
    val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
    runCatching {
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            com.joeyos.app.data.DisplayTargets.optionsFor(com.joeyos.app.data.DisplayTargets.currentDisplayId(context)))
        AppLog.i("SecondScreen", "Opened ${app.packageName} on the second screen")
    }.onFailure { AppLog.w("SecondScreen", "Couldn't open ${app.packageName} on the second screen", it) }
}

/** The second screen's own settings: what it opens when a game starts, and achievement spoilers. */
@Composable
private fun SecondScreenSettings() {
    val context = LocalContext.current
    var openGuide by remember { mutableStateOf(SecondScreenPrefs.openGuideOnLaunch(context)) }
    var homeApps by remember { mutableStateOf(SecondScreenPrefs.homeTabIsApps(context)) }
    var hideSpoilers by remember { mutableStateOf(SecondScreenPrefs.hideSpoilers(context)) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ChoiceRow("While browsing JoeyOS, show", listOf(true to "Apps", false to "Achievements"), homeApps, { v ->
            homeApps = v; SecondScreenPrefs.setHomeTabIsApps(context, v)
            AppLog.i("SecondScreen", "Browsing tab: ${if (v) "Apps" else "Achievements"}")
        })
        ChoiceRow("When a game starts, show", listOf(false to "Now playing", true to "Guide"), openGuide, { v ->
            openGuide = v; SecondScreenPrefs.setOpenGuideOnLaunch(context, v)
            AppLog.i("SecondScreen", "Opens on ${if (v) "Guide" else "Now playing"} when a game starts")
        })
        ToggleRow("Hide achievement spoilers",
            "A locked achievement's name and description stay hidden until you tap it.",
            hideSpoilers, { on -> hideSpoilers = on; SecondScreenPrefs.setHideSpoilers(context, on) })
        Text("The rest of the second screen's settings are on the main screen: Settings › Appearance › Second screen.",
            fontSize = 10.sp, color = TextFaint)
    }
}

/** Changes when the game to find a guide for becomes known (its launch name, or RA's game). */
private fun guideTargetKey(s: SecondScreenState.Session?, ra: RAGameProgress?) = listOf(s?.startedAt, s?.title, ra?.gameId)

/** For the game being played: when it started and RA's live view of it. */
private data class LiveInfo(val session: SecondScreenState.Session, val nowPlaying: RANowPlaying)

@Composable
private fun Overview(
    awards: RAResult?,
    recent: List<RARecentGame>,
    points: Int?,
    username: String,
    currentYear: Int,
    onOpenGame: (Int) -> Unit
) {
    val ra = (awards as? RAResult.Success)?.data
    when {
        awards is RAResult.Error -> { Message("RetroAchievements", awards.message); return }
        ra == null -> { Message("RetroAchievements", "Loading…"); return }
    }
    ra!!
    val beaten = ra.awards.filter { !it.isFinished }
    val playing = remember(ra, recent) {
        val awarded = ra.awards.map { it.gameId }.toSet()
        recent.filter { it.gameId !in awarded && it.numPossible > 0 && it.numAchieved < it.numPossible }.take(4)
    }
    val latest = ra.awards.take(4)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 600.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("RetroAchievements", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                    modifier = Modifier.weight(1f))
                Text("$username  ·  updated ${agoText(ra.fetchedAt)}", fontSize = 10.sp,
                    fontFamily = JoeyFont, color = TextFaint)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("${beaten.count { yearOf(it.awardedAt) == currentYear }}", "beaten in $currentYear", Amber, Modifier.weight(1f), big = wide)
                StatTile("${ra.beatenHardcoreAwardsCount + ra.beatenSoftcoreAwardsCount}", "beaten all-time", Amber, Modifier.weight(1f), big = wide)
                finishedTotal(ra.awards).let { (n, label) -> StatTile("$n", label, MasteredColor, Modifier.weight(1f), big = wide) }
                StatTile(points?.let { "%,d".format(it) } ?: "—", "points", RaColor, Modifier.weight(1f), big = wide)
            }
            val playingCol: @Composable (Modifier) -> Unit = { m ->
                GameColumn("Currently playing", "Nothing on the go.", playing.isEmpty(), m) {
                    playing.forEach { g -> GameRow(g.entry(), onClick = { onOpenGame(g.gameId) }) }
                }
            }
            val latestCol: @Composable (Modifier) -> Unit = { m ->
                GameColumn("Recent awards", "No awards yet.", latest.isEmpty(), m) {
                    latest.forEach { a -> GameRow(a.entry(), onClick = { onOpenGame(a.gameId) }) }
                }
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                playingCol(Modifier.weight(1f)); latestCol(Modifier.weight(1f))
            } else { playingCol(Modifier); latestCol(Modifier) }
            Text("Tap a game to see its achievements.", fontSize = 9.sp, fontFamily = JoeyFont, color = TextFaint)
        }
    }
}

@Composable
private fun GameColumn(title: String, empty: String, isEmpty: Boolean, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(title)
        if (isEmpty) Text(empty, fontSize = 11.sp, fontFamily = JoeyFont, color = TextFaint)
        else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

private enum class AchFilter(val label: String) { All("All"), Locked("Locked"), Earned("Earned"), Missable("Missable"), ToBeat("To beat") }
private enum class AchSort(val label: String) { Default("Default"), Easiest("Easiest"), Points("Points"), Recent("Recent") }

/**
 * A game's achievement list: your progress, the filters and sorts, and each achievement (tap it
 * for its details and comments). With [live] (the game you're playing) it also shows RA's live
 * status line, the session time and your total playtime, refreshes every minute, and flashes an
 * "Unlocked!" banner when a new one comes in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameAchievements(gameId: Int, raRepo: RetroAchievementsRepository, live: LiveInfo?,
                             onFindGuide: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val hideSpoilers = remember(gameId) { SecondScreenPrefs.hideSpoilers(context) }
    var game by remember(gameId) { mutableStateOf<RAGameProgress?>(null) }
    var failed by remember(gameId) { mutableStateOf(false) }
    var filter by remember(gameId) { mutableStateOf(AchFilter.All) }
    var sort by remember(gameId) { mutableStateOf(AchSort.Default) }
    val revealed = remember(gameId) { mutableStateListOf<Int>() }
    var detail by remember(gameId) { mutableStateOf<RAAchievement?>(null) }
    var banner by remember(gameId) { mutableStateOf<RAAchievement?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(gameId, live != null) {
        var known: Set<Int>? = null
        while (true) {
            val g = raRepo.fetchGameProgress(gameId, maxAgeMs = if (live != null) 55_000 else 5 * 60_000)
            if (g != null) {
                val earned = g.achievements.filter { it.earned }.map { it.id }.toSet()
                // A new unlock since the last look: show it for a few seconds.
                if (live != null && known != null) g.achievements.firstOrNull { it.earned && it.id !in known!! }?.let { banner = it }
                known = earned
                game = g
            } else if (game == null) failed = true
            if (live == null) break
            delay(60_000L)
        }
    }
    LaunchedEffect(banner) { if (banner != null) { delay(6_000L); banner = null } }
    // The session clock ticks every half minute.
    if (live != null) LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000L) } }

    detail?.let { a -> game?.let { g -> AchievementDetail(a, g, raRepo, onFindGuide) { detail = null } }; return }

    val g = game
    if (g == null) { Message(live?.session?.title ?: "Achievements", if (failed) "Couldn't load this game's achievements." else "Loading…"); return }
    val shown = remember(g, filter, sort) {
        g.achievements
            .filter {
                when (filter) {
                    AchFilter.All -> true
                    AchFilter.Locked -> !it.earned
                    AchFilter.Earned -> it.earned
                    AchFilter.Missable -> it.isMissable
                    AchFilter.ToBeat -> it.isToBeat
                }
            }
            .let { l ->
                when (sort) {
                    AchSort.Default -> l
                    AchSort.Easiest -> l.sortedByDescending { it.numAwarded }
                    AchSort.Points -> l.sortedByDescending { it.points }
                    AchSort.Recent -> l.sortedWith(compareByDescending<RAAchievement> { it.dateEarned?.time ?: 0L })
                }
            }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { GameHeader(g, live, now) }
            if (g.achievements.isEmpty()) {
                item { Text("This game has no achievements yet.", fontSize = 11.sp, fontFamily = JoeyFont, color = TextFaint) }
            } else {
                item {
                    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Wrapping rows, not sideways-scrolling ones: on the Thor's narrow bottom screen
                        // "To beat" sat off the right edge where nobody would find it (found on device).
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AchFilter.entries.forEach { f ->
                                val n = when (f) {
                                    AchFilter.All -> g.achievements.size
                                    AchFilter.Locked -> g.achievements.size - g.earnedCount
                                    AchFilter.Earned -> g.earnedCount
                                    AchFilter.Missable -> g.achievements.count { it.isMissable }
                                    AchFilter.ToBeat -> g.toBeat.size
                                }
                                if (n > 0 || f == AchFilter.All) Pill("${f.label} $n", filter == f) { filter = f }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Sort", fontSize = 10.sp, fontFamily = JoeyFont, color = TextFaint,
                                modifier = Modifier.align(Alignment.CenterVertically))
                            AchSort.entries.forEach { s -> Pill(s.label, sort == s) { sort = s } }
                        }
                    }
                }
                if (shown.isEmpty()) item { Text("Nothing here.", fontSize = 11.sp, fontFamily = JoeyFont, color = TextFaint) }
                items(shown, key = { it.id }) { a ->
                    val hidden = hideSpoilers && !a.earned && a.id !in revealed
                    AchievementRow(a, g.numDistinctPlayers, hidden) { if (hidden) revealed.add(a.id) else detail = a }
                }
            }
        }
        banner?.let { a ->
            Row(
                Modifier.align(Alignment.TopCenter).padding(12.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF14311F)).border(1.dp, VerifiedColor, RoundedCornerShape(14.dp))
                    .clickable { banner = null; detail = a }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(model = a.badgeUrl, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)))
                Column {
                    Text("Unlocked!", fontSize = 10.sp, fontFamily = JoeyFont, fontWeight = FontWeight.Bold, color = VerifiedColor)
                    Text("${a.title}  +${a.points}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

/** The game's icon, name and your progress; for the game being played, its live status too. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameHeader(g: RAGameProgress, live: LiveInfo?, now: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(g.iconUrl, g.title, 56.dp)
            Column(Modifier.weight(1f)) {
                Text(g.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(g.consoleName, g.genre, g.released?.take(4)).joinToString("  ·  "),
                    fontSize = 10.sp, fontFamily = JoeyFont, color = TextFaint, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (g.achievements.isEmpty()) 0f else g.earnedCount.toFloat() / g.achievements.size },
                    color = RaColor, trackColor = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                )
            }
        }
        // RA's live line for this session ("Stage 3-2 · 4 lives"), when the game has one.
        live?.nowPlaying?.richPresence?.takeIf { it.isNotBlank() }?.let { rp ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Amber.copy(alpha = 0.10f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                JoeyIcon(R.drawable.ic_play_arrow, Amber, 16.dp)
                Text(rp, fontSize = 12.sp, color = Amber, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val status = when (g.highestAward) {
            "mastered" -> "Mastered"; "completed" -> "Completed"
            "beaten-hardcore" -> "Beaten (hardcore)"; "beaten-softcore" -> "Beaten"
            else -> null
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (g.toBeat.isNotEmpty()) MiniStat("${g.toBeat.count { it.earned }}/${g.toBeat.size}", "to beat")
            MiniStat("${g.earnedCount}/${g.achievements.size}", "earned")
            MiniStat("${g.earnedPoints}/${g.totalPoints}", "points")
            if (g.earnedHardcoreCount > 0) MiniStat("${g.earnedHardcoreCount}", "hardcore")
            live?.let { MiniStat(formatDuration((now - it.session.startedAt) / 1000), "this session") }
            if (g.userPlaytimeSeconds > 0) MiniStat(formatDuration(g.userPlaytimeSeconds.toLong()), "played")
            status?.let { MiniStat(it, "status") }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
        Text(label, fontSize = 9.sp, fontFamily = JoeyFont, color = TextFaint)
    }
}

@Composable
private fun AchievementRow(a: RAAchievement, players: Int, hidden: Boolean, onTap: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Color.White.copy(alpha = if (a.earned) 0.06f else 0.03f))
            .border(1.dp, if (a.earned) RaColor.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f), shape)
            .clickable(onClick = onTap)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = if (a.earned) a.badgeUrl else a.lockedBadgeUrl, contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.05f))
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (hidden) "Locked achievement" else a.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (a.earned) TextPrimary else TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (a.isMissable) Tag("MISSABLE", WarnColor)
                if (a.isToBeat) Tag(if (a.type == "win_condition") "WIN" else "STORY", RaColor)
            }
            Text(if (hidden) "Tap to reveal" else a.description, fontSize = 10.sp, fontFamily = JoeyFont,
                color = TextFaint, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val rarity = if (players > 0) "${(a.numAwarded * 100f / players).roundToInt()}% of players" else ""
            val earned = a.dateEarned?.let { "earned ${dayFmt().format(it)}" + if (a.earnedHardcore) " (hardcore)" else "" }
            Text(listOfNotNull(rarity.takeIf { it.isNotEmpty() }, earned).joinToString("  ·  "), fontSize = 9.sp,
                fontFamily = JoeyFont, color = if (a.earned) VerifiedColor else TextFaint)
        }
        Text("${a.points}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (a.earned) Amber else TextFaint,
            modifier = Modifier.alpha(if (a.earned) 1f else 0.7f))
    }
}

/** One achievement in full, with its RetroAchievements comments (where players post tips). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AchievementDetail(a: RAAchievement, g: RAGameProgress, raRepo: RetroAchievementsRepository,
                              onFindGuide: ((String) -> Unit)?, onBack: () -> Unit) {
    val comments by produceState<List<RAComment>?>(null, a.id) { value = raRepo.fetchAchievementComments(a.id) }
    val players = g.numDistinctPlayers
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Pill("‹  ${g.title}", active = false, onClick = onBack) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AsyncImage(model = if (a.earned) a.badgeUrl else a.lockedBadgeUrl, contentDescription = null,
                    modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(a.description, fontSize = 12.sp, color = TextDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (a.isMissable) Tag("MISSABLE", WarnColor)
                        if (a.isToBeat) Tag(if (a.type == "win_condition") "WIN CONDITION" else "PROGRESSION", RaColor)
                    }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniStat("${a.points}", "points")
                if (a.trueRatio > 0) MiniStat("${a.trueRatio}", "RetroPoints")
                if (players > 0) MiniStat("${(a.numAwarded * 100f / players).roundToInt()}%", "of players")
                if (players > 0 && a.numAwardedHardcore > 0) MiniStat("${(a.numAwardedHardcore * 100f / players).roundToInt()}%", "hardcore")
                a.dateEarned?.let { MiniStat(dayFmt().format(it), if (a.earnedHardcore) "earned (hardcore)" else "earned") }
            }
        }
        // Stuck on it: search for this one achievement, in the Guide tab's browser.
        onFindGuide?.let { find ->
            item { Pill("Find a guide for this", active = false) { find("${g.title} ${a.title} achievement") } }
        }
        item { SectionLabel("Comments" + (comments?.let { "  ·  ${it.size}" } ?: ""), Modifier.padding(top = 6.dp)) }
        val list = comments
        when {
            list == null -> item { Text("Loading…", fontSize = 11.sp, fontFamily = JoeyFont, color = TextFaint) }
            list.isEmpty() -> item { Text("No comments yet.", fontSize = 11.sp, fontFamily = JoeyFont, color = TextFaint) }
            else -> items(list) { c ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.04f))
                        .padding(10.dp)
                ) {
                    Text(c.user + (c.submitted?.let { "  ·  ${dayFmt().format(it)} ${yearOf(it)}" } ?: ""),
                        fontSize = 10.sp, fontFamily = JoeyFont, color = Amber)
                    Text(c.text, fontSize = 12.sp, color = TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(text, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = JoeyFont, color = color,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 2.dp))
}

/** A tappable tab / filter: soft amber fill when it's the current one. */
@Composable
internal fun Pill(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label, fontSize = 12.sp, fontFamily = JoeyFont, fontWeight = FontWeight.SemiBold,
        color = if (active) Amber else TextDim,
        modifier = Modifier.clip(shape)
            .background(if (active) Amber.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (active) AmberSoft else Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun Message(title: String, text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Amber)
        Spacer(Modifier.height(8.dp))
        Text(text, fontSize = 12.sp, fontFamily = JoeyFont, color = TextDim, textAlign = TextAlign.Center)
    }
}
