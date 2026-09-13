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
        // If corePath is a core-name hint (not already a full .so path), use it to restrict
        // the ROM search to that core's system. Without this, a save file gets matched
        // against a same-named ROM in ANY system folder (e.g. "Aladdin" exists on both SNES
        // and Genesis) — the wrong game can launch even though the displayed core is correct.
        val coreHintSystemFolder = game.corePath
            ?.takeIf { !it.startsWith("/") }
            ?.let { systemFolderForCoreHint(it) }
        // The system id isn't always the folder name (ES-DE keeps PlayStation games in "psx").
        val aliases = coreHintSystemFolder?.let { RomFolders.esDeFoldersFor(it) }.orEmpty()
        val romPath = RomFinder.resolveRomFromSave(game.path, systemFolder = coreHintSystemFolder, folderAliases = aliases)
        Log.d(TAG, "launch: game.path=${game.path} coreHintSystemFolder=$coreHintSystemFolder romPath=$romPath")
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
            addFlags(FRESH_TASK)
        }
        Log.d(TAG, "launch: firing intent ROM=$romPath LIBRETRO=${corePath ?: "(none - RetroArch will pick core)"}")
        return context.tryStartGame(TAG, intent)
    }

    // ES-DE's folder names (e.g. "psx", "megadrive") map to ALL_SYSTEMS ids in RomFolders.
    private fun systemIdFromPath(romPath: String): String? {
        val folder = File(romPath).parentFile?.name ?: return null
        return RomFolders.systemIdForFolder(folder)
    }

    /**
     * Maps a RetroArch core-name hint (e.g. "snes9x", from a save file's parent folder name)
     * to the ROM folder it belongs to, by finding which system lists that core.
     */
    private fun systemFolderForCoreHint(coreHint: String): String? {
        val normalized = coreKey(coreHint)
        val system = ALL_SYSTEMS.firstOrNull { sys ->
            sys.retroarchCores.any { coreKey(it) == normalized }
        } ?: return null
        return system.id
    }

}
