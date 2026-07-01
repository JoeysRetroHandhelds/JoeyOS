package com.joeyos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.joeyos.app.data.RecentGame
import com.joeyos.app.ui.theme.*

private fun favoriteSubtitle(game: RecentGame): String {
    val core = game.corePath ?: return game.emulatorPackage.substringAfterLast(".")
    val coreName = core
        .substringAfterLast("/")
        .removeSuffix(".so")
        .removeSuffix("_libretro_android")
        .removeSuffix("_libretro")
    val system = CORE_SYSTEMS[coreName] ?: coreName
    return "retroarch - $system"
}

@Composable
fun FavoritePickerPopup(
    games: List<RecentGame>,
    currentFavorite: RecentGame?,
    selectedIndex: Int = 0,
    onSelect: (RecentGame) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        if (games.isNotEmpty()) listState.animateScrollToItem(selectedIndex.coerceIn(0, games.lastIndex))
    }
    val screenH = LocalConfiguration.current.screenHeightDp

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onDismiss)
        )
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

            if (games.isEmpty()) {
                Text(
                    "No recent games found.\nPlay some games first.",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextFaint,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                )
            } else {
                LazyColumn(
                    state          = listState,
                    modifier       = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 0.dp)
                ) {
                    itemsIndexed(games) { i, game ->
                        val isCurrent = currentFavorite?.title == game.title &&
                                        currentFavorite.emulatorPackage == game.emulatorPackage
                        FavoritePickerRow(
                            index      = i + 1,
                            game       = game,
                            isCurrent  = isCurrent,
                            isSelected = i == selectedIndex,
                            onClick    = { onSelect(game); onDismiss() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritePickerRow(
    index: Int,
    game: RecentGame,
    isCurrent: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Amber.copy(alpha = 0.18f))
                .border(1.dp, AmberSoft, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isCurrent)
                Text("★", fontSize = 12.sp, color = Amber)
            else
                Text("$index", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = Amber, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                game.title,
                fontSize = 13.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                favoriteSubtitle(game),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = TextFaint,
                maxLines = 1
            )
        }
        if (isSelected) Text("▶", fontSize = 12.sp, color = Amber)
    }
}
