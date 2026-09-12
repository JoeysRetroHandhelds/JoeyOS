package com.joeyos.app.data

import com.joeyos.app.AppLog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Converts GameCube and Wii disc images to RVZ with Dolphin's dolphin-tool. Ported from Chameleon.
 *
 * RVZ is Dolphin's own compressed disc format, so only Dolphin's tool makes one. As with
 * chdman, the tool is a native executable bundled as `jniLibs/<abi>/libdolphintool.so`
 * and run at arm's length. joeysretrohandhelds.com's file-types guide recommends RVZ for
 * both GameCube and Wii; this is the tool for that half, chdman covering the disc systems
 * that use CHD.
 *
 * dolphin-tool (from Dolphin) is GPL-2.0-or-later, invoked as a separate executable; its
 * licence and source offer ship in the app's notices.
 *
 * The RVZ is verified with `dolphin-tool verify` before any source is removed.
 */
object RvzConversion {

    /** A system and the disc images that convert to RVZ. */
    data class RvzSystem(
        val shortname: String,
        val inputExtensions: Set<String>,
        val label: String = shortname,
        /** The ROM folder names people use for this console (JoeyOS reads `ROMs/<folder>`). */
        val folderNames: Set<String> = setOf(shortname),
    )

    // GameCube images are iso/gcm/ciso; Wii adds wbfs. Output is always rvz. JoeyOS treats
    // GameCube and Wii as one system, and people often keep both in one folder, so each
    // folder takes every disc type dolphin-tool reads.
    private val Discs = setOf("iso", "gcm", "ciso", "wbfs")
    val Systems: List<RvzSystem> = listOf(
        RvzSystem("gc", Discs, "GameCube", setOf("gc", "gamecube", "ngc")),
        RvzSystem("wii", Discs, "Wii", setOf("wii")),
        RvzSystem("gcwii", Discs, "GameCube & Wii", setOf("gcwii", "gc-wii", "gamecube-wii")),
    )

    /** Every `ROMs/<folder>` on every storage volume, grouped by the console it holds. */
    fun romFolders(roots: List<File>): Map<RvzSystem, List<File>> {
        val found = mutableMapOf<RvzSystem, MutableList<File>>()
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

    enum class RvzStatus { Ready, Conflict }

    data class RvzJob(
        val system: String,
        val source: File,
        val target: File,
        val status: RvzStatus,
        val conflict: String?,
    )

    data class RvzProgress(val filesDone: Int, val filesTotal: Int, val current: String, val percent: Int)
    data class RvzResult(val name: String, val converted: Boolean, val message: String)
    data class RvzUndo(val target: File, val source: File, val removedSource: Boolean)
    data class RvzRun(val results: List<RvzResult>, val undo: List<RvzUndo>)

    /** The bundled dolphin-tool, or null when it was not shipped for this device's ABI. */
    fun binary(nativeLibraryDir: String): File? =
        File(nativeLibraryDir, "libdolphintool.so").takeIf { it.exists() && it.canExecute() }

    fun plan(folder: File, system: RvzSystem): List<RvzJob> {
        if (!folder.isDirectory) return emptyList()
        return folder.listFiles { f -> f.isFile }.orEmpty()
            .filter { it.extension.lowercase() in system.inputExtensions }
            .map { source ->
                val target = File(folder, source.nameWithoutExtension + ".rvz")
                val exists = target.exists()
                RvzJob(
                    system = system.shortname,
                    source = source,
                    target = target,
                    status = if (exists) RvzStatus.Conflict else RvzStatus.Ready,
                    conflict = if (exists) "an RVZ named ${target.name} is already there" else null,
                )
            }
            .sortedBy { it.source.name }
    }

    /** How many discs under [folder] are already RVZ. */
    fun doneCount(folder: File): Int {
        if (!folder.isDirectory) return 0
        return folder.listFiles { f -> f.isFile && f.extension.equals("rvz", ignoreCase = true) }
            ?.size ?: 0
    }

    suspend fun convertAll(
        tool: File,
        jobs: List<RvzJob>,
        removeSource: Boolean,
        overwrite: (RvzJob) -> Boolean,
        shouldStop: () -> Boolean = { false },
        onProgress: (RvzProgress) -> Unit,
    ): RvzRun = withContext(Dispatchers.IO) {
        val total = jobs.size
        var done = 0
        val results = mutableListOf<RvzResult>()
        val undos = mutableListOf<RvzUndo>()
        for (job in jobs) {
            if (shouldStop()) break
            onProgress(RvzProgress(done, total, job.source.name, 0))
            val (result, undo) = convert(tool, job, removeSource, overwrite(job), shouldStop) { percent ->
                onProgress(RvzProgress(done, total, job.source.name, percent))
            }
            results += result
            undo?.let { undos += it }
            done++
        }
        onProgress(RvzProgress(done, total, "", 0))
        RvzRun(results, undos)
    }

    private fun convert(
        tool: File,
        job: RvzJob,
        removeSource: Boolean,
        overwrite: Boolean,
        shouldStop: () -> Boolean,
        onPercent: (Int) -> Unit,
    ): Pair<RvzResult, RvzUndo?> {
        if (job.status == RvzStatus.Conflict && !overwrite) {
            return RvzResult(job.source.name, false, "skipped") to null
        }
        val temp = File(job.target.parentFile, job.target.name + ".part")
        runCatching { temp.delete() }

        // Dolphin's defaults for RVZ: zstd, level 5, 128 KiB blocks.
        val ran = runTool(
            tool,
            listOf(
                "convert", "-i", job.source.absolutePath, "-o", temp.absolutePath,
                "-f", "rvz", "-c", "zstd", "-l", "5", "-b", "131072",
            ),
            shouldStop,
            onPercent,
        )
        if (!ran || !temp.isFile || !verify(tool, temp)) {
            AppLog.w("Compress", "rvz: ${job.source.name} failed (ran=$ran, output=${temp.isFile})")
            runCatching { temp.delete() }
            return RvzResult(job.source.name, false, "could not convert ${job.source.name}") to null
        }

        if (job.target.exists() && !job.target.delete()) {
            runCatching { temp.delete() }
            return RvzResult(job.source.name, false, "could not replace ${job.target.name}") to null
        }
        if (!temp.renameTo(job.target)) {
            runCatching { temp.delete() }
            return RvzResult(job.source.name, false, "could not finish ${job.target.name}") to null
        }
        AppLog.i("Compress", "rvz: wrote ${job.target.name}")

        if (removeSource) {
            if (job.source.delete()) AppLog.i("Compress", "rvz: removed ${job.source.name}")
            else AppLog.w("Compress", "rvz: kept ${job.source.name}, could not remove it")
        }
        return RvzResult(job.source.name, true, "converted ${job.source.name}") to
            RvzUndo(job.target, job.source, removeSource)
    }

    /**
     * Reverses a run. Keep-both drops the RVZ; a removed source is rebuilt as an ISO from
     * the RVZ, which is Dolphin's plain container rather than the exact original.
     */
    suspend fun undo(tool: File, actions: List<RvzUndo>): Int = withContext(Dispatchers.IO) {
        var restored = 0
        for (action in actions) {
            if (!action.removedSource) {
                runCatching { action.target.delete() }
                restored++
                continue
            }
            val iso = File(action.source.parentFile, action.source.nameWithoutExtension + ".iso")
            val ran = NativeTool.run(
                tool,
                listOf("convert", "-i", action.target.absolutePath, "-o", iso.absolutePath, "-f", "iso"),
            )
            if (ran) {
                runCatching { action.target.delete() }
                AppLog.i("Compress", "rvz: restored ${iso.name}")
                restored++
            } else {
                AppLog.w("Compress", "rvz: could not restore ${action.source.name}, kept the RVZ")
            }
        }
        restored
    }

    private fun verify(tool: File, rvz: File): Boolean =
        NativeTool.run(tool, listOf("verify", "-i", rvz.absolutePath))

    private val percentPattern = Regex("""(\d{1,3})(?:\.\d+)?%""")

    private fun runTool(
        tool: File,
        args: List<String>,
        shouldStop: () -> Boolean,
        onPercent: (Int) -> Unit,
    ): Boolean = NativeTool.run(tool, args, shouldStop) { line ->
        percentPattern.find(line)?.let { onPercent(it.groupValues[1].toInt().coerceIn(0, 100)) }
    }
}
