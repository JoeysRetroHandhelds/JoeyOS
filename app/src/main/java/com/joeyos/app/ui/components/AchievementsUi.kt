package com.joeyos.app.ui.components

import com.joeyos.app.R
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Pieces shared by the Achievements tab and the second screen: one game row, one tile, one set
 * of RetroAchievements-to-row conversions, so the two show a game the same way.
 */

internal val RaColor       = Color(0xFF3B82F6)
internal val MasteredColor = Color(0xFFD97706)

/** One game as a row and a detail popup. */
internal data class GameEntry(
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val badge: Int? = null,   // a drawable icon
    val badgeColor: Color = RaColor,
    val trailing: String? = null,
    val progress: Float? = null,
    val raGameId: Int = 0,
    val url: String? = null,
    val facts: List<Pair<String, String>> = emptyList(),
    val sortMs: Long = 0L,
)

internal fun dayFmt() = SimpleDateFormat("MMM d", Locale.US)
internal fun fullFmt() = SimpleDateFormat("MMM d, yyyy", Locale.US)
// Month headers in the award lists. Like the two above, each call makes a fresh formatter:
// SimpleDateFormat isn't thread-safe, so none is ever shared.
internal fun monthFmt() = SimpleDateFormat("MMMM", Locale.US)
internal fun monthYearFmt() = SimpleDateFormat("MMMM yyyy", Locale.US)
internal fun yearOf(d: Date) = Calendar.getInstance().apply { time = d }.get(Calendar.YEAR)
internal fun monthOf(d: Date) = Calendar.getInstance().apply { time = d }.get(Calendar.MONTH)

/** A finished game: every achievement earned. RA calls it Completed in casual, Mastered in hardcore. */
internal val RAAward.isFinished get() = awardType == "Mastery/Completion"
internal val RAAward.finishLabel get() = if (awardDataExtra == 1) "Mastered" else "Completed"

/** The finished-games total and what to call it: "completed" for a casual player, "mastered" only for hardcore. */
internal fun finishedTotal(awards: List<RAAward>): Pair<Int, String> {
    val mastered = awards.count { it.isFinished && it.awardDataExtra == 1 }
    val completed = awards.count { it.isFinished && it.awardDataExtra != 1 }
    val label = when { mastered == 0 -> "completed"; completed == 0 -> "mastered"; else -> "completed / mastered" }
    return (completed + mastered) to label
}

internal fun RAAward.entry(): GameEntry = GameEntry(
    title = title, subtitle = consoleName, imageUrl = iconUrl,
    badge = if (isFinished) R.drawable.ic_star else R.drawable.ic_check, badgeColor = if (isFinished) MasteredColor else RaColor,
    trailing = dayFmt().format(awardedAt), raGameId = gameId, url = gameUrl, sortMs = awardedAt.time,
    facts = listOf(
        "Console" to consoleName,
        "Award" to if (isFinished) finishLabel else "Beaten" + if (awardDataExtra == 1) " (hardcore)" else " (softcore)",
        "Date" to fullFmt().format(awardedAt)
    )
)

internal fun RAProgressGame.entry() = GameEntry(
    title = title, subtitle = "$consoleName  ·  $numAwarded/$maxPossible", imageUrl = iconUrl,
    trailing = "${(percent * 100).roundToInt()}%", progress = percent, raGameId = gameId, url = gameUrl,
    facts = listOf("Console" to consoleName, "Achievements" to "$numAwarded of $maxPossible")
)

internal fun RARecentGame.entry(): GameEntry {
    val pct = if (numPossible > 0) numAchieved.toFloat() / numPossible else 0f
    return GameEntry(
        title = title, subtitle = "$consoleName  ·  $numAchieved/$numPossible", imageUrl = iconUrl,
        badge = R.drawable.ic_play_arrow, trailing = "${(pct * 100).roundToInt()}%", progress = pct, raGameId = gameId, url = gameUrl,
        sortMs = lastPlayedMs,
        facts = listOf("Console" to consoleName, "Achievements" to "$numAchieved of $numPossible",
            "Last played" to fullFmt().format(Date(lastPlayedMs)))
    )
}

internal fun agoText(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1    -> "just now"
        m < 60   -> "${m}m ago"
        m < 1440 -> "${m / 60}h ago"
        else     -> "${m / 1440}d ago"
    }
}

/** A game's icon; its first letters on a tint when there is no picture. */
@Composable
internal fun Thumb(url: String?, fallback: String, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(RaColor.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
        else Text(fallback.take(2).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RaColor)
    }
}

/** A game in a list: icon, title, detail line, an optional progress bar, and its badge and date. */
@Composable
internal fun GameRow(e: GameEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CardRow(onClick = onClick, modifier = modifier) { focused ->
        Thumb(e.imageUrl, e.title, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(e.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Accent else TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(e.subtitle, fontSize = 10.sp, fontFamily = JoeyFont, color = TextFaint,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            e.progress?.let { p ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { p }, color = RaColor, trackColor = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            e.badge?.let { JoeyIcon(it, e.badgeColor, 16.dp) }
            e.trailing?.let { Text(it, fontSize = 10.sp, fontFamily = JoeyFont, color = TextDim) }
        }
    }
}

/** A number and what it counts. Pressable when [onClick] is set, otherwise just a focus stop. */
@Composable
internal fun StatTile(
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
        Text(label, fontSize = 10.sp, fontFamily = JoeyFont, color = TextFaint, maxLines = 2)
    }
}

