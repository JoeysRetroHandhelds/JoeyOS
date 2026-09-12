package com.joeyos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.data.RecentGame
import com.joeyos.app.ui.theme.*

/**
 * Pick the game for the Favorite dock tile. [games] is null while it loads. Focus opens on the
 * current favourite, so the D-pad starts from what's already chosen.
 */
@Composable
fun FavoritePickerPopup(
    games: List<RecentGame>?,
    currentFavorite: RecentGame?,
    onSelect: (RecentGame) -> Unit,
    onDismiss: () -> Unit
) {
    val screenH = LocalConfiguration.current.screenHeightDp
    fun isCurrent(game: RecentGame) = currentFavorite != null &&
        currentFavorite.title == game.title && currentFavorite.emulatorPackage == game.emulatorPackage
    val currentIndex = games?.indexOfFirst(::isCurrent)?.takeIf { it >= 0 } ?: 0
    val currentRow = remember { FocusRequester() }

    JoeyDialog(
        onDismiss = onDismiss,
        focusKey = !games.isNullOrEmpty(),
        initialFocus = if (!games.isNullOrEmpty()) currentRow else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .heightIn(max = (screenH * 0.82f).dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SheetBg)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Set Favorite", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                Text("↑↓  •  A to set  •  B cancel", fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, color = TextFaint)
            }

            when {
                games == null -> Text(
                    "Loading…",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextFaint,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                )
                games.isEmpty() -> Text(
                    "No recent games found.\nPlay some games first.",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextFaint,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                )
                else -> {
                    // Start scrolled to the current favourite so its row exists to take focus.
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(games, key = { i, g -> "$i:${g.emulatorPackage}:${g.path}" }) { i, game ->
                            GameListRow(
                                index     = i + 1,
                                game      = game,
                                isCurrent = isCurrent(game),
                                onClick   = { onSelect(game); onDismiss() },
                                modifier  = if (i == currentIndex) Modifier.focusRequester(currentRow) else Modifier
                            )
                        }
                    }
                }
            }
        }
    }
}
