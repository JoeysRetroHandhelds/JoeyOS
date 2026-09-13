package com.joeyos.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.joeyos.app.AppLog
import com.joeyos.app.data.RAAward
import com.joeyos.app.data.RAAwardsResult
import com.joeyos.app.data.RAProgressGame
import com.joeyos.app.data.RAProgressResult
import com.joeyos.app.data.RARecentGame
import com.joeyos.app.data.RAResult
import com.joeyos.app.data.RetroAchievementsRepository
import com.joeyos.app.ui.theme.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

/*
 * The Achievements tab: RetroTracker's RetroAchievements data and layout (Overview, the games,
 * Stats), rebuilt in JoeyOS's style for a controller. RetroTracker is touch-first (swipe tabs,
 * pull to refresh, bottom sheets); here a row of chips picks the section, every row and tile is
 * a focus stop, and details and filters open as JoeyPopups.
 */

private const val TAG = "Achievements"

private enum class AchSection(val label: String) {
    Overview("Overview"), Games("Games"), Stats("Stats"), Account("Account")
}

@Composable
fun RetroAchievementsTab(
    raRepo: RetroAchievementsRepository,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    firstFocus: FocusRequester? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    var section by rememberSaveable { mutableStateOf(AchSection.Overview) }

    // ── Data ─────────────────────────────────────────────────────────────────
    var raAwards    by remember { mutableStateOf<RAResult?>(null) }
    var raProgress  by remember { mutableStateOf<RAProgressResult?>(null) }
    var raRecent    by remember { mutableStateOf<List<RARecentGame>>(emptyList()) }
    var raPoints    by remember { mutableStateOf<Int?>(null) }
    var raLoading   by remember { mutableStateOf(false) }
    var raPlaytimes by remember { mutableStateOf<Map<Int, Int>?>(null) }

    fun clear() { raAwards = null; raProgress = null; raRecent = emptyList(); raPoints = null; raPlaytimes = null }

    fun load(force: Boolean) {
        if (!raRepo.isConfigured) { clear(); return }
        scope.launch {
            raLoading = true
            coroutineScope {
                launch {
                    raAwards = raRepo.fetchAwards(force)
                    (raAwards as? RAResult.Error)?.let { AppLog.w(TAG, "RetroAchievements: ${it.message}") }
                }
                launch { raProgress = raRepo.fetchProgress(force) }
                launch { raRecent = raRepo.fetchRecentlyPlayed(forceRefresh = force) }
                launch { raPoints = raRepo.fetchPoints() }
            }
            raLoading = false
        }
    }

    LaunchedEffect(Unit) { load(false) }

    val ra = (raAwards as? RAResult.Success)?.data
    val raProg = (raProgress as? RAProgressResult.Success)?.games.orEmpty()

    val beaten = remember(ra) { ra?.awards.orEmpty().filter { !it.isFinished } }
    // Year, beaten, mastered — newest year first.
    val yearsTable = remember(ra) {
        val byYear = ra?.awards.orEmpty().groupBy { yearOf(it.awardedAt) }
        // sortedDescending, not toSortedSet().reversed(): on a SortedSet that call binds to Java
        // 21's SequencedCollection.reversed(), which only exists on Android 15+ (crashed on 14).
        byYear.keys.filter { it > 0 }.sortedDescending().map { y ->
            val list = byYear.getValue(y)
            Triple(y, list.count { !it.isFinished }, list.count { it.isFinished })
        }
    }
    val thisYearCount = beaten.count { yearOf(it.awardedAt) == currentYear }
    val allTimeCount = ra?.let { it.beatenHardcoreAwardsCount + it.beatenSoftcoreAwardsCount } ?: 0

    // Awards for a year (0 = all time), newest first.
    fun awardsFor(year: Int): List<GameEntry> =
        beaten.filter { year == 0 || yearOf(it.awardedAt) == year }.map { it.entry() }

    // "Currently playing": games started recently but not beaten yet.
    val currentlyPlaying = remember(ra, raRecent) {
        val awarded = ra?.awards.orEmpty().map { it.gameId }.toSet()
        raRecent.filter { it.gameId !in awarded && it.numPossible > 0 && it.numAchieved < it.numPossible }
            .map { it.entry() }.take(9)
    }

    // ── View state (hoisted: list items come and go as the list scrolls) ──────
    var year      by rememberSaveable { mutableIntStateOf(currentYear) }
    var mode      by rememberSaveable { mutableIntStateOf(0) }    // 0 beaten, 1 almost there
    var awardType by rememberSaveable { mutableIntStateOf(0) }    // 0 all, 1 beaten, 2 mastered
    var sort      by rememberSaveable { mutableIntStateOf(0) }    // 0 date, 1 A-Z
    var console   by rememberSaveable { mutableStateOf<String?>(null) }

    var detail        by remember { mutableStateOf<GameEntry?>(null) }
    var listYear      by remember { mutableStateOf<Int?>(null) }
    var filters       by remember { mutableStateOf(false) }
    var consolePicker by remember { mutableStateOf(false) }

    // Playtime is one request per game, so it's only added up when Stats is opened.
    LaunchedEffect(section, ra) {
        if (section == AchSection.Stats && ra != null && raPlaytimes == null) {
            raPlaytimes = raRepo.fetchGamePlaytimes(ra.awards.map { it.gameId })
        }
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { AppLog.w(TAG, "No app to open $url", it) }
    }

    // ── Popups ───────────────────────────────────────────────────────────────
    listYear?.let { y ->
        val list = remember(y, ra) { awardsFor(y) }
        JoeyPopup(
            title = if (y == 0) "All-time beaten (${list.size})" else "Beaten in $y (${list.size})",
            hint = "A details  •  B close", onDismiss = { listYear = null }, padded = false, wide = true
        ) {
            if (list.isEmpty()) PopupNote("Nothing beaten yet.")
            else LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                itemsIndexed(list) { i, e ->
                    GameRow(e, onClick = { detail = e },
                        modifier = Modifier.initialFocus(i == 0))
                }
            }
        }
    }

    detail?.let { e -> GameDetailPopup(e, raRepo, onOpen = ::openUrl, onClose = { detail = null }) }

    if (filters) {
        JoeyPopup(title = "Filters", hint = "A select  •  B close", onDismiss = { filters = false }) {
            if (mode == 0) {
                ChoiceRow("Show", listOf(0 to "All", 1 to "Beaten", 2 to "Completed"), awardType, { awardType = it })
                ChoiceRow("Sort", listOf(0 to "Date", 1 to "A–Z"), sort, { sort = it })
            }
            SectionLabel("Console")
            CardRow(onClick = { consolePicker = true }) { f -> CardText(console ?: "All consoles", "A to choose", f) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JoeyButton("Clear", { awardType = 0; sort = 0; console = null }, Modifier.weight(1f))
                JoeyButton("Done", { filters = false }, Modifier.weight(1f))
            }
        }
    }

    if (consolePicker) {
        val consoles = remember(ra, raProg) {
            (ra?.awards.orEmpty().map { it.consoleName } + raProg.map { it.consoleName })
                .filter { it.isNotBlank() }.distinct().sorted()
        }
        JoeyPopup(title = "Console", hint = "A set  •  B cancel", onDismiss = { consolePicker = false },
            padded = false) {
            // Opens scrolled to the current choice, so its row is composed and takes focus.
            LazyColumn(Modifier.fillMaxWidth(), state = rememberLazyListState(
                initialFirstVisibleItemIndex = console?.let { consoles.indexOf(it) + 1 }?.coerceAtLeast(0) ?: 0)) {
                item {
                    PopupRow("All consoles", { console = null; consolePicker = false }, isCurrent = console == null,
                        modifier = Modifier.initialFocus(console == null))
                }
                items(consoles) { c ->
                    PopupRow(c, { console = c; consolePicker = false }, isCurrent = console == c,
                        modifier = Modifier.initialFocus(console == c))
                }
            }
        }
    }

    // ── The tab ──────────────────────────────────────────────────────────────
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "sections") {
            val first = firstFocus ?: remember { FocusRequester() }
            Row(Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AchSection.entries.forEachIndexed { i, s ->
                    OptionChip(s.label, section == s, { section = s },
                        Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(first) else Modifier), fontSize = 11.sp)
                }
            }
        }

        if (!raRepo.isConfigured && section != AchSection.Account) {
            item {
                CardRow(onClick = { section = AchSection.Account }) { f ->
                    CardText("Connect RetroAchievements", "Add your username and API key in Account.", f)
                }
            }
        } else if (raAwards is RAResult.Error && section != AchSection.Account) {
            item { ErrorCard("Couldn't load RetroAchievements: ${(raAwards as RAResult.Error).message}") { load(true) } }
        } else when (section) {
            AchSection.Overview -> overview(
                loading = raLoading && ra == null,
                thisYear = thisYearCount, allTime = allTimeCount, currentYear = currentYear,
                playing = currentlyPlaying, years = yearsTable,
                onYear = { listYear = it }, onDetail = { detail = it }
            )

            AchSection.Games -> {
                item {
                    val first = remember { FocusRequester() }
                    Row(Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OptionChip("Beaten", mode == 0, { mode = 0 }, Modifier.weight(1f).focusRequester(first))
                        OptionChip("Almost there", mode == 1, { mode = 1 }, Modifier.weight(1f))
                        val active = awardType != 0 || sort != 0 || console != null
                        OptionChip(if (active) "Filters •" else "Filters", active, { filters = true }, Modifier.weight(1f))
                    }
                }
                when {
                    ra == null -> item { StatusLine("Loading your RetroAchievements games…", TextFaint) }
                    mode == 0 -> {
                        val minYear = ra.awards.minOfOrNull { yearOf(it.awardedAt) } ?: currentYear
                        item { YearChips((currentYear downTo minYear).toList(), year) { year = it } }
                        val list = ra.awards
                            .filter { yearOf(it.awardedAt) == year }
                            .filter { awardType == 0 || (awardType == 1) == !it.isFinished }
                            .filter { console == null || it.consoleName == console }
                            .let { l -> if (sort == 1) l.sortedBy { it.title.lowercase() } else l }
                        item {
                            CountLine("${list.size} game${if (list.size == 1) "" else "s"} in $year" +
                                (console?.let { "  ·  $it" } ?: ""), "Updated ${agoText(ra.fetchedAt)}")
                        }
                        if (list.isEmpty()) item { StatusLine("Nothing matches these filters in $year.", TextFaint) }
                        else if (sort == 0) {
                            list.groupBy { monthOf(it.awardedAt) }.forEach { (_, inMonth) ->
                                item { MonthLabel(inMonth.first().awardedAt, inMonth.size) }
                                items(inMonth) { a -> val e = a.entry(); GameRow(e, onClick = { detail = e }) }
                            }
                        } else items(list) { a -> val e = a.entry(); GameRow(e, onClick = { detail = e }) }
                    }
                    else -> {
                        val list = raProg
                            .filter { !it.isBeaten && it.numAwarded > 0 && it.percent < 1f }
                            .filter { console == null || it.consoleName == console }
                            .sortedByDescending { it.percent }
                        item { CountLine("${list.size} games in progress, closest to beating first", console ?: "") }
                        if (raProgress == null) item { StatusLine("Loading your progress…", TextFaint) }
                        items(list) { g -> val e = g.entry(); GameRow(e, onClick = { detail = e }) }
                    }
                }
            }

            AchSection.Stats -> stats(
                ra = ra, beaten = beaten, inProgress = raProg.count { !it.isBeaten && it.numAwarded > 0 && it.percent < 1f },
                years = yearsTable, thisYear = thisYearCount, allTime = allTimeCount, currentYear = currentYear,
                points = raPoints, playtimes = raPlaytimes, loadingPlaytime = ra != null && raPlaytimes == null
            )

            AchSection.Account -> item {
                AccountSection(raRepo, raLoading, raAwards, onChanged = ::clear, onFetch = { force -> load(force) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ── Overview ─────────────────────────────────────────────────────────────────

private fun LazyListScope.overview(
    loading: Boolean,
    thisYear: Int,
    allTime: Int,
    currentYear: Int,
    playing: List<GameEntry>,
    years: List<Triple<Int, Int, Int>>,
    onYear: (Int) -> Unit,
    onDetail: (GameEntry) -> Unit
) {
    item {
        val first = remember { FocusRequester() }
        Row(Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("$thisYear", "beaten in $currentYear", Accent, Modifier.weight(1f).focusRequester(first), big = true) {
                onYear(currentYear)
            }
            StatTile("$allTime", "beaten all-time", Accent, Modifier.weight(1f), big = true) { onYear(0) }
        }
    }
    if (loading) item { StatusLine("Loading your games…", TextFaint) }

    if (playing.isNotEmpty()) {
        item { SectionLabel("Currently playing", Modifier.padding(top = 6.dp)) }
        items(playing) { e -> GameRow(e, onClick = { onDetail(e) }) }
    }

    item { SectionLabel("Per year", Modifier.padding(top = 6.dp)) }
    if (years.isEmpty()) {
        item { StatusLine(if (loading) "Loading…" else "Nothing beaten yet.", TextFaint) }
    } else {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                TableHead("YEAR", Modifier.weight(1f))
                TableHead("BEATEN", Modifier.width(72.dp))
                TableHead("COMPLETED", Modifier.width(88.dp))
            }
        }
        items(years) { (y, beatenN, finishedN) ->
            CardRow(onClick = { onYear(y) }) { f ->
                Text("$y", fontSize = 13.sp, fontFamily = JoeyFont, fontWeight = FontWeight.Bold,
                    color = if (f) Accent else TextPrimary, modifier = Modifier.weight(1f))
                TableNum(beatenN, RaColor, Modifier.width(72.dp))
                TableNum(finishedN, MasteredColor, Modifier.width(88.dp))
            }
        }
        item { Text("A on a year lists the games beaten.", fontSize = 9.sp, fontFamily = JoeyFont, color = TextFaint) }
    }
}

@Composable
private fun TableHead(text: String, modifier: Modifier) {
    Text(text, fontSize = 9.sp, fontFamily = JoeyFont, fontWeight = FontWeight.Bold,
        color = TextFaint, letterSpacing = 1.sp, modifier = modifier)
}

@Composable
private fun TableNum(n: Int, color: Color, modifier: Modifier) {
    Text(if (n == 0) "—" else "$n", fontSize = 13.sp, fontFamily = JoeyFont, fontWeight = FontWeight.Bold,
        color = if (n == 0) TextFaint else color, modifier = modifier)
}

// ── Stats ────────────────────────────────────────────────────────────────────

private val MonthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun LazyListScope.stats(
    ra: RAAwardsResult?,
    beaten: List<RAAward>,
    inProgress: Int,
    years: List<Triple<Int, Int, Int>>,
    thisYear: Int,
    allTime: Int,
    currentYear: Int,
    points: Int?,
    playtimes: Map<Int, Int>?,
    loadingPlaytime: Boolean
) {
    // Hours: a game's playtime counted in the year (and month) of its first award.
    val hoursByYear = mutableMapOf<Int, Double>()
    val hoursByMonth = DoubleArray(12)
    playtimes?.let { pt ->
        ra?.awards.orEmpty().groupBy { it.gameId }.forEach { (id, awards) ->
            val first = awards.minOf { it.awardedAt }
            val h = (pt[id] ?: 0) / 3600.0
            hoursByYear.merge(yearOf(first), h, Double::plus)
            if (yearOf(first) == currentYear) hoursByMonth[monthOf(first)] += h
        }
    }
    val bestYear = years.maxByOrNull { it.second }

    val tiles = listOf(
        "$allTime" to "beaten all-time",
        "$thisYear" to "in $currentYear",
        finishedTotal(ra?.awards.orEmpty()).let { (n, label) -> "$n" to label },
        (points?.let { "%,d".format(it) } ?: "—") to "points",
        (if (loadingPlaytime) "…" else "${hoursByYear.values.sum().roundToInt()}") to "hours played",
        (bestYear?.let { "${it.first}" } ?: "—") to "best year",
        "$inProgress" to "in progress",
        "${beaten.map { it.consoleName }.distinct().size}" to "consoles"
    )
    tiles.chunked(4).forEach { row ->
        item {
            val first = remember { FocusRequester() }
            Row(Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { i, (v, l) ->
                    StatTile(v, l, Accent, Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(first) else Modifier))
                }
            }
        }
    }
    if (loadingPlaytime) item { StatusLine("Adding up your playtime…", TextFaint) }

    val sections = listOf(
        Triple("Hours per year", hoursByYear.entries.sortedByDescending { it.key }.map { "${it.key}" to it.value.roundToInt() }, VerifiedColor),
        Triple("Hours in $currentYear by month", MonthNames.mapIndexed { i, m -> m to hoursByMonth[i].roundToInt() }.filter { it.second > 0 }, VerifiedColor),
        Triple("Beaten per year", years.map { "${it.first}" to it.second }, Accent),
        Triple("$currentYear by month", MonthNames.mapIndexed { i, m ->
            m to beaten.count { yearOf(it.awardedAt) == currentYear && monthOf(it.awardedAt) == i }
        }.filter { it.second > 0 }, Accent),
        Triple("Awards", listOfNotNull(ra?.let {
            listOf("Beaten, hardcore" to it.beatenHardcoreAwardsCount, "Beaten, casual" to it.beatenSoftcoreAwardsCount,
                "Completed" to it.awards.count { a -> a.isFinished && a.awardDataExtra != 1 },
                "Mastered" to it.awards.count { a -> a.isFinished && a.awardDataExtra == 1 })
        }).flatten().filter { it.second > 0 }, RaColor),
        Triple("Top consoles", beaten.groupBy { it.consoleName }.map { it.key to it.value.size }
            .sortedByDescending { it.second }.take(8), RaColor),
        Triple("Most productive month", MonthNames.mapIndexed { i, m -> m to beaten.count { monthOf(it.awardedAt) == i } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(6), Accent)
    )
    sections.filter { it.second.isNotEmpty() }.forEach { (title, rows, color) ->
        item { BarSection(title, rows, color) }
    }
}

@Composable
private fun BarSection(title: String, rows: List<Pair<String, Int>>, color: Color) {
    val max = rows.maxOf { it.second }.coerceAtLeast(1)
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(title, Modifier.padding(top = 6.dp))
        // One focus stop per chart, so the D-pad can walk down through them.
        Column(
            Modifier.fillMaxWidth().readOnlyFocus(shape).clip(shape)
                .background(Color.White.copy(alpha = 0.04f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rows.forEach { (label, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 10.sp, fontFamily = JoeyFont, color = TextDim, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
                    Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.15f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(v.toFloat() / max).clip(RoundedCornerShape(4.dp)).background(color))
                    }
                    Text("$v", fontSize = 10.sp, fontFamily = JoeyFont, color = TextPrimary,
                        textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                }
            }
        }
    }
}

// ── Account ──────────────────────────────────────────────────────────────────

@Composable
private fun AccountSection(
    raRepo: RetroAchievementsRepository,
    loading: Boolean,
    raAwards: RAResult?,
    onChanged: () -> Unit,
    onFetch: (force: Boolean) -> Unit
) {
    var username   by remember { mutableStateOf(raRepo.username) }
    var apiKey     by remember { mutableStateOf(raRepo.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }

    // Manual refreshes are limited, to be kind to the RetroAchievements API.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000L); nowMs = System.currentTimeMillis() } }
    val canRefresh = nowMs - raRepo.lastManualRefreshAt > raRepo.manualRefreshCooldownMs
    val wait = ((raRepo.manualRefreshCooldownMs - (nowMs - raRepo.lastManualRefreshAt)) / 60_000).coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("RetroAchievements")
        if (raAwards is RAResult.Success) {
            Text("Logged in as ${raRepo.username}", fontSize = 12.sp, fontFamily = JoeyFont, color = TextPrimary)
            val first = remember { FocusRequester() }
            Row(Modifier.focusRow(first), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JoeyButton(when { loading -> "Loading…"; !canRefresh -> "Refresh in ${wait}m"; else -> "Refresh" },
                    { onFetch(true) }, Modifier.focusRequester(first), primary = true, enabled = !loading && canRefresh)
                JoeyButton("Log out", {
                    username = ""; apiKey = ""
                    raRepo.username = ""; raRepo.apiKey = ""; raRepo.clearCache(); onChanged()
                })
            }
        } else {
            // Typed into these fields only; saved when you press Connect. Saving every letter meant
            // an encrypted write and a cache wipe per keystroke, on the main thread.
            LabelledField("Username", username, { username = it })
            LabelledField("API key", apiKey, { apiKey = it },
                isPassword = true, showPassword = showApiKey, onToggleShow = { showApiKey = !showApiKey })
            Text("Get your API key from retroachievements.org → Settings → Keys", fontSize = 9.sp,
                fontFamily = JoeyFont, color = TextFaint)
            JoeyButton(if (loading) "Loading…" else "Connect", {
                if (username.trim() != raRepo.username || apiKey.trim() != raRepo.apiKey) {
                    raRepo.username = username.trim(); raRepo.apiKey = apiKey.trim(); raRepo.clearCache(); onChanged()
                }
                onFetch(true)
            }, primary = true,
                enabled = username.isNotBlank() && apiKey.isNotBlank() && !loading)
            (raAwards as? RAResult.Error)?.let { StatusLine(it.message, MissingColor) }
        }
    }
}

@Composable
private fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onToggleShow: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(label)
        ControllerTextField(
            value = value, onValueChange = onValueChange, placeholder = label,
            isPassword = isPassword, showPassword = showPassword,
            trailing = if (isPassword && onToggleShow != null) {
                {
                    val (source, focused) = rememberFocusState()
                    Text(if (showPassword) "hide" else "show", fontSize = 10.sp, fontFamily = JoeyFont,
                        color = if (focused) Accent else TextFaint,
                        modifier = Modifier.clickable(interactionSource = source, indication = null, onClick = onToggleShow)
                            .padding(start = 8.dp))
                }
            } else null
        )
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────────

@Composable
private fun YearChips(years: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    LazyRow(Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(years) { i, y ->
            OptionChip("$y", y == selected, { onSelect(y) },
                Modifier.width(72.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier))
        }
    }
}

@Composable
private fun CountLine(text: String, sub: String) {
    Column {
        Text(text, fontSize = 12.sp, fontFamily = JoeyFont, fontWeight = FontWeight.Medium, color = TextPrimary)
        if (sub.isNotEmpty()) Text(sub, fontSize = 9.sp, fontFamily = JoeyFont, color = TextFaint)
    }
}

@Composable
private fun MonthLabel(date: Date, count: Int) {
    SectionLabel("${monthFmt().format(date)}  ·  $count", Modifier.padding(top = 6.dp))
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    CardRow(onClick = onRetry) { f -> CardText(message, "A to try again", f) }
}

/** A game's details, with RetroAchievements' extra facts (playtime, genre, …) fetched when opened. */
@Composable
private fun GameDetailPopup(
    e: GameEntry,
    raRepo: RetroAchievementsRepository,
    onOpen: (String) -> Unit,
    onClose: () -> Unit
) {
    val extra by produceState<List<Pair<String, String>>?>(null, e.raGameId) {
        value = if (e.raGameId > 0) raRepo.fetchGameInfo(e.raGameId) else emptyList()
    }
    JoeyPopup(title = e.title, hint = "B close", onDismiss = onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Thumb(e.imageUrl, e.title, 96.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (e.facts + extra.orEmpty()).distinctBy { it.first }.forEach { (k, v) ->
                    Row {
                        Text(k, fontSize = 10.sp, fontFamily = JoeyFont, color = TextFaint, modifier = Modifier.width(96.dp))
                        Text(v, fontSize = 11.sp, fontFamily = JoeyFont, color = TextPrimary)
                    }
                }
                if (extra == null) Text("Loading details…", fontSize = 10.sp, fontFamily = JoeyFont, color = TextFaint)
            }
        }
        val first = remember { FocusRequester() }
        Row(Modifier.focusRow(first), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            e.url?.let { url ->
                JoeyButton("Open on website", { onOpen(url) }, Modifier.weight(1f).focusRequester(first))
            }
            JoeyButton("Close", onClose, Modifier.weight(1f).initialFocus()
                .then(if (e.url == null) Modifier.focusRequester(first) else Modifier))
        }
    }
}
