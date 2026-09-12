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
import com.joeyos.app.data.IBCompletion
import com.joeyos.app.data.IBProgressGame
import com.joeyos.app.data.IBProgressStatus
import com.joeyos.app.data.IBStatus
import com.joeyos.app.data.InfiniteBacklogRepository
import com.joeyos.app.data.RAAward
import com.joeyos.app.data.RAProgressGame
import com.joeyos.app.data.RAProgressResult
import com.joeyos.app.data.RARecentGame
import com.joeyos.app.data.RAResult
import com.joeyos.app.data.RetroAchievementsRepository
import com.joeyos.app.ui.theme.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The Achievements tab: RetroTracker's data and layout (Overview, RetroAchievements, Infinite
 * Backlog, Stats), rebuilt in JoeyOS's style for a controller. RetroTracker is touch-first
 * (swipe tabs, pull to refresh, bottom sheets); here a row of chips picks the section, every
 * row and tile is a focus stop, and details and filters open as JoeyPopups.
 */

private const val TAG = "Achievements"

private enum class AchSection(val label: String) {
    Overview("Overview"), RA("RetroAchievements"), IB("Infinite Backlog"), Stats("Stats"), Accounts("Accounts")
}

private val RaColor       = Color(0xFF3B82F6)
private val IbColor       = Color(0xFF10B981)
private val MasteredColor = Color(0xFFD97706)

/** One game as a row and a detail popup, whichever source it came from. */
private data class GameEntry(
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val source: String,                 // "RA" or "IB"
    val badge: String? = null,
    val badgeColor: Color = if (source == "RA") RaColor else IbColor,
    val trailing: String? = null,
    val progress: Float? = null,
    val raGameId: Int = 0,
    val url: String? = null,
    val facts: List<Pair<String, String>> = emptyList(),
    val sortMs: Long = 0L,
) {
    val color: Color get() = if (source == "RA") RaColor else IbColor
}

private fun dayFmt() = SimpleDateFormat("MMM d", Locale.US)
private fun fullFmt() = SimpleDateFormat("MMM d, yyyy", Locale.US)
private fun isoDay() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private fun yearOf(d: Date) = Calendar.getInstance().apply { time = d }.get(Calendar.YEAR)
private fun monthOf(d: Date) = Calendar.getInstance().apply { time = d }.get(Calendar.MONTH)
private fun parseDay(s: String?): Date? = s?.takeIf { it.length >= 10 }?.let { runCatching { isoDay().parse(it.take(10)) }.getOrNull() }

private val RAAward.isMastered get() = awardType == "Mastery/Completion"

private fun RAAward.entry(): GameEntry = GameEntry(
    title = title, subtitle = consoleName, imageUrl = iconUrl, source = "RA",
    badge = if (isMastered) "★" else "✓", badgeColor = if (isMastered) MasteredColor else RaColor,
    trailing = dayFmt().format(awardedAt), raGameId = gameId, url = gameUrl, sortMs = awardedAt.time,
    facts = listOf(
        "Console" to consoleName,
        "Award" to (if (isMastered) "Mastered" else "Beaten") + if (awardDataExtra == 1) " (hardcore)" else " (softcore)",
        "Date" to fullFmt().format(awardedAt)
    )
)

private fun IBCompletion.entry(): GameEntry {
    val d = parseDay(completionDate)
    return GameEntry(
        title = gameName,
        subtitle = listOfNotNull(playtimeLabel, rating?.let { "★ $it/100" }).joinToString("  ·  ").ifEmpty { "Infinite Backlog" },
        imageUrl = coverUrl, source = "IB",
        badge = if (isFullyCompleted) "100%" else "✓", badgeColor = if (isFullyCompleted) MasteredColor else IbColor,
        trailing = d?.let { dayFmt().format(it) }, url = gameUrl, sortMs = d?.time ?: 0L,
        facts = listOfNotNull(
            d?.let { "Beaten" to fullFmt().format(it) },
            "Completion" to if (isFullyCompleted) "100%" else "Main story",
            playtimeLabel?.let { "Playtime" to it },
            releaseYear?.let { "Released" to "$it" },
            rating?.let { "Rating" to "$it / 100" }
        )
    )
}

private fun RAProgressGame.entry() = GameEntry(
    title = title, subtitle = "$consoleName  ·  $numAwarded/$maxPossible", imageUrl = iconUrl, source = "RA",
    trailing = "${(percent * 100).roundToInt()}%", progress = percent, raGameId = gameId, url = gameUrl,
    facts = listOf("Console" to consoleName, "Achievements" to "$numAwarded of $maxPossible")
)

private fun RARecentGame.entry(): GameEntry {
    val pct = if (numPossible > 0) numAchieved.toFloat() / numPossible else 0f
    return GameEntry(
        title = title, subtitle = "$consoleName  ·  $numAchieved/$numPossible", imageUrl = iconUrl, source = "RA",
        badge = "▶", trailing = "${(pct * 100).roundToInt()}%", progress = pct, raGameId = gameId, url = gameUrl,
        sortMs = lastPlayedMs,
        facts = listOf("Console" to consoleName, "Achievements" to "$numAchieved of $numPossible",
            "Last played" to fullFmt().format(Date(lastPlayedMs)))
    )
}

private fun IBProgressGame.entry(): GameEntry {
    val d = parseDay(lastPlayedDate)
    return GameEntry(
        title = gameName, subtitle = listOfNotNull("Infinite Backlog", playtimeLabel).joinToString("  ·  "),
        imageUrl = coverUrl, source = "IB", badge = "▶", trailing = "$progress%", progress = progress / 100f,
        url = gameUrl, sortMs = d?.time ?: 0L,
        facts = listOfNotNull("Progress" to "$progress%", playtimeLabel?.let { "Playtime" to it },
            d?.let { "Last played" to fullFmt().format(it) })
    )
}

private fun agoText(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1    -> "just now"
        m < 60   -> "${m}m ago"
        m < 1440 -> "${m / 60}h ago"
        else     -> "${m / 1440}d ago"
    }
}

@Composable
fun RetroAchievementsTab(
    raRepo: RetroAchievementsRepository,
    ibRepo: InfiniteBacklogRepository,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    firstFocus: FocusRequester? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    var section by rememberSaveable { mutableStateOf(AchSection.Overview) }

    // ── Data ─────────────────────────────────────────────────────────────────
    var raAwards   by remember { mutableStateOf<RAResult?>(null) }
    var raProgress by remember { mutableStateOf<RAProgressResult?>(null) }
    var raRecent   by remember { mutableStateOf<List<RARecentGame>>(emptyList()) }
    var raPoints   by remember { mutableStateOf<Int?>(null) }
    var ibStatus   by remember { mutableStateOf<IBStatus?>(null) }
    var ibProgress by remember { mutableStateOf<IBProgressStatus?>(null) }
    var raLoading  by remember { mutableStateOf(false) }
    var ibLoading  by remember { mutableStateOf(false) }
    var raPlaytimes by remember { mutableStateOf<Map<Int, Int>?>(null) }

    fun loadRa(force: Boolean) {
        if (!raRepo.isConfigured) { raAwards = null; raProgress = null; raRecent = emptyList(); raPoints = null; return }
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

    fun loadIb(force: Boolean) {
        if (!ibRepo.isConfigured) { ibStatus = null; ibProgress = null; return }
        scope.launch {
            ibLoading = true
            coroutineScope {
                launch {
                    ibStatus = ibRepo.fetchCompletions(force)
                    (ibStatus as? IBStatus.Error)?.let { AppLog.w(TAG, "Infinite Backlog: ${it.message}") }
                }
                launch { ibProgress = ibRepo.fetchInProgress(force) }
            }
            ibLoading = false
        }
    }

    LaunchedEffect(Unit) { loadRa(false); loadIb(false) }

    val ra = (raAwards as? RAResult.Success)?.data
    val ib = (ibStatus as? IBStatus.Success)?.data
    val raProg = (raProgress as? RAProgressResult.Success)?.games.orEmpty()
    val ibProg = (ibProgress as? IBProgressStatus.Success)?.games.orEmpty()
    val ibByYear = ib?.completionsByYear.orEmpty()

    val raBeaten = remember(ra) { ra?.awards.orEmpty().filter { !it.isMastered } }
    val raBeatenByYear = remember(raBeaten) { raBeaten.groupBy { yearOf(it.awardedAt) }.mapValues { it.value.size } }
    val yearsTable = remember(raBeatenByYear, ibByYear) {
        // sortedDescending, not toSortedSet().reversed(): on a SortedSet that call binds to Java
        // 21's SequencedCollection.reversed(), which only exists on Android 15+ (crashed on 14).
        (raBeatenByYear.keys + ibByYear.keys).filter { it > 0 }.distinct().sortedDescending()
            .map { y -> Triple(y, raBeatenByYear[y] ?: 0, ibByYear[y]?.size ?: 0) }
    }
    val thisYearCount = (raBeatenByYear[currentYear] ?: 0) + (ibByYear[currentYear]?.size ?: 0)
    val allTimeCount = (ra?.let { it.beatenHardcoreAwardsCount + it.beatenSoftcoreAwardsCount } ?: 0) + (ib?.totalCompleted ?: 0)

    // Every RA-beaten game and every IB completion, for a year (0 = all time), newest first.
    fun combinedFor(year: Int): List<GameEntry> {
        val raList = raBeaten.filter { year == 0 || yearOf(it.awardedAt) == year }.map { it.entry() }
        val ibList = (if (year == 0) ibByYear.values.flatten() + ib?.undated.orEmpty() else ibByYear[year].orEmpty())
            .map { it.entry() }
        return (raList + ibList).sortedByDescending { it.sortMs }
    }

    // "Currently playing": RA games started but not beaten, and IB games part-way through.
    val currentlyPlaying = remember(ra, raRecent, ibProg) {
        val awarded = ra?.awards.orEmpty().map { it.gameId }.toSet()
        val raNow = raRecent.filter { it.gameId !in awarded && it.numPossible > 0 && it.numAchieved < it.numPossible }
            .map { it.entry() }
        val ibNow = ibProg.filter { it.progress in 1..99 }.sortedByDescending { it.updatedAt ?: "" }.take(9)
            .map { it.entry() }
        (raNow + ibNow).sortedByDescending { it.sortMs }.take(9)
    }

    // ── View state (hoisted: list items come and go as the list scrolls) ──────
    var year        by rememberSaveable { mutableIntStateOf(currentYear) }
    var raMode      by rememberSaveable { mutableIntStateOf(0) }    // 0 beaten, 1 almost there
    var raType      by rememberSaveable { mutableIntStateOf(0) }    // 0 all, 1 beaten, 2 mastered
    var raSort      by rememberSaveable { mutableIntStateOf(0) }    // 0 date, 1 A-Z
    var raConsole   by rememberSaveable { mutableStateOf<String?>(null) }
    var ibMode      by rememberSaveable { mutableIntStateOf(0) }
    var ibSort      by rememberSaveable { mutableIntStateOf(0) }    // 0 date, 1 A-Z, 2 playtime, 3 rating
    var ibMinRating by rememberSaveable { mutableIntStateOf(0) }
    var ibUndated   by rememberSaveable { mutableStateOf(false) }
    var ibProgSort  by rememberSaveable { mutableIntStateOf(0) }    // 0 by %, 1 last played

    var detail       by remember { mutableStateOf<GameEntry?>(null) }
    var combinedYear by remember { mutableStateOf<Int?>(null) }
    var raFilters    by remember { mutableStateOf(false) }
    var ibFilters    by remember { mutableStateOf(false) }
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
    combinedYear?.let { y ->
        val list = remember(y, ra, ib) { combinedFor(y) }
        val first = remember { FocusRequester() }
        JoeyPopup(
            title = if (y == 0) "All-time beaten (${list.size})" else "Beaten in $y (${list.size})",
            hint = "A details  •  B close", onDismiss = { combinedYear = null }, padded = false, wide = true,
            initialFocus = if (list.isNotEmpty()) first else null
        ) {
            if (list.isEmpty()) PopupNote("Nothing beaten yet.")
            else LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                itemsIndexed(list) { i, e ->
                    GameRow(e, onClick = { detail = e }, showSource = true,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
    }

    detail?.let { e -> GameDetailPopup(e, raRepo, onOpen = ::openUrl, onClose = { detail = null }) }

    if (raFilters) {
        JoeyPopup(title = "RetroAchievements filters", hint = "A select  •  B close", onDismiss = { raFilters = false }) {
            if (raMode == 0) {
                ChoiceRow("Show", listOf(0 to "All", 1 to "Beaten", 2 to "Mastered"), raType, { raType = it })
                ChoiceRow("Sort", listOf(0 to "Date", 1 to "A–Z"), raSort, { raSort = it })
            }
            SectionLabel("Console")
            CardRow(onClick = { consolePicker = true }) { f -> CardText(raConsole ?: "All consoles", "A to choose", f) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JoeyButton("Clear", { raType = 0; raSort = 0; raConsole = null }, Modifier.weight(1f))
                JoeyButton("Done", { raFilters = false }, Modifier.weight(1f))
            }
        }
    }

    if (consolePicker) {
        val consoles = remember(ra, raProg) {
            (ra?.awards.orEmpty().map { it.consoleName } + raProg.map { it.consoleName })
                .filter { it.isNotBlank() }.distinct().sorted()
        }
        val current = remember { FocusRequester() }
        JoeyPopup(title = "Console", hint = "A set  •  B cancel", onDismiss = { consolePicker = false },
            padded = false, initialFocus = current) {
            LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    PopupRow("All consoles", { raConsole = null; consolePicker = false }, isCurrent = raConsole == null,
                        modifier = if (raConsole == null) Modifier.focusRequester(current) else Modifier)
                }
                items(consoles) { c ->
                    PopupRow(c, { raConsole = c; consolePicker = false }, isCurrent = raConsole == c,
                        modifier = if (raConsole == c) Modifier.focusRequester(current) else Modifier)
                }
            }
        }
    }

    if (ibFilters) {
        JoeyPopup(title = "Infinite Backlog filters", hint = "A select  •  B close", onDismiss = { ibFilters = false }) {
            if (ibMode == 0) {
                ChoiceRow("Sort", listOf(0 to "Date", 1 to "A–Z", 2 to "Playtime", 3 to "Rating"), ibSort, { ibSort = it })
                ChoiceRow("Rating", listOf(0 to "Any", 70 to "70+", 80 to "80+", 90 to "90+"), ibMinRating, { ibMinRating = it })
                ToggleRow("Include undated games", "${ib?.undatedCount ?: 0} beaten games have no date", ibUndated,
                    { ibUndated = it })
            } else {
                ChoiceRow("Sort", listOf(0 to "By %", 1 to "Last played"), ibProgSort, { ibProgSort = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JoeyButton("Clear", { ibSort = 0; ibMinRating = 0; ibUndated = false; ibProgSort = 0 }, Modifier.weight(1f))
                JoeyButton("Done", { ibFilters = false }, Modifier.weight(1f))
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

        val nothingConnected = !raRepo.isConfigured && !ibRepo.isConfigured
        if (nothingConnected && section != AchSection.Accounts) {
            item {
                CardRow(onClick = { section = AchSection.Accounts }) { f ->
                    CardText("Connect an account", "Add your RetroAchievements or Infinite Backlog login in Accounts.", f)
                }
            }
        } else when (section) {
            AchSection.Overview -> overview(
                loading = (raLoading && ra == null) || (ibLoading && ib == null),
                thisYear = thisYearCount, allTime = allTimeCount, currentYear = currentYear,
                playing = currentlyPlaying, years = yearsTable,
                onCombined = { combinedYear = it }, onDetail = { detail = it }
            )

            AchSection.RA -> {
                item {
                    val first = remember { FocusRequester() }
                    Row(Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OptionChip("Beaten", raMode == 0, { raMode = 0 }, Modifier.weight(1f).focusRequester(first))
                        OptionChip("Almost there", raMode == 1, { raMode = 1 }, Modifier.weight(1f))
                        val active = raType != 0 || raSort != 0 || raConsole != null
                        OptionChip(if (active) "Filters •" else "Filters", active, { raFilters = true }, Modifier.weight(1f))
                    }
                }
                when {
                    !raRepo.isConfigured -> item { ConnectCard("RetroAchievements") { section = AchSection.Accounts } }
                    raAwards is RAResult.Error -> item {
                        ErrorCard("Couldn't load RetroAchievements: ${(raAwards as RAResult.Error).message}") { loadRa(true) }
                    }
                    ra == null -> item { StatusLine("Loading your RetroAchievements games…", TextFaint) }
                    raMode == 0 -> {
                        val minYear = ra.awards.minOfOrNull { yearOf(it.awardedAt) } ?: currentYear
                        item { YearChips((currentYear downTo minYear).toList(), year) { year = it } }
                        val list = ra.awards
                            .filter { yearOf(it.awardedAt) == year }
                            .filter { raType == 0 || (raType == 1) == !it.isMastered }
                            .filter { raConsole == null || it.consoleName == raConsole }
                            .let { l -> if (raSort == 1) l.sortedBy { it.title.lowercase() } else l }
                        item {
                            CountLine("${list.size} game${if (list.size == 1) "" else "s"} in $year" +
                                (raConsole?.let { "  ·  $it" } ?: ""), "Updated ${agoText(ra.fetchedAt)}")
                        }
                        if (list.isEmpty()) item { StatusLine("Nothing matches these filters in $year.", TextFaint) }
                        else if (raSort == 0) {
                            list.groupBy { monthOf(it.awardedAt) }.forEach { (_, inMonth) ->
                                item { MonthLabel(inMonth.first().awardedAt, inMonth.size) }
                                items(inMonth) { a -> val e = a.entry(); GameRow(e, onClick = { detail = e }) }
                            }
                        } else items(list) { a -> val e = a.entry(); GameRow(e, onClick = { detail = e }) }
                    }
                    else -> {
                        val list = raProg
                            .filter { !it.isBeaten && it.numAwarded > 0 && it.percent < 1f }
                            .filter { raConsole == null || it.consoleName == raConsole }
                            .sortedByDescending { it.percent }
                        item { CountLine("${list.size} games in progress, closest to beating first", raConsole ?: "") }
                        if (raProgress == null) item { StatusLine("Loading your progress…", TextFaint) }
                        items(list) { g -> val e = g.entry(); GameRow(e, onClick = { detail = e }) }
                    }
                }
            }

            AchSection.IB -> {
                item {
                    val first = remember { FocusRequester() }
                    Row(Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OptionChip("Beaten", ibMode == 0, { ibMode = 0 }, Modifier.weight(1f).focusRequester(first))
                        OptionChip("Almost there", ibMode == 1, { ibMode = 1 }, Modifier.weight(1f))
                        val active = ibSort != 0 || ibMinRating != 0 || ibUndated || ibProgSort != 0
                        OptionChip(if (active) "Filters •" else "Filters", active, { ibFilters = true }, Modifier.weight(1f))
                    }
                }
                when {
                    !ibRepo.isConfigured -> item { ConnectCard("Infinite Backlog") { section = AchSection.Accounts } }
                    ibStatus is IBStatus.Error -> item {
                        ErrorCard("Couldn't load Infinite Backlog: ${(ibStatus as IBStatus.Error).message}") { loadIb(true) }
                    }
                    ib == null -> item { StatusLine("Loading your Infinite Backlog games…", TextFaint) }
                    ibMode == 0 -> {
                        val minYear = ibByYear.keys.filter { it > 0 }.minOrNull() ?: currentYear
                        item { YearChips((currentYear downTo minYear).toList(), year) { year = it } }
                        fun List<IBCompletion>.shaped() = filter { ibMinRating == 0 || (it.rating ?: 0) >= ibMinRating }
                            .let { l ->
                                when (ibSort) {
                                    1 -> l.sortedBy { it.gameName.lowercase() }
                                    2 -> l.sortedByDescending { it.playtimeMinutes }
                                    3 -> l.sortedByDescending { it.rating ?: -1 }
                                    else -> l
                                }
                            }
                        val list = ibByYear[year].orEmpty().shaped()
                        val undated = if (ibUndated) ib.undated.shaped() else emptyList()
                        item {
                            CountLine("${list.size} beaten in $year" + if (ibMinRating > 0) "  ·  $ibMinRating+" else "",
                                "Updated ${agoText(ib.fetchedAt)}")
                        }
                        if (list.isEmpty()) item { StatusLine("Nothing matches these filters in $year.", TextFaint) }
                        else if (ibSort == 0) {
                            list.groupBy { parseDay(it.completionDate)?.let(::monthOf) ?: -1 }.forEach { (_, inMonth) ->
                                val d = parseDay(inMonth.first().completionDate)
                                if (d != null) item { MonthLabel(d, inMonth.size) }
                                items(inMonth) { c -> val e = c.entry(); GameRow(e, onClick = { detail = e }) }
                            }
                        } else items(list) { c -> val e = c.entry(); GameRow(e, onClick = { detail = e }) }
                        if (undated.isNotEmpty()) {
                            item { SectionLabel("No date  ·  ${undated.size}", Modifier.padding(top = 6.dp)) }
                            items(undated) { c -> val e = c.entry(); GameRow(e, onClick = { detail = e }) }
                        }
                    }
                    else -> {
                        val list = ibProg.filter { it.progress in 1..99 }.let { l ->
                            if (ibProgSort == 1) l.sortedByDescending { it.updatedAt ?: "" } else l.sortedByDescending { it.progress }
                        }
                        item {
                            CountLine("${list.size} games in progress",
                                if (ibProgress == null) "Scanning your backlog… the first time takes a while" else "")
                        }
                        items(list) { g -> val e = g.entry(); GameRow(e, onClick = { detail = e }) }
                    }
                }
            }

            AchSection.Stats -> stats(
                ra = ra, ibCompletions = ibByYear.values.flatten() + ib?.undated.orEmpty(), raBeaten = raBeaten,
                years = yearsTable, thisYear = thisYearCount, allTime = allTimeCount, currentYear = currentYear,
                points = raPoints, playtimes = raPlaytimes, loadingPlaytime = ra != null && raPlaytimes == null
            )

            AchSection.Accounts -> item {
                AccountsSection(raRepo, ibRepo, raLoading, ibLoading, raAwards, ibStatus,
                    onRaChanged = { raAwards = null; raProgress = null; raRecent = emptyList(); raPoints = null; raPlaytimes = null },
                    onRaFetch = { force -> loadRa(force) },
                    onIbConnect = { loadIb(true) },
                    onIbChanged = { ibStatus = null; ibProgress = null })
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
    onCombined: (Int) -> Unit,
    onDetail: (GameEntry) -> Unit
) {
    item {
        val first = remember { FocusRequester() }
        Row(Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("$thisYear", "beaten in $currentYear", Amber, Modifier.weight(1f).focusRequester(first), big = true) {
                onCombined(currentYear)
            }
            StatTile("$allTime", "beaten all-time", Amber, Modifier.weight(1f), big = true) { onCombined(0) }
        }
    }
    if (loading) item { StatusLine("Loading your games…", TextFaint) }

    if (playing.isNotEmpty()) {
        item { SectionLabel("Currently playing", Modifier.padding(top = 6.dp)) }
        items(playing) { e -> GameRow(e, onClick = { onDetail(e) }, showSource = true) }
    }

    item { SectionLabel("Beaten per year", Modifier.padding(top = 6.dp)) }
    if (years.isEmpty()) {
        item { StatusLine(if (loading) "Loading…" else "Nothing beaten yet.", TextFaint) }
    } else {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                TableHead("YEAR", Modifier.weight(1f)); TableHead("RA", Modifier.width(52.dp))
                TableHead("IB", Modifier.width(52.dp)); TableHead("TOTAL", Modifier.width(60.dp))
            }
        }
        items(years) { (y, raN, ibN) ->
            CardRow(onClick = { onCombined(y) }) { f ->
                Text("$y", fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    color = if (f) Amber else TextPrimary, modifier = Modifier.weight(1f))
                TableNum(raN, RaColor, Modifier.width(52.dp))
                TableNum(ibN, IbColor, Modifier.width(52.dp))
                TableNum(raN + ibN, TextPrimary, Modifier.width(60.dp))
            }
        }
        item { Text("A on a year lists the games.", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextFaint) }
    }
}

@Composable
private fun TableHead(text: String, modifier: Modifier) {
    Text(text, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        color = TextFaint, letterSpacing = 1.sp, modifier = modifier)
}

@Composable
private fun TableNum(n: Int, color: Color, modifier: Modifier) {
    Text(if (n == 0) "—" else "$n", fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        color = if (n == 0) TextFaint else color, modifier = modifier)
}

// ── Stats ────────────────────────────────────────────────────────────────────

private val MonthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun LazyListScope.stats(
    ra: com.joeyos.app.data.RAAwardsResult?,
    ibCompletions: List<IBCompletion>,
    raBeaten: List<RAAward>,
    years: List<Triple<Int, Int, Int>>,
    thisYear: Int,
    allTime: Int,
    currentYear: Int,
    points: Int?,
    playtimes: Map<Int, Int>?,
    loadingPlaytime: Boolean
) {
    // Hours: RA playtime counted in the year of the game's first award; IB minutes in the year beaten.
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
    ibCompletions.forEach { c ->
        val d = parseDay(c.completionDate) ?: return@forEach
        val h = c.playtimeMinutes / 60.0
        hoursByYear.merge(yearOf(d), h, Double::plus)
        if (yearOf(d) == currentYear) hoursByMonth[monthOf(d)] += h
    }
    val totalHours = hoursByYear.values.sum() + ibCompletions.filter { it.completionDate == null }.sumOf { it.playtimeMinutes / 60.0 }
    val ratings = ibCompletions.mapNotNull { it.rating }
    val bestYear = years.maxByOrNull { it.second + it.third }

    val tiles = listOfNotNull(
        "$allTime" to "beaten all-time",
        "$thisYear" to "in $currentYear",
        "${ra?.masteryAwardsCount ?: 0}" to "mastered (RA)",
        "${ibCompletions.count { it.isFullyCompleted }}" to "100% (IB)",
        (points?.let { "%,d".format(it) } ?: "—") to "RA points",
        (if (ratings.isEmpty()) "—" else "${ratings.average().roundToInt()}") to "avg rating (IB)",
        (if (loadingPlaytime) "…" else "${totalHours.roundToInt()}") to "hours played",
        (bestYear?.let { "${it.first}" } ?: "—") to "best year"
    )
    tiles.chunked(4).forEach { row ->
        item {
            val first = remember { FocusRequester() }
            Row(Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { i, (v, l) ->
                    StatTile(v, l, Amber, Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(first) else Modifier))
                }
            }
        }
    }
    if (loadingPlaytime) item { StatusLine("Adding up your RetroAchievements playtime…", TextFaint) }

    val sections = listOf(
        Triple("Hours per year", hoursByYear.entries.sortedByDescending { it.key }.map { "${it.key}" to it.value.roundToInt() }, VerifiedColor),
        Triple("Hours in $currentYear by month", MonthNames.mapIndexed { i, m -> m to hoursByMonth[i].roundToInt() }.filter { it.second > 0 }, VerifiedColor),
        Triple("Beaten per year", years.map { "${it.first}" to it.second + it.third }, Amber),
        Triple("$currentYear by month", MonthNames.mapIndexed { i, m ->
            m to raBeaten.count { yearOf(it.awardedAt) == currentYear && monthOf(it.awardedAt) == i } +
                ibCompletions.count { c -> parseDay(c.completionDate)?.let { yearOf(it) == currentYear && monthOf(it) == i } == true }
        }.filter { it.second > 0 }, Amber),
        Triple("RetroAchievements", listOfNotNull(ra?.let {
            listOf("Hardcore" to it.beatenHardcoreAwardsCount, "Softcore" to it.beatenSoftcoreAwardsCount, "Mastered" to it.masteryAwardsCount)
        }).flatten().filter { it.second > 0 }, RaColor),
        Triple("Top consoles", raBeaten.groupBy { it.consoleName }.map { it.key to it.value.size }
            .sortedByDescending { it.second }.take(8), RaColor),
        Triple("Most productive month", MonthNames.mapIndexed { i, m ->
            m to raBeaten.count { monthOf(it.awardedAt) == i } +
                ibCompletions.count { c -> parseDay(c.completionDate)?.let { monthOf(it) == i } == true }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(6), Amber),
        Triple("Infinite Backlog by release decade", ibCompletions.mapNotNull { it.releaseYear }.groupBy { it / 10 * 10 }
            .toSortedMap().map { "${it.key}s" to it.value.size }, IbColor),
        Triple("Infinite Backlog ratings", listOf(
            "90+" to ratings.count { it >= 90 }, "80–89" to ratings.count { it in 80..89 },
            "70–79" to ratings.count { it in 70..79 }, "Under 70" to ratings.count { it < 70 }
        ).filter { it.second > 0 }, IbColor)
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
                    Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextDim, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
                    Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.15f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(v.toFloat() / max).clip(RoundedCornerShape(4.dp)).background(color))
                    }
                    Text("$v", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextPrimary,
                        textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                }
            }
        }
    }
}

// ── Accounts ─────────────────────────────────────────────────────────────────

@Composable
private fun AccountsSection(
    raRepo: RetroAchievementsRepository,
    ibRepo: InfiniteBacklogRepository,
    raLoading: Boolean,
    ibLoading: Boolean,
    raAwards: RAResult?,
    ibStatus: IBStatus?,
    onRaChanged: () -> Unit,
    onRaFetch: (force: Boolean) -> Unit,
    onIbConnect: () -> Unit,
    onIbChanged: () -> Unit
) {
    var username   by remember { mutableStateOf(raRepo.username) }
    var apiKey     by remember { mutableStateOf(raRepo.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var ibUsername by remember { mutableStateOf(ibRepo.username) }

    // Manual refreshes are limited, to be kind to both sites' APIs.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000L); nowMs = System.currentTimeMillis() } }
    val raCanRefresh = nowMs - raRepo.lastManualRefreshAt > raRepo.manualRefreshCooldownMs
    val ibCanRefresh = nowMs - ibRepo.lastManualRefreshAt > ibRepo.manualRefreshCooldownMs
    val raWait = ((raRepo.manualRefreshCooldownMs - (nowMs - raRepo.lastManualRefreshAt)) / 60_000).coerceAtLeast(1)
    val ibWait = ((ibRepo.manualRefreshCooldownMs - (nowMs - ibRepo.lastManualRefreshAt)) / 60_000).coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("RetroAchievements")
        if (raAwards is RAResult.Success) {
            Text("Logged in as ${raRepo.username}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
            val first = remember { FocusRequester() }
            Row(Modifier.focusRow(first), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JoeyButton(when { raLoading -> "Loading…"; !raCanRefresh -> "Refresh in ${raWait}m"; else -> "Refresh" },
                    { onRaFetch(true) }, Modifier.focusRequester(first), primary = true, enabled = !raLoading && raCanRefresh)
                JoeyButton("Log out", {
                    username = ""; apiKey = ""
                    raRepo.username = ""; raRepo.apiKey = ""; raRepo.clearCache(); onRaChanged()
                })
            }
        } else {
            LabelledField("Username", username, { username = it; raRepo.username = it; raRepo.clearCache(); onRaChanged() })
            LabelledField("API key", apiKey, { apiKey = it; raRepo.apiKey = it; raRepo.clearCache(); onRaChanged() },
                isPassword = true, showPassword = showApiKey, onToggleShow = { showApiKey = !showApiKey })
            Text("Get your API key from retroachievements.org → Settings → Keys", fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, color = TextFaint)
            JoeyButton(if (raLoading) "Loading…" else "Connect", { onRaFetch(true) }, primary = true,
                enabled = username.isNotBlank() && apiKey.isNotBlank() && !raLoading)
            (raAwards as? RAResult.Error)?.let { StatusLine(it.message, MissingColor) }
        }

        SectionLabel("Infinite Backlog", Modifier.padding(top = 10.dp))
        if (ibStatus is IBStatus.Success) {
            Text("${ibRepo.username}  ·  ${ibStatus.data.totalCompleted} games beaten", fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, color = TextPrimary)
            val first = remember { FocusRequester() }
            Row(Modifier.focusRow(first), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JoeyButton(when { ibLoading -> "Loading…"; !ibCanRefresh -> "Refresh in ${ibWait}m"; else -> "Refresh" },
                    onIbConnect, Modifier.focusRequester(first), primary = true, enabled = !ibLoading && ibCanRefresh)
                JoeyButton("Disconnect", {
                    ibUsername = ""; ibRepo.username = ""; ibRepo.clearCache(); onIbChanged()
                })
            }
        } else {
            LabelledField("Username", ibUsername, { ibUsername = it })
            Text("infinitebacklog.net: no API key needed", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
            JoeyButton(if (ibLoading) "Loading…" else "Connect", { ibRepo.username = ibUsername; onIbConnect() },
                primary = true, enabled = ibUsername.isNotBlank() && !ibLoading)
            (ibStatus as? IBStatus.Error)?.let { StatusLine(it.message, MissingColor) }
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
                    Text(if (showPassword) "hide" else "show", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = if (focused) Amber else TextFaint,
                        modifier = Modifier.clickable(interactionSource = source, indication = null, onClick = onToggleShow)
                            .padding(start = 8.dp))
                }
            } else null
        )
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────────

/** A game's icon or cover; its first letters on a tint when there is no picture. */
@Composable
private fun Thumb(url: String?, fallback: String, color: Color, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
        else Text(fallback.take(2).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/** A game in a list: picture, title, detail line, an optional progress bar, and its badge and date. */
@Composable
private fun GameRow(e: GameEntry, onClick: () -> Unit, modifier: Modifier = Modifier, showSource: Boolean = false) {
    CardRow(onClick = onClick, modifier = modifier) { focused ->
        Thumb(e.imageUrl, e.title, e.color, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(e.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Amber else TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text((if (showSource) "${e.source}  ·  " else "") + e.subtitle, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, color = TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.progress?.let { p ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { p }, color = e.color, trackColor = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            e.badge?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = e.badgeColor) }
            e.trailing?.let { Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextDim) }
        }
    }
}

/** A number and what it counts. Pressable when [onClick] is set, otherwise just a focus stop. */
@Composable
private fun StatTile(
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val (source, focused) = rememberFocusState()
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = if (focused) 0.10f else 0.04f))
            .border(if (focused) FocusWidth else 1.dp, if (focused) FocusColor else Color.White.copy(alpha = 0.09f), shape)
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                else Modifier.focusable(interactionSource = source))
            .padding(horizontal = 14.dp, vertical = if (big) 16.dp else 12.dp)
    ) {
        Text(value, fontSize = if (big) 30.sp else 20.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint, maxLines = 2)
    }
}

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
        Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = TextPrimary)
        if (sub.isNotEmpty()) Text(sub, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
    }
}

@Composable
private fun MonthLabel(date: Date, count: Int) {
    SectionLabel("${SimpleDateFormat("MMMM", Locale.US).format(date)}  ·  $count", Modifier.padding(top = 6.dp))
}

@Composable
private fun ConnectCard(site: String, onClick: () -> Unit) {
    CardRow(onClick = onClick) { f -> CardText("Connect $site", "Add your login in Accounts.", f) }
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
    val close = remember { FocusRequester() }
    JoeyPopup(title = e.title, hint = "B close", onDismiss = onClose, initialFocus = close) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Thumb(e.imageUrl, e.title, e.color, 96.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (e.facts + extra.orEmpty()).distinctBy { it.first }.forEach { (k, v) ->
                    Row {
                        Text(k, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint, modifier = Modifier.width(96.dp))
                        Text(v, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                    }
                }
                if (extra == null) Text("Loading details…", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
            }
        }
        val first = remember { FocusRequester() }
        Row(Modifier.focusRow(first), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            e.url?.let { url ->
                JoeyButton("Open on website", { onOpen(url) }, Modifier.weight(1f).focusRequester(first))
            }
            JoeyButton("Close", onClose, Modifier.weight(1f).focusRequester(close)
                .then(if (e.url == null) Modifier.focusRequester(first) else Modifier))
        }
    }
}
