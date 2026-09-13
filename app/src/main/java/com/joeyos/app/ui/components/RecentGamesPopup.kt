package com.joeyos.app.ui.components

import com.joeyos.app.R
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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

internal val EMULATOR_NAMES = mapOf(
    "org.ppsspp"                       to "PPSSPP",
    "me.magnum.melonds"                to "melonDS",
    "me.magnum.melondualds"            to "MelonDualDS",
    "org.dolphinemu"                   to "Dolphin",
    "com.joeyos.dolphinemu"            to "DolphinCS",
    "xyz.aethersx2"                    to "AetherSX2",
    "net.nicholaswilde.nethersx2"      to "NetherSX2",
    "com.armsx2"                       to "ARMSX2",
    "com.armsx3"                       to "ARMSX3",
    "com.flycast.emulator"             to "Flycast",
    "org.azahar_emu"                   to "Azahar",
    "info.cemu"                        to "Cemu",
    "dev.eden"                         to "Eden",
    "org.vita3k"                       to "Vita3K",
    "com.github.stenzek.duckstation"   to "DuckStation",
    "com.duckstation"                  to "DuckStation",
    "aenu.aps3e"                       to "APS3E",
    "org.mupen64plusae"                to "M64Plus FZ",
)

/**
 * Reads the actual installed app's display label from PackageManager. Some emulator forks
 * (e.g. a "root storage patch" build of NetherSX2) keep the original app's package ID
 * unchanged, so package-name-prefix matching alone can't tell them apart — only the real
 * installed label can, since that reflects whatever name the user actually sees for it.
 */
internal fun installedAppLabel(context: Context, packageName: String): String? = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
} catch (_: Exception) {
    null
}

internal fun gameSubtitle(context: Context, game: RecentGame): String {
    val core = game.corePath ?: run {
        val pkg = game.emulatorPackage
        return installedAppLabel(context, pkg)
            ?: EMULATOR_NAMES.entries.firstOrNull { (prefix, _) -> pkg.startsWith(prefix) }?.value
            ?: pkg
    }
    val coreName = core
        .substringAfterLast("/")
        .removeSuffix(".so")
        .removeSuffix("_libretro_android")
        .removeSuffix("_libretro")
    return "RetroArch - ${CORE_SYSTEMS[coreName] ?: coreName}"
}

@Composable
fun RecentGamesPopup(
    games: List<RecentGame>,
    onLaunch: (RecentGame) -> Unit,
    onDismiss: () -> Unit
) {
    // Rows load after the popup opens; the first one takes focus when it arrives.
    JoeyPopup(
        title = "Recently Played",
        hint = "A launch  •  B cancel",
        onDismiss = onDismiss,
        padded = false
    ) {
        if (games.isEmpty()) {
            PopupNote("Loading…")
        } else {
            // The focused row is brought into view by the list itself as the D-pad walks it.
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(games, key = { i, g -> "$i:${g.emulatorPackage}:${g.path}" }) { i, game ->
                    GameListRow(
                        index    = i + 1,
                        game     = game,
                        onClick  = { onLaunch(game) },
                        modifier = Modifier.initialFocus(i == 0)
                    )
                }
            }
        }
    }
}

/**
 * A game row in a vertical popup list: a number badge (a star for the current favourite), the
 * title and where it runs. The focus, ring and chevron come from [PopupRowFrame], the same as
 * every other popup row. Shared by Recently Played and the favourite picker.
 */
@Composable
internal fun GameListRow(
    index: Int,
    game: RecentGame,
    onClick: () -> Unit,
    isCurrent: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val subtitle = remember(game.emulatorPackage, game.corePath) { gameSubtitle(context, game) }
    PopupRowFrame(onClick, modifier, verticalPadding = 9.dp, spacing = 12.dp) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Accent.copy(alpha = 0.18f))
                .border(1.dp, AccentSoft, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isCurrent) JoeyIcon(R.drawable.ic_star, Accent, 16.dp)
            else Text("$index", fontSize = 11.sp, fontFamily = JoeyFont,
                color = Accent, fontWeight = FontWeight.Bold)
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
            Text(
                subtitle,
                fontSize = 10.sp,
                fontFamily = JoeyFont,
                color = TextFaint,
                maxLines = 1
            )
        }
    }
}
