package com.joeyos.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import com.joeyos.app.data.RecentGame
import com.joeyos.app.ui.theme.*

internal val CORE_SYSTEMS = mapOf(
    "snes9x"                  to "SNES",
    "bsnes"                   to "SNES",
    "bsnes_hd_beta"           to "SNES",
    "mesen-s"                 to "SNES",
    "mgba"                    to "GBA",
    "vba_next"                to "GBA",
    "vbam"                    to "GBA",
    "gambatte"                to "GB/GBC",
    "gearboy"                 to "GB/GBC",
    "tgbdual"                 to "GBC",
    "nestopia"                to "NES",
    "fceumm"                  to "NES",
    "mesen"                   to "NES",
    "genesis_plus_gx"         to "Genesis",
    "genesis_plus_gx_wide"    to "Genesis",
    "picodrive"               to "Genesis",
    "mupen64plus_next"        to "N64",
    "parallel_n64"            to "N64",
    "pcsx_rearmed"            to "PS1",
    "mednafen_psx"            to "PS1",
    "mednafen_psx_hw"         to "PS1",
    "mednafen_saturn"         to "Saturn",
    "mednafen_pce"            to "PC Engine",
    "mednafen_pce_fast"       to "PC Engine",
    "mednafen_supergrafx"     to "PC Engine",
    "mednafen_ngp"            to "Neo Geo Pocket",
    "mednafen_vb"             to "Virtual Boy",
    "mednafen_wswan"          to "WonderSwan",
    "mednafen_lynx"           to "Lynx",
    "flycast"                 to "Dreamcast",
    "reicast"                 to "Dreamcast",
    "dolphin"                 to "GameCube",
    "ppsspp"                  to "PSP",
    "desmume"                 to "DS",
    "melonds"                 to "DS",
    "mame"                    to "Arcade",
    "mame2003_plus"           to "Arcade",
    "mame2010"                to "Arcade",
    "fbneo"                   to "Arcade",
    "dosbox_pure"             to "DOS",
    "scummvm"                 to "ScummVM",
    "vice_x64"                to "C64",
    "bluemsx"                 to "MSX",
    "fuse"                    to "ZX Spectrum",
    "atari800"                to "Atari 8-bit",
    "stella"                  to "Atari 2600",
    "prosystem"               to "Atari 7800",
    "handy"                   to "Lynx",
    "o2em"                    to "Odyssey²",
)

private val EMULATOR_NAMES = mapOf(
    "org.ppsspp"                       to "PPSSPP",
    "me.magnum.melonds"                to "melonDS",
    "me.magnum.melondualds"            to "MelonDualDS",
    "org.dolphinemu"                   to "Dolphin",
    "xyz.aethersx2"                    to "AetherSX2",
    "net.nicholaswilde.nethersx2"      to "NetherSX2",
    "org.azahar_emu"                   to "Azahar",
    "info.cemu"                        to "Cemu",
    "dev.eden"                         to "Eden",
    "org.vita3k"                       to "Vita3K",
    "com.github.stenzek.duckstation"   to "DuckStation",
    "com.duckstation"                  to "DuckStation",
    "aenu.aps3e"                       to "APS3E",
)

private fun gameSubtitle(game: RecentGame): String {
    val core = game.corePath ?: run {
        val pkg = game.emulatorPackage
        return EMULATOR_NAMES.entries.firstOrNull { (prefix, _) -> pkg.startsWith(prefix) }?.value ?: pkg
    }
    val coreName = core
        .substringAfterLast("/")
        .removeSuffix(".so")
        .removeSuffix("_libretro_android")
        .removeSuffix("_libretro")
    return CORE_SYSTEMS[coreName] ?: coreName
}

@Composable
fun RecentGamesPopup(
    games: List<RecentGame>,
    selectedIndex: Int,
    onLaunch: (RecentGame) -> Unit,
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
                Text("Recently Played", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                Text("↑↓  •  A to launch  •  B cancel", fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, color = TextFaint)
            }

            if (games.isEmpty()) {
                Text(
                    "No recent games found.",
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
                    val isMultiEmulator = games.map { it.emulatorPackage }.toSet().size > 1
                    itemsIndexed(games) { i, game ->
                        RecentGameRow(
                            index        = i + 1,
                            game         = game,
                            isSelected   = i == selectedIndex,
                            showSubtitle = game.corePath != null || isMultiEmulator,
                            onClick      = { onLaunch(game) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentGameRow(index: Int, game: RecentGame, isSelected: Boolean, showSubtitle: Boolean, onClick: () -> Unit) {
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
            Text("$index", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = Amber, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                game.title,
                fontSize = 14.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showSubtitle) Text(
                gameSubtitle(game),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = TextFaint,
                maxLines = 1
            )
        }
        if (isSelected) Text("▶", fontSize = 12.sp, color = Amber)
    }
}
