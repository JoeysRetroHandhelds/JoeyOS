package com.joeyos.app.data

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

private const val TAG = "RetroArchLauncher"

object RetroArchLauncher {

    // Display name → actual .so filename stem (for names that don't sanitize cleanly)
    private val CORE_NAME_MAP = mapOf(
        "nestopia ue"       to "nestopia",
        "beetle psx"        to "mednafen_psx",
        "beetle psx hw"     to "mednafen_psx_hw",
        "beetle saturn"     to "mednafen_saturn",
        "beetle vb"         to "mednafen_vb",
        "beetle wonderswan" to "mednafen_wswan",
        "beetle pce"        to "mednafen_pce",
        "beetle pce fast"   to "mednafen_pce_fast",
        "beetle pc-fx"      to "mednafen_pcfx",
        "mednafen (beetle)" to "mednafen",
        "mesen-s"           to "mesen_s",
        "parallel n64"      to "parallel_n64",
        "finalburn neo"     to "fbneo",
        "mupen64plus-next"  to "mupen64plus_next",
        "swanstation"       to "swanstation",
        "snes9x 2002"       to "snes9x2002",
        "snes9x 2005"       to "snes9x2005",
        "snes9x 2010"       to "snes9x2010",
        "stella 2014"       to "stella2014",
        "mame 2000"         to "mame2000",
        "mame 2003"         to "mame2003",
        "mame 2003-plus"    to "mame2003_plus",
        "mame 2010"         to "mame2010",
        "mame 2015"         to "mame2015",
    )

    /** Assignment value separator: "com.retroarch::Nestopia UE" */
    private const val SEP = "::"

    fun packageFromAssignment(value: String): String = value.substringBefore(SEP)
    fun coreFromAssignment(value: String): String?   = if (value.contains(SEP)) value.substringAfter(SEP) else null

    /** Canonical assignment value for a given RetroArch core */
    fun assignmentFor(coreName: String): String = "com.retroarch.aarch64$SEP$coreName"

    private fun coreKey(coreName: String): String =
        CORE_NAME_MAP[coreName.lowercase()]
            ?: coreName.lowercase().replace("-", "_").replace(" ", "_")

    /**
     * Constructs the RetroArch core .so path using the internal app data pattern.
     * RetroArch can access its own /data/data/<pkg>/cores/ directory even though
     * we cannot read it — we just pass the path string and RetroArch opens the file.
     */
    private fun corePathForName(coreName: String, pkg: String): String {
        val key = coreKey(coreName)
        return "/data/data/$pkg/cores/${key}_libretro_android.so"
    }

    fun isCoreInstalled(coreName: String, pkg: String = "com.retroarch.aarch64"): Boolean = true

    fun launch(context: Context, game: RecentGame, assignments: Map<String, String>): Boolean {
        val romPath  = RomFinder.resolveRomFromSave(game.path)
        Log.d(TAG, "launch: game.path=${game.path} romPath=$romPath")
        if (romPath == null) return false

        val systemId = systemIdFromPath(romPath)
        Log.d(TAG, "launch: systemId=$systemId")
        if (systemId == null) return false

        val system = ALL_SYSTEMS.firstOrNull { it.id == systemId }
        Log.d(TAG, "launch: system=${system?.id} cores=${system?.retroarchCores}")
        if (system == null) return false

        val pkg = game.emulatorPackage
        // corePath from RecentGame is either a full .so path (from history) or a core
        // name hint (from save subdirectory name e.g. "SwanStation", "mGBA").
        val coreName: String? = when {
            game.corePath?.startsWith("/") == true -> null  // already a full path, use directly
            game.corePath != null -> game.corePath          // name hint from save subdir
            else -> {
                val assignment   = assignments[systemId] ?: ""
                val assignedCore = if (packageFromAssignment(assignment).startsWith("com.retroarch"))
                    coreFromAssignment(assignment) else null
                assignedCore ?: system.retroarchCores.firstOrNull()
            }
        }
        val corePath: String? = when {
            game.corePath?.startsWith("/") == true -> game.corePath
            coreName != null -> corePathForName(coreName, pkg)
            else -> null
        }
        Log.d(TAG, "launch: coreName=$coreName corePath=$corePath")

        val configFile = "/storage/emulated/0/Android/data/$pkg/files/retroarch.cfg"

        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(pkg, "com.retroarch.browser.retroactivity.RetroActivityFuture")
            putExtra("ROM", romPath)
            if (corePath != null) putExtra("LIBRETRO", corePath)
            putExtra("CONFIGFILE", configFile)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        Log.d(TAG, "launch: firing intent ROM=$romPath LIBRETRO=${corePath ?: "(none - RetroArch will pick core)"}")
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "launch: startActivity failed", e)
            false
        }
    }

    // Maps ES-DE ROM folder names → ALL_SYSTEMS ids where they differ
    private val ES_DE_FOLDER_MAP = mapOf(
        // Sony
        "psx"           to "ps1",
        "psvita"        to "vita",
        // Sega
        "dreamcast"     to "dc",
        "gamegear"      to "gg",
        "mastersystem"  to "sms",
        "megadrive"     to "genesis",
        "megadrivejp"   to "genesis",
        "megacd"        to "segacd",
        "megacdjp"      to "segacd",
        "sega32xjp"     to "sega32x",
        "sega32xna"     to "sega32x",
        "saturnjp"      to "saturn",
        "naomi2"        to "naomi",
        "naomigd"       to "naomi",
        "atomiswave"    to "naomi",
        // Nintendo
        "famicom"       to "nes",
        "fds"           to "nes",
        "sfc"           to "snes",
        "snesna"        to "snes",
        "gbc"           to "gb",
        "sgb"           to "gb",
        "wii"           to "gc",
        "n64dd"         to "n64",
        "satellaview"   to "snes",
        "sufami"        to "snes",
        // NEC
        "pcengine"      to "pce",
        "tg16"          to "pce",
        "tg-cd"         to "pcenginecd",
        // Atari
        "atari2600"     to "a2600",
        "atari5200"     to "a5200",
        "atari7800"     to "a7800",
        "atarijaguar"   to "jaguar",
        "atarilynx"     to "lynx",
        // SNK
        "ngpc"          to "ngp",
        "neogeocd"      to "neogeo",
        "neogeocdjp"    to "neogeo",
        // Arcade
        "fbneo"         to "arcade",
        "fba"           to "arcade",
        "cps"           to "arcade",
        "cps1"          to "arcade",
        "cps2"          to "arcade",
        "cps3"          to "arcade",
        "mame"          to "arcade",
        "consolearcade" to "arcade",
        "stv"           to "arcade",
    )

    private fun systemIdFromPath(romPath: String): String? {
        val folder = File(romPath).parentFile?.name?.lowercase() ?: return null
        return ES_DE_FOLDER_MAP[folder] ?: folder
    }

}
