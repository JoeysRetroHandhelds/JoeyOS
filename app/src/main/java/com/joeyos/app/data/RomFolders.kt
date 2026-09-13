package com.joeyos.app.data

import java.io.File

/**
 * The one list of console ROM folder names, shared by the launchers and the tools.
 *
 * JoeyOS reads `ROMs/<folder>` directly, and people name those folders differently depending
 * on the frontend they came from ("psx", "ps1", "PlayStation"...). Every place that looks for
 * a console's folder used to carry its own copy of these names, and the copies drifted, so a
 * folder one tool found another missed. Everything goes through here now.
 *
 * Names are lowercase and matched case-insensitively.
 */
object RomFolders {

    /**
     * Each console (by a short key) and every folder name it is found under. The key is itself
     * one of the names, so passing any name for a console finds all of them. Consoles that a
     * tool lumps together (Neo Geo Pocket and Color, say) stay separate here so the tool that
     * keeps them apart can; the tool that merges them just adds the two sets.
     */
    val Aliases: Map<String, Set<String>> = mapOf(
        // Nintendo
        "nes"          to setOf("nes", "famicom", "fc"),
        "fds"          to setOf("fds"),
        "snes"         to setOf("snes", "sfc", "superfamicom"),
        "n64"          to setOf("n64"),
        "gb"           to setOf("gb"),
        "gbc"          to setOf("gbc"),
        "gba"          to setOf("gba"),
        "nds"          to setOf("nds", "ds"),
        "3ds"          to setOf("3ds", "n3ds", "nintendo3ds"),
        "gc"           to setOf("gc", "gamecube", "ngc"),
        "wii"          to setOf("wii"),
        // A single folder holding both, which JoeyOS (like Dolphin) treats as one system.
        "gcwii"        to setOf("gcwii", "gc-wii", "gamecube-wii"),
        // Sega
        "genesis"      to setOf("genesis", "megadrive", "md"),
        "mastersystem" to setOf("mastersystem", "master", "sms"),
        "gamegear"     to setOf("gamegear", "gg"),
        "sega32x"      to setOf("sega32x", "32x"),
        "segacd"       to setOf("segacd", "megacd", "sega cd"),
        "saturn"       to setOf("saturn"),
        "dreamcast"    to setOf("dreamcast", "dc"),
        // Sony
        "psx"          to setOf("psx", "ps1", "playstation", "ps"),
        "ps2"          to setOf("ps2"),
        "psp"          to setOf("psp"),
        // NEC
        "tg16"         to setOf("tg16", "pce", "pcengine", "turbografx16"),
        "pcenginecd"   to setOf("tgcd", "pcenginecd", "pcecd", "turbografxcd", "pce-cd"),
        "pcfx"         to setOf("pcfx"),
        // SNK
        "neogeocd"     to setOf("neogeocd", "ngcd"),
        "ngp"          to setOf("ngp"),
        "ngpc"         to setOf("ngpc"),
        // Others
        "msx"          to setOf("msx", "msx2"),
        "3do"          to setOf("3do"),
    )

    /**
     * ES-DE ROM folder names mapped to the JoeyOS system id (ALL_SYSTEMS) where the two differ.
     * RetroArch works out which system a ROM belongs to from its folder, and ES-DE's names are
     * the ones most people have (it keeps PlayStation games in "psx", for example).
     */
    private val EsDeSystemIds: Map<String, String> = mapOf(
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

    /**
     * Every name the console behind [folder] goes by, [folder] included. A name that isn't in
     * [Aliases] (e.g. "switch") is simply itself.
     */
    fun namesFor(folder: String): Set<String> {
        val name = folder.lowercase()
        return Aliases[name] ?: Aliases.values.firstOrNull { name in it } ?: setOf(name)
    }

    /** Every `ROMs` folder (any case) at the top of the given storage volumes. */
    fun romsDirs(roots: List<File>): List<File> = roots.flatMap { root ->
        root.listFiles()?.filter { it.isDirectory && it.name.equals("roms", ignoreCase = true) }.orEmpty()
    }

    /**
     * The console's folders under every `ROMs/` on [roots], found under any of its names, so
     * "psx" also finds a "PlayStation" or "ps1" folder. Each storage volume's folders come
     * before the next volume's.
     */
    fun systemDirs(roots: List<File>, folder: String): List<File> {
        val names = namesFor(folder)
        return romsDirs(roots).flatMap { roms ->
            roms.listFiles()?.filter { it.isDirectory && it.name.lowercase() in names }.orEmpty()
        }
    }

    /**
     * Every `ROMs/<folder>` on [roots], grouped by the entry of [systems] whose [names] include
     * it. Folders no entry claims are left out. This is how each tool finds the consoles it
     * works on.
     */
    fun <T> group(roots: List<File>, systems: List<T>, names: (T) -> Set<String>): Map<T, List<File>> {
        val found = mutableMapOf<T, MutableList<File>>()
        for (roms in romsDirs(roots)) {
            roms.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val system = systems.firstOrNull { dir.name.lowercase() in names(it) } ?: return@forEach
                found.getOrPut(system) { mutableListOf() } += dir
            }
        }
        return found
    }

    /**
     * The JoeyOS system id (ALL_SYSTEMS) for a ROM folder name. ES-DE's names are mapped
     * first; a name that's already an id is kept; anything else is tried through its other
     * names, so a "PlayStation" folder is still PS1. Falls back to the folder name itself,
     * as it always has, when nothing matches.
     */
    fun systemIdForFolder(folder: String): String {
        val name = folder.lowercase()
        EsDeSystemIds[name]?.let { return it }
        val ids = ALL_SYSTEMS.map { it.id }.toSet()
        if (name in ids) return name
        return namesFor(name).firstNotNullOfOrNull { alias ->
            EsDeSystemIds[alias] ?: alias.takeIf { it in ids }
        } ?: name
    }

    /** The ES-DE folder names that hold system [systemId] under a different name (e.g. "psx" for "ps1"). */
    fun esDeFoldersFor(systemId: String): Set<String> =
        EsDeSystemIds.filterValues { it == systemId }.keys
}
