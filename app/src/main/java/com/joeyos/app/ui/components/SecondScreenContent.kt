package com.joeyos.app.ui.components

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
 *  - Now playing (while a game runs): the achievements of the game RetroAchievements sees you
 *    playing, refreshed every minute so new unlocks show up. It switches here by itself once RA
 *    has seen the game, and back to the overview when you're home.
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

    val gameStartedAt by SecondScreenState.gameStartedAt.collectAsState()
    var nowPlaying by remember { mutableStateOf<Int?>(null) }   // RA game id of the running game
    var openGame by remember { mutableStateOf<Int?>(null) }     // a game opened from the overview
    var tab by remember { mutableIntStateOf(0) }                // 0 overview, 1 now playing

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

    // While a game runs, ask RetroAchievements what's being played until it says, then follow it
    // (it can change if you switch games without coming home). Back home: back to the overview.
    LaunchedEffect(gameStartedAt, configured) {
        val since = gameStartedAt
        if (since == null || !configured) { nowPlaying = null; tab = 0; return@LaunchedEffect }
        while (true) {
            val id = raRepo.fetchNowPlaying(since)
            if (id != null && id != nowPlaying) {
                AppLog.i("SecondScreen", "Now playing: RetroAchievements game $id")
                nowPlaying = id; tab = 1; openGame = null
            }
            delay(if (nowPlaying == null) 20_000L else 60_000L)
        }
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        when {
            !enabled -> Message("Second screen off", "Turn it on in Settings › Appearance › Second screen.")
            !configured -> Message(
                "JoeyOS",
                "Connect RetroAchievements in Settings › Achievements › Account to see your progress here."
            )
            else -> Column(Modifier.fillMaxSize()) {
                // Tabs, or a back button over an opened game.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (openGame != null) {
                        Pill("‹  Back", active = false) { openGame = null }
                    } else {
                        Pill("Overview", active = tab == 0) { tab = 0 }
                        if (gameStartedAt != null) Pill("Now playing", active = tab == 1) { tab = 1 }
                    }
                }
                Box(Modifier.weight(1f)) {
                    when {
                        openGame != null -> GameAchievements(openGame!!, raRepo, live = false)
                        tab == 1 && nowPlaying != null -> GameAchievements(nowPlaying!!, raRepo, live = true)
                        tab == 1 -> Message("Now playing",
                            "Waiting for RetroAchievements to see the game. This works with emulators signed in to " +
                                "RetroAchievements, and can take up to a minute after the game starts.")
                        else -> Overview(awards, recent, points, raRepo.username, currentYear) { openGame = it }
                    }
                }
            }
        }
    }
}

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
                    fontFamily = FontFamily.Monospace, color = TextFaint)
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
            Text("Tap a game to see its achievements.", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
        }
    }
}

@Composable
private fun GameColumn(title: String, empty: String, isEmpty: Boolean, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(title)
        if (isEmpty) Text(empty, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
        else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/**
 * A game's achievement list: header with your progress, All / Locked / Earned, and each
 * achievement with its badge, points, how many players have it, and a MISSABLE tag. [live]
 * refreshes it every minute (the game you're playing), so new unlocks appear.
 */
@Composable
private fun GameAchievements(gameId: Int, raRepo: RetroAchievementsRepository, live: Boolean) {
    val context = LocalContext.current
    val hideSpoilers = remember { SecondScreenPrefs.hideSpoilers(context) }
    var game by remember(gameId) { mutableStateOf<RAGameProgress?>(null) }
    var failed by remember(gameId) { mutableStateOf(false) }
    var filter by remember(gameId) { mutableIntStateOf(0) }   // 0 all, 1 locked, 2 earned
    val revealed = remember(gameId) { mutableStateListOf<Int>() }

    LaunchedEffect(gameId, live) {
        while (true) {
            val g = raRepo.fetchGameProgress(gameId, maxAgeMs = if (live) 55_000 else 5 * 60_000)
            if (g != null) game = g else if (game == null) failed = true
            if (!live) break
            delay(60_000L)
        }
    }

    val g = game
    if (g == null) { Message("Achievements", if (failed) "Couldn't load this game's achievements." else "Loading…"); return }
    val shown = when (filter) {
        1 -> g.achievements.filter { !it.earned }
        2 -> g.achievements.filter { it.earned }.sortedByDescending { it.dateEarned }
        else -> g.achievements
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(g.iconUrl, g.title, 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(g.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text("${g.consoleName}  ·  ${g.earnedCount}/${g.achievements.size} earned  ·  ${g.earnedPoints}/${g.totalPoints} points",
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (g.achievements.isEmpty()) 0f else g.earnedCount.toFloat() / g.achievements.size },
                        color = RaColor, trackColor = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                    )
                }
            }
        }
        if (g.achievements.isEmpty()) {
            item { Text("This game has no achievements yet.", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextFaint) }
        } else {
            item {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("All ${g.achievements.size}", filter == 0) { filter = 0 }
                    Pill("Locked ${g.achievements.size - g.earnedCount}", filter == 1) { filter = 1 }
                    Pill("Earned ${g.earnedCount}", filter == 2) { filter = 2 }
                }
            }
            items(shown, key = { it.id }) { a ->
                val hidden = hideSpoilers && !a.earned && a.id !in revealed
                AchievementRow(a, g.numDistinctPlayers, hidden) { if (hidden) revealed.add(a.id) }
            }
        }
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
            }
            Text(if (hidden) "Tap to reveal" else a.description, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = TextFaint, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val rarity = if (players > 0) "${(a.numAwarded * 100f / players).roundToInt()}% of players" else ""
            val earned = a.dateEarned?.let { "earned ${dayFmt().format(it)}" + if (a.earnedHardcore) " (hardcore)" else "" }
            Text(listOfNotNull(rarity.takeIf { it.isNotEmpty() }, earned).joinToString("  ·  "), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, color = if (a.earned) VerifiedColor else TextFaint)
        }
        Text("${a.points}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (a.earned) Amber else TextFaint,
            modifier = Modifier.alpha(if (a.earned) 1f else 0.7f))
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(text, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 2.dp))
}

/** A tappable tab / filter: soft amber fill when it's the current one. */
@Composable
private fun Pill(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
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
        Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TextDim, textAlign = TextAlign.Center)
    }
}
