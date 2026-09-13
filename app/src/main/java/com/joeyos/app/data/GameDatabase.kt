package com.joeyos.app.data

import android.content.Context

/**
 * Lazy-loaded, cached lookup tables for emulators that store save files
 * under game serial / title-ID filenames rather than human-readable names.
 *
 * CSV format (one entry per line):  id,Title
 * Lines starting with # are treated as comments and ignored.
 *
 * Call GameDatabase.init(context) once on app startup before any lookups.
 */
object GameDatabase {

    private var appContext: Context? = null

    private val ps2    by lazy { load("gamedb/ps2.csv") }
    private val gcwii  by lazy { load("gamedb/gcwii.csv") }
    private val ds3    by lazy { load("gamedb/3ds.csv") }
    private val wiiu   by lazy { load("gamedb/wiiu.csv") }
    private val switch by lazy { load("gamedb/switch.csv") }
    private val vita   by lazy { load("gamedb/vita.csv") }
    private val ps3    by lazy { load("gamedb/ps3.csv") }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * GC/Wii game IDs by their first 4 characters (the title code), each mapped to the title of
     * the first ID in file order that starts with it — what lookupGcWiiByCode's old scan of the
     * whole table returned, without walking tens of thousands of entries per save folder.
     */
    private val gcwiiByCode by lazy {
        val map = HashMap<String, String>()
        gcwii.forEach { (key, value) -> if (key.length >= 4) map.putIfAbsent(key.substring(0, 4), value) }
        map
    }

    /**
     * Parse, on a background thread at startup, only the tables an installed emulator reads.
     * Each one is a few hundred KB of CSV and several MB once in memory, so loading all seven for
     * emulators that aren't there was wasted time and memory. Anything not loaded here still
     * loads on its first lookup.
     */
    fun preWarm(installedPackages: Collection<String>) {
        fun has(vararg prefixes: String) = installedPackages.any { pkg -> prefixes.any { pkg.startsWith(it) } }
        if (has("xyz.aethersx2", "net.nicholaswilde.nethersx2", "com.armsx2")) ps2
        if (has("org.dolphinemu", "com.joeyos.dolphinemu")) { gcwii; gcwiiByCode }
        if (has("org.azahar_emu")) ds3
        if (has("info.cemu")) wiiu
        if (has("dev.eden")) switch
        if (has("org.vita3k")) vita
        if (has("aenu.aps3e", "com.armsx3")) ps3
    }

    /** PS2 serial e.g. "SLUS-20963" */
    fun lookupPs2(serial: String): String? = ps2[serial.uppercase()]

    /** Dolphin 6-char game ID e.g. "GALE01" */
    fun lookupGcWii(id: String): String? = gcwii[id.uppercase()]

    /** 4-char Wii title code decoded from a save directory e.g. "RMCE" → first match */
    fun lookupGcWiiByCode(code: String): String? {
        val prefix = code.uppercase()
        return gcwii[prefix + "01"]
            ?: gcwii[prefix + "E1"]
            ?: (if (prefix.length == 4) gcwiiByCode[prefix]
                else gcwii.entries.firstOrNull { it.key.startsWith(prefix) }?.value)
    }

    /** 3DS full 16-char title ID e.g. "0004000000030800" */
    fun lookup3ds(titleId: String): String? = ds3[titleId.lowercase()]

    /** Wii U full 16-char title ID e.g. "0005000010101c00" */
    fun lookupWiiU(titleId: String): String? = wiiu[titleId.lowercase()]

    /** Switch full 16-char title ID e.g. "01007ef00011e000" */
    fun lookupSwitch(titleId: String): String? = switch[titleId.lowercase()]

    /** PS Vita title ID e.g. "PCSE00120" */
    fun lookupVita(titleId: String): String? = vita[titleId.uppercase()]

    /** PS3 title ID e.g. "BCUS98174" */
    fun lookupPs3(titleId: String): String? = ps3[titleId.uppercase()]

    private fun load(asset: String): Map<String, String> {
        val ctx = appContext ?: return emptyMap()
        return try {
            ctx.assets.open(asset).bufferedReader().useLines { lines ->
                lines
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val comma = line.indexOf(',')
                        if (comma < 0) null
                        else line.substring(0, comma).trim() to line.substring(comma + 1).trim()
                    }
                    .toMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
