package com.joeyos.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.data.IBCompletion
import com.joeyos.app.data.IBStatus
import com.joeyos.app.data.InfiniteBacklogRepository
import com.joeyos.app.data.RAAward
import com.joeyos.app.data.RAAwardsResult
import com.joeyos.app.data.RAResult
import com.joeyos.app.data.RetroAchievementsRepository
import com.joeyos.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun RetroAchievementsTab(
    raRepo: RetroAchievementsRepository,
    ibRepo: InfiniteBacklogRepository,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()
) {
    val scope = rememberCoroutineScope()

    // ── RetroAchievements state ───────────────────────────────────────────────
    var username       by remember { mutableStateOf(raRepo.username) }
    var apiKey         by remember { mutableStateOf(raRepo.apiKey) }
    var showApiKey     by remember { mutableStateOf(false) }
    var raLoading      by remember { mutableStateOf(false) }
    var raResult       by remember { mutableStateOf<RAResult?>(null) }
    var selectedYear   by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var raListExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (raRepo.isConfigured) {
            raLoading = true
            raResult  = raRepo.fetchAwards()
            raLoading = false
        }
    }

    fun raRefresh(force: Boolean = false) {
        scope.launch {
            raLoading = true
            raResult  = raRepo.fetchAwards(forceRefresh = force)
            raLoading = false
        }
    }

    val yearAwards = remember(raResult, selectedYear) {
        val data = (raResult as? RAResult.Success)?.data ?: return@remember emptyList<RAAward>()
        val cal  = Calendar.getInstance()
        data.awards.filter { award ->
            cal.time = award.awardedAt
            cal.get(Calendar.YEAR) == selectedYear
        }
    }

    val isRALoggedIn = raResult is RAResult.Success

    // ── Infinite Backlog state ────────────────────────────────────────────────
    var ibUsername     by remember { mutableStateOf(ibRepo.username) }
    var ibLoading      by remember { mutableStateOf(false) }
    var ibStatus       by remember { mutableStateOf<IBStatus?>(null) }
    var ibYear         by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var ibListExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (ibRepo.isConfigured) {
            ibLoading = true
            ibStatus  = ibRepo.fetchCompletions()
            ibLoading = false
        }
    }

    fun ibRefresh(force: Boolean = false) {
        scope.launch {
            ibLoading = true
            ibStatus  = ibRepo.fetchCompletions(forceRefresh = force)
            ibLoading = false
        }
    }

    fun ibConnect() {
        scope.launch {
            ibRepo.username = ibUsername
            ibLoading = true
            ibStatus  = ibRepo.fetchCompletions(forceRefresh = true)
            ibLoading = false
        }
    }

    fun ibDisconnect() {
        ibUsername = ""
        ibRepo.username = ""
        ibRepo.clearCache()
        ibStatus = null
    }

    val ibData      = (ibStatus as? IBStatus.Success)?.data
    val ibIsSuccess = ibData != null

    // ── Cooldown ticker ───────────────────────────────────────────────────────
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val raCanRefresh  = nowMs - raRepo.lastManualRefreshAt > raRepo.manualRefreshCooldownMs
    val ibCanRefresh  = nowMs - ibRepo.lastManualRefreshAt > ibRepo.manualRefreshCooldownMs
    val raRemaining   = ((raRepo.manualRefreshCooldownMs - (nowMs - raRepo.lastManualRefreshAt)) / 60_000).coerceAtLeast(1)
    val ibRemaining   = ((ibRepo.manualRefreshCooldownMs - (nowMs - ibRepo.lastManualRefreshAt)) / 60_000).coerceAtLeast(1)

    // ── Combined beaten-by-year (RA beaten awards + IB completions) ───────────
    val raBeatenByYear = remember(raResult) {
        val data = (raResult as? RAResult.Success)?.data ?: return@remember emptyMap<Int, Int>()
        val cal  = Calendar.getInstance()
        data.awards
            .filter { it.awardType == "Game Beaten" }
            .groupBy { award -> cal.apply { time = award.awardedAt }.get(Calendar.YEAR) }
            .mapValues { (_, v) -> v.size }
    }

    val combinedBeatenByYear = remember(raBeatenByYear, ibData) {
        val allYears = (raBeatenByYear.keys + (ibData?.completionsByYear?.keys ?: emptySet()))
            .toSet().sortedDescending()
        allYears.map { year ->
            Triple(year, raBeatenByYear[year] ?: 0, ibData?.completionsByYear?.get(year)?.size ?: 0)
        }
    }

    val maxYear = Calendar.getInstance().get(Calendar.YEAR)

    // Every control is a focus stop lit by its real focus; the list follows focus by itself.
    // Read-only rows (awards, beaten games, the totals table) are stops too, so the D-pad can
    // reach the bottom of the tab.
    LazyColumn(
        state          = listState,
        modifier       = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        // ══════════════════════════════════════════════════════════════════════
        //  RETROACHIEVEMENTS
        // ══════════════════════════════════════════════════════════════════════
        item { SectionLabel("RETROACHIEVEMENTS ACCOUNT") }

        if (isRALoggedIn) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Logged in as $username",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RAButton(
                            label         = when {
                                raLoading    -> "Loading…"
                                !raCanRefresh -> "${raRemaining}m"
                                else          -> "Refresh"
                            },
                            enabled       = !raLoading && raCanRefresh,
                            onClick       = { raRefresh(force = true) }
                        )
                        RAButton(
                            label         = "Logout",
                            enabled       = true,
                            onClick       = {
                                username = ""; apiKey = ""
                                raRepo.username = ""; raRepo.apiKey = ""
                                raRepo.clearCache(); raResult = null
                            }
                        )
                    }
                }
                if (raLoading) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFFE5A00D))
                }
            }
        } else {
            item {
                RATextField(
                    label         = "Username",
                    value         = username,
                    onValueChange = { username = it; raRepo.username = it; raRepo.clearCache(); raResult = null }
                )
            }
            item {
                RATextField(
                    label         = "API Key",
                    value         = apiKey,
                    onValueChange = { apiKey = it; raRepo.apiKey = it; raRepo.clearCache(); raResult = null },
                    isPassword    = true,
                    showPassword  = showApiKey,
                    onToggleShow  = { showApiKey = !showApiKey }
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Get your API key from retroachievements.org → Settings → Keys",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextFaint,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RAButton(
                        label         = if (raLoading) "Loading…" else "Fetch",
                        enabled       = username.isNotBlank() && apiKey.isNotBlank() && !raLoading,
                        onClick       = { raRefresh(force = true) }
                    )
                }
                if (raLoading) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFFE5A00D))
                }
            }
        }

        if (raResult is RAResult.Error) {
            item {
                Text(
                    "Error: ${(raResult as RAResult.Error).message}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFEF4444)
                )
            }
        }

        if (raResult is RAResult.Success) {
            val data = (raResult as RAResult.Success).data

            item { SectionLabel("ALL-TIME STATS") }
            item { RAStatsRow(data) }

            item {
                SectionLabel("AWARDS BY YEAR")
                Spacer(Modifier.height(8.dp))
                YearSelector(
                    selected     = selectedYear,
                    minYear      = 2000,
                    maxYear      = maxYear,
                    onChange     = { selectedYear = it; raListExpanded = true }
                )
            }

            item {
                val beatenCount  = yearAwards.count { it.awardType == "Game Beaten" }
                val masteryCount = yearAwards.count { it.awardType == "Mastery/Completion" }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RAStatChip("⚔ $beatenCount beaten", Color(0xFF3B82F6))
                    RAStatChip("★ $masteryCount mastered", Color(0xFFD97706))
                }
                Spacer(Modifier.height(6.dp))
                val (toggleSource, toggleNavSel) = rememberFocusState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (toggleNavSel) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f))
                        .then(if (toggleNavSel) Modifier.border(2.dp, FocusColor, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable(interactionSource = toggleSource, indication = null) { raListExpanded = !raListExpanded }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${yearAwards.size} total awards in $selectedYear",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (raListExpanded) "▲" else "▼",
                        fontSize = 11.sp,
                        color = if (toggleNavSel) TextPrimary else TextFaint
                    )
                }
            }

            item {
                AnimatedVisibility(visible = raListExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Spacer(Modifier.height(4.dp))
                        yearAwards.forEach { award -> RAAwardRow(award) }
                        if (yearAwards.isEmpty()) {
                            Text(
                                "No awards in $selectedYear",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextFaint,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  INFINITE BACKLOG
        // ══════════════════════════════════════════════════════════════════════
        item {
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            Spacer(Modifier.height(8.dp))
            SectionLabel("INFINITE BACKLOG")
        }

        if (ibData != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            ibRepo.username,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "${ibData.totalCompleted} games beaten",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextFaint
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RAButton(
                            label         = when {
                                ibLoading    -> "Loading…"
                                !ibCanRefresh -> "${ibRemaining}m"
                                else          -> "Refresh"
                            },
                            enabled       = !ibLoading && ibCanRefresh,
                            onClick       = { ibRefresh(true) }
                        )
                        RAButton(
                            label         = "Disconnect",
                            enabled       = true,
                            onClick       = { ibDisconnect() }
                        )
                    }
                }
                if (ibLoading) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF10B981))
                }
            }

            if (ibData.completionsByYear.isNotEmpty()) {
                item {
                    SectionLabel("BEATEN PER YEAR")
                    Spacer(Modifier.height(8.dp))
                    val sortedYears = ibData.completionsByYear.keys.sortedDescending()
                    // Show up to 4 years per row, at most 2 rows
                    sortedYears.chunked(4).take(2).forEachIndexed { rowIdx, chunk ->
                        if (rowIdx > 0) Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            chunk.forEach { year ->
                                val count = ibData.completionsByYear[year]?.size ?: 0
                                IBYearChip(year = year, count = count, modifier = Modifier.weight(1f))
                            }
                            // Pad incomplete row
                            repeat(4 - chunk.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            item {
                SectionLabel("BEATEN BY YEAR")
                Spacer(Modifier.height(8.dp))
                YearSelector(
                    selected     = ibYear,
                    minYear      = ibData.completionsByYear.keys.minOrNull() ?: 2010,
                    maxYear      = maxYear,
                    onChange     = { ibYear = it; ibListExpanded = true }
                )
            }

            item {
                val yearCompletions = ibData.completionsByYear[ibYear] ?: emptyList()
                val (ibToggleSource, ibToggleNavSel) = rememberFocusState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (ibToggleNavSel) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f))
                        .then(if (ibToggleNavSel) Modifier.border(2.dp, FocusColor, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable(interactionSource = ibToggleSource, indication = null) { ibListExpanded = !ibListExpanded }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${yearCompletions.size} beaten in $ibYear",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (ibListExpanded) "▲" else "▼",
                        fontSize = 11.sp,
                        color = if (ibToggleNavSel) TextPrimary else TextFaint
                    )
                }
            }

            item {
                val yearCompletions = ibData.completionsByYear[ibYear] ?: emptyList()
                AnimatedVisibility(visible = ibListExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Spacer(Modifier.height(4.dp))
                        yearCompletions.forEach { completion -> IBCompletionRow(completion) }
                        if (yearCompletions.isEmpty()) {
                            Text(
                                "No beaten games recorded for $ibYear",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextFaint,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }

        } else {
            item {
                RATextField(
                    label         = "Infinite Backlog Username",
                    value         = ibUsername,
                    onValueChange = { ibUsername = it }
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "infinitebacklog.net - no API key needed",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextFaint,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RAButton(
                        label         = if (ibLoading) "Loading…" else "Connect",
                        enabled       = ibUsername.isNotBlank() && !ibLoading,
                        onClick       = { ibConnect() }
                    )
                }
                if (ibLoading) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF10B981))
                }
            }
            if (ibStatus is IBStatus.Error) {
                item {
                    Text(
                        "Error: ${(ibStatus as IBStatus.Error).message}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFEF4444)
                    )
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        //  COMBINED BEATEN TOTAL
        // ══════════════════════════════════════════════════════════════════════
        if (combinedBeatenByYear.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                Spacer(Modifier.height(8.dp))
                SectionLabel("TOTAL BEATEN - ALL PLATFORMS")
            }

            if (!isRALoggedIn || !ibIsSuccess) {
                item {
                    Text(
                        buildString {
                            if (!isRALoggedIn) append("Connect RetroAchievements to include RA data. ")
                            if (!ibIsSuccess) append("Connect Infinite Backlog to include IB data.")
                        }.trim(),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextFaint,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }

            item {
                // Column header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("YEAR",  fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextFaint, modifier = Modifier.weight(1f))
                    Text("RA",    fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextFaint, modifier = Modifier.width(48.dp))
                    Text("IB",    fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextFaint, modifier = Modifier.width(48.dp))
                    Text("TOTAL", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextFaint, modifier = Modifier.width(56.dp))
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    combinedBeatenByYear.forEach { (year, raCount, ibCount) ->
                        CombinedBeatenRow(year = year, raCount = raCount, ibCount = ibCount)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ── Shared sub-composables ────────────────────────────────────────────────────

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = TextFaint,
        letterSpacing = 1.sp
    )
}

@Composable
internal fun RATextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onToggleShow: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
        ControllerTextField(
            value         = value,
            onValueChange = onValueChange,
            placeholder   = label,
            isPassword    = isPassword,
            showPassword  = showPassword,
            trailing      = if (isPassword && onToggleShow != null) {
                {
                    val (source, focused) = rememberFocusState()
                    Text(
                        if (showPassword) "hide" else "show",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (focused) Amber else TextFaint,
                        modifier = Modifier
                            .clickable(interactionSource = source, indication = null, onClick = onToggleShow)
                            .padding(start = 8.dp)
                    )
                }
            } else null
        )
    }
}

/** A text button (Fetch, Refresh, Logout, Connect): amber-ringed when focused, like every control. */
@Composable
internal fun RAButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val (source, focused) = rememberFocusState()
    Box(
        modifier = Modifier
            .clip(shape)
            .background(when {
                !enabled -> Color.White.copy(alpha = 0.06f)
                focused  -> Amber.copy(alpha = 0.30f)
                else     -> Amber.copy(alpha = 0.16f)
            })
            .border(if (focused) FocusWidth else 1.dp,
                when {
                    focused  -> FocusColor
                    enabled  -> AmberSoft
                    else     -> Color.White.copy(alpha = 0.10f)
                }, shape)
            .then(if (enabled) Modifier.clickable(interactionSource = source, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Amber else TextFaint
        )
    }
}

@Composable
internal fun RAStatChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
internal fun YearSelector(
    selected: Int,
    minYear: Int,
    maxYear: Int,
    onChange: (Int) -> Unit
) {
    val (prevSource, prevSelected) = rememberFocusState()
    val (nextSource, nextSelected) = rememberFocusState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val prevEnabled = selected > minYear
        Text(
            "◀",
            fontSize = 14.sp,
            color = when {
                prevSelected && prevEnabled -> Color(0xFFE5A00D)
                prevEnabled                -> TextPrimary
                else                       -> TextFaint
            },
            modifier = (if (prevEnabled) Modifier.clickable(interactionSource = prevSource, indication = null) { onChange(selected - 1) } else Modifier)
                .padding(horizontal = 6.dp)
        )
        Text(
            "$selected",
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        val nextEnabled = selected < maxYear
        Text(
            "▶",
            fontSize = 14.sp,
            color = when {
                nextSelected && nextEnabled -> Color(0xFFE5A00D)
                nextEnabled                -> TextPrimary
                else                       -> TextFaint
            },
            modifier = (if (nextEnabled) Modifier.clickable(interactionSource = nextSource, indication = null) { onChange(selected + 1) } else Modifier)
                .padding(horizontal = 6.dp)
        )
    }
}

@Composable
private fun RAStatsRow(data: RAAwardsResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RAStatChip("⚔ ${data.beatenHardcoreAwardsCount} HC", Color(0xFF3B82F6), Modifier.weight(1f))
        RAStatChip("⚔ ${data.beatenSoftcoreAwardsCount} SC", Color(0xFF6366F1), Modifier.weight(1f))
        RAStatChip("★ ${data.masteryAwardsCount}", Color(0xFFD97706), Modifier.weight(1f))
        RAStatChip("∑ ${data.totalAwardsCount}", Color(0xFF10B981), Modifier.weight(1f))
    }
}

@Composable
private fun IBYearChip(year: Int, count: Int, modifier: Modifier = Modifier) {
    val color = Color(0xFF10B981)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$year", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
            Text("$count", fontSize = 15.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, color = color)
        }
    }
}

private val ibDateFormat     = SimpleDateFormat("MMM d", Locale.US)
private val ibFullDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

@Composable
private fun IBCompletionRow(completion: IBCompletion) {
    val dateLabel = completion.completionDate?.let {
        try { ibDateFormat.format(ibFullDateFormat.parse(it)!!) } catch (_: Exception) { it }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .readOnlyFocus()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (completion.isFullyCompleted) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFD97706).copy(alpha = 0.18f))
                    .border(1.dp, Color(0xFFD97706).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("100%", fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
            }
        } else {
            Text("✓", fontSize = 12.sp, color = Color(0xFF10B981), fontFamily = FontFamily.Monospace)
        }
        Text(
            completion.gameName,
            fontSize = 12.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        if (dateLabel != null) {
            Text(dateLabel, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
        }
    }
}

@Composable
private fun CombinedBeatenRow(year: Int, raCount: Int, ibCount: Int) {
    val total = raCount + ibCount
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .readOnlyFocus()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$year",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (raCount > 0) "$raCount" else "-",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = if (raCount > 0) Color(0xFF3B82F6) else TextFaint,
            modifier = Modifier.width(48.dp)
        )
        Text(
            if (ibCount > 0) "$ibCount" else "-",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = if (ibCount > 0) Color(0xFF10B981) else TextFaint,
            modifier = Modifier.width(48.dp)
        )
        Text(
            "$total",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE5A00D),
            modifier = Modifier.width(56.dp)
        )
    }
}

private val displayDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

@Composable
private fun RAAwardRow(award: RAAward) {
    val isBeaten   = award.awardType == "Game Beaten"
    val isHC       = award.awardDataExtra == 1
    val badgeColor = when {
        !isBeaten -> Color(0xFFD97706)
        isHC      -> Color(0xFF3B82F6)
        else      -> Color(0xFF6366F1)
    }
    val badgeLabel = when {
        !isBeaten -> "MASTERY"
        isHC      -> "HC"
        else      -> "SC"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .readOnlyFocus()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(badgeColor.copy(alpha = 0.18f))
                .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(badgeLabel, fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, color = badgeColor)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(award.title, fontSize = 12.sp, color = TextPrimary,
                fontWeight = FontWeight.Medium, maxLines = 1)
            Text(award.consoleName, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextFaint)
        }
        Text(displayDateFormat.format(award.awardedAt), fontSize = 9.sp,
            fontFamily = FontFamily.Monospace, color = TextFaint)
    }
}
