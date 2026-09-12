package com.joeyos.app.data

import com.joeyos.app.AppLog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Converts disc images to CHD with MAME's chdman. Ported from Chameleon.
 *
 * chdman is a native tool, not a library, so it is bundled as an executable and run: the
 * app ships it as `jniLibs/<abi>/libchdman.so`, which Android extracts to the executable
 * nativeLibraryDir, and this execs it. Nothing here reimplements CHD; it drives the same
 * tool the desktop guides use, with `createcd` for CD systems and `createdvd` for the DVD
 * ones, exactly as joeysretrohandhelds.com's file-types guide lays out.
 *
 * chdman (from MAME) is GPL-2.0. It is a separate executable invoked at arm's length, and
 * its licence and a written offer for its source ship in the app's notices.
 *
 * Careful with the files it is given: the CHD is verified with `chdman verify` before any
 * source is removed, and a descriptor's referenced tracks (a cue's .bin, a gdi's tracks)
 * are only deleted alongside it, never on their own.
 */
object ChdConversion {

    /** The two chdman subcommands the guide uses, and their extract counterparts. */
    enum class Mode(val create: String, val extract: String) {
        Cd("createcd", "extractcd"),
        Dvd("createdvd", "extractdvd"),
    }

    /** A disc system, its chdman mode, and the images that convert. */
    data class ChdSystem(
        val shortname: String,
        val mode: Mode,
        val inputExtensions: Set<String>,
        /** Shown in the tool. */
        val label: String = shortname,
        /** The ROM folder names people use for this console (JoeyOS reads `ROMs/<folder>`). */
        val folderNames: Set<String> = setOf(shortname),
    )

    // From the file-types guide: CD mode for PS1, PS2, Saturn, Sega CD and Dreamcast,
    // DVD mode for PSP. Dreamcast is gdi only, since a Dreamcast disc cannot be made from
    // bin/cue. The rest take a cue, a gdi or a plain iso.
    private val Disc = setOf("cue", "gdi", "iso")
    val Systems: List<ChdSystem> = listOf(
        ChdSystem("psx", Mode.Cd, Disc, "PlayStation", setOf("psx", "ps1", "playstation", "ps")),
        ChdSystem("ps2", Mode.Cd, Disc, "PlayStation 2", setOf("ps2")),
        ChdSystem("saturn", Mode.Cd, Disc, "Saturn", setOf("saturn")),
        ChdSystem("segacd", Mode.Cd, Disc, "Sega CD", setOf("segacd", "megacd", "sega cd")),
        ChdSystem("dreamcast", Mode.Cd, setOf("gdi"), "Dreamcast", setOf("dreamcast", "dc")),
        ChdSystem("psp", Mode.Dvd, Disc, "PSP", setOf("psp")),
    )

    /** Every `ROMs/<folder>` on every storage volume, grouped by the console it holds. */
    fun romFolders(roots: List<File>): Map<ChdSystem, List<File>> {
        val found = mutableMapOf<ChdSystem, MutableList<File>>()
        for (root in roots) {
            val roms = root.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("roms", true) } ?: continue
            roms.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val system = Systems.firstOrNull { dir.name.lowercase() in it.folderNames } ?: return@forEach
                found.getOrPut(system) { mutableListOf() } += dir
            }
        }
        return found
    }

    val Shortnames: Set<String> = Systems.map { it.shortname }.toSet()

    enum class ChdStatus { Ready, Conflict }

    /**
     * One disc that could be converted.
     *
     * [source] is the descriptor or image; [referenced] are the track files a cue or gdi
     * points at, moved or deleted only with it.
     */
    data class ChdJob(
        val system: String,
        val mode: Mode,
        val source: File,
        val referenced: List<File>,
        val target: File,
        val status: ChdStatus,
        val conflict: String?,
    )

    data class ChdProgress(val filesDone: Int, val filesTotal: Int, val current: String, val percent: Int)
    data class ChdResult(val name: String, val converted: Boolean, val message: String)
    /** [sources] are what to bring back on undo; [restorable] is false for a removed iso. */
    data class ChdUndo(val target: File, val sources: List<File>, val mode: Mode, val removedSource: Boolean)
    data class ChdRun(val results: List<ChdResult>, val undo: List<ChdUndo>)

    /** The bundled chdman, or null when it was not shipped for this device's ABI. */
    fun binary(nativeLibraryDir: String): File? =
        File(nativeLibraryDir, "libchdman.so").takeIf { it.exists() && it.canExecute() }

    /** The discs under [folder] that could be converted for [system]. */
    fun plan(folder: File, system: ChdSystem): List<ChdJob> {
        if (!folder.isDirectory) return emptyList()
        val files = folder.listFiles { f -> f.isFile }.orEmpty()
        return files.filter { it.extension.lowercase() in system.inputExtensions }
            .map { source ->
                val target = File(folder, source.nameWithoutExtension + ".chd")
                val exists = target.exists()
                ChdJob(
                    system = system.shortname,
                    mode = system.mode,
                    source = source,
                    referenced = referencedFiles(source),
                    target = target,
                    status = if (exists) ChdStatus.Conflict else ChdStatus.Ready,
                    conflict = if (exists) "a CHD named ${target.name} is already there" else null,
                )
            }
            .sortedBy { it.source.name }
    }

    /** How many discs under [folder] are already CHD. */
    fun doneCount(folder: File): Int {
        if (!folder.isDirectory) return 0
        return folder.listFiles { f -> f.isFile && f.extension.equals("chd", ignoreCase = true) }
            ?.size ?: 0
    }

    /**
     * The track files a descriptor points at, resolved beside it.
     *
     * A cue lists `FILE "name" BINARY`; a gdi lists tracks as the fifth field of each
     * line, quoted or not. An iso references nothing. Pure text parsing, so it is tested
     * without a device.
     */
    fun referencedFiles(descriptor: File): List<File> {
        val parent = descriptor.parentFile ?: return emptyList()
        val text = runCatching { descriptor.readText() }.getOrNull() ?: return emptyList()
        return when (descriptor.extension.lowercase()) {
            "cue" -> Regex("""(?i)FILE\s+"([^"]+)"""").findAll(text)
                .map { it.groupValues[1] }.toList()
            "gdi" -> text.lineSequence().drop(1).mapNotNull { line ->
                val quoted = Regex(""""([^"]+)"""").find(line)?.groupValues?.get(1)
                quoted ?: line.trim().split(Regex("\\s+")).getOrNull(4)
            }.toList()
            else -> emptyList()
        }.map { File(parent, it) }.filter { it.isFile }
    }

    suspend fun convertAll(
        chdman: File,
        jobs: List<ChdJob>,
        removeSource: Boolean,
        overwrite: (ChdJob) -> Boolean,
        shouldStop: () -> Boolean = { false },
        onProgress: (ChdProgress) -> Unit,
    ): ChdRun = withContext(Dispatchers.IO) {
        val total = jobs.size
        var done = 0
        val results = mutableListOf<ChdResult>()
        val undos = mutableListOf<ChdUndo>()
        for (job in jobs) {
            if (shouldStop()) break
            onProgress(ChdProgress(done, total, job.source.name, 0))
            val (result, undo) = convert(chdman, job, removeSource, overwrite(job), shouldStop) { percent ->
                onProgress(ChdProgress(done, total, job.source.name, percent))
            }
            results += result
            undo?.let { undos += it }
            done++
        }
        onProgress(ChdProgress(done, total, "", 0))
        ChdRun(results, undos)
    }

    private fun convert(
        chdman: File,
        job: ChdJob,
        removeSource: Boolean,
        overwrite: Boolean,
        shouldStop: () -> Boolean,
        onPercent: (Int) -> Unit,
    ): Pair<ChdResult, ChdUndo?> {
        if (job.status == ChdStatus.Conflict && !overwrite) {
            return ChdResult(job.source.name, false, "skipped") to null
        }
        val temp = File(job.target.parentFile, job.target.name + ".part")
        runCatching { temp.delete() }

        // chdman refuses to overwrite; it writes to a temp name we control, then we swap.
        val ran = runChdman(
            chdman,
            listOf(job.mode.create, "-i", job.source.absolutePath, "-o", temp.absolutePath, "-f"),
            shouldStop,
            onPercent,
        )
        if (!ran || !temp.isFile || !verify(chdman, temp)) {
            AppLog.w("Compress", "chd: ${job.source.name} failed (ran=$ran, output=${temp.isFile})")
            runCatching { temp.delete() }
            return ChdResult(job.source.name, false, "could not convert ${job.source.name}") to null
        }

        if (job.target.exists() && !job.target.delete()) {
            runCatching { temp.delete() }
            return ChdResult(job.source.name, false, "could not replace ${job.target.name}") to null
        }
        if (!temp.renameTo(job.target)) {
            runCatching { temp.delete() }
            return ChdResult(job.source.name, false, "could not finish ${job.target.name}") to null
        }
        AppLog.i("Compress", "chd: wrote ${job.target.name}")

        // Only after a verified CHD is in place is the source safe to drop, and its
        // referenced tracks go with it, never on their own.
        if (removeSource) {
            (listOf(job.source) + job.referenced).forEach { file ->
                if (file.delete()) AppLog.i("Compress", "chd: removed ${file.name}")
                else AppLog.w("Compress", "chd: kept ${file.name}, could not remove it")
            }
        }
        return ChdResult(job.source.name, true, "converted ${job.source.name}") to
            ChdUndo(job.target, listOf(job.source) + job.referenced, job.mode, removeSource)
    }

    /**
     * Reverses a run. Keep-both simply drops the CHD; a removed source is extracted back
     * with chdman. Returns how many were undone.
     */
    suspend fun undo(chdman: File, actions: List<ChdUndo>): Int = withContext(Dispatchers.IO) {
        var restored = 0
        for (action in actions) {
            if (!action.removedSource) {
                runCatching { action.target.delete() }
                restored++
                continue
            }
            // Extract back to the descriptor the source was, beside the CHD.
            val descriptor = action.sources.firstOrNull() ?: continue
            val ran = NativeTool.run(
                chdman,
                listOf(action.mode.extract, "-i", action.target.absolutePath, "-o", descriptor.absolutePath, "-f"),
            )
            if (ran) {
                runCatching { action.target.delete() }
                AppLog.i("Compress", "chd: restored ${descriptor.name}")
                restored++
            } else {
                AppLog.w("Compress", "chd: could not restore ${descriptor.name}, kept the CHD")
            }
        }
        restored
    }

    private fun verify(chdman: File, chd: File): Boolean =
        NativeTool.run(chdman, listOf("verify", "-i", chd.absolutePath))

    /**
     * Runs chdman, reporting the percentage it prints as it works.
     *
     * chdman writes progress like "Compressing, 42.1% complete" on one line updated with
     * carriage returns, so each line is scanned for the last percentage. Running and
     * cancelling are [NativeTool]'s job.
     */
    private val percentPattern = Regex("""(\d{1,3})(?:\.\d+)?%""")

    private fun runChdman(
        chdman: File,
        args: List<String>,
        shouldStop: () -> Boolean,
        onPercent: (Int) -> Unit,
    ): Boolean = NativeTool.run(chdman, args, shouldStop) { line ->
        percentPattern.find(line)?.let { onPercent(it.groupValues[1].toInt().coerceIn(0, 100)) }
    }
}
