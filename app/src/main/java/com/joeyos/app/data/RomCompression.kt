package com.joeyos.app.data

import com.joeyos.app.AppLog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * How a system's ROMs are compressed.
 *
 * Only [Zip] is built today, the format joeysretrohandhelds.com's file-types guide
 * recommends for the cart systems here. The enum exists so 7z, and the per-system disc
 * and dump formats (CHD, RVZ) the same guide recommends elsewhere, can be added later
 * without reshaping the tool.
 */
enum class CompressionMethod(val label: String, val extension: String) {
    Zip("Zip", "zip"),
}

/**
 * A system whose loose ROMs can be compressed, and what they look like.
 *
 * [sourceExtensions] are the uncompressed forms; a file already in an archive is left
 * alone. Which systems belong here follows the guide and the real library: the single
 * cart systems load a ROM straight from a zip, while disc systems use CHD and are not
 * here, and arcade "zips" are romsets that must not be repacked.
 */
data class CompressSystem(
    val shortname: String,
    val method: CompressionMethod,
    val sourceExtensions: Set<String>,
    /** Shown in the tool. */
    val label: String = shortname,
    /** The ROM folder names people use for this console (JoeyOS reads `ROMs/<folder>`). */
    val folderNames: Set<String> = setOf(shortname),
)

enum class CompressStatus { Ready, Conflict }

/** One ROM that could be compressed. */
data class CompressJob(
    val system: String,
    val source: File,
    val target: File,
    val method: CompressionMethod,
    val status: CompressStatus,
    val conflict: String?,
)

/** Enough to reverse one job: put the ROM back and drop the archive. */
data class CompressUndo(val source: File, val target: File, val removedOriginal: Boolean)

data class CompressProgress(val filesDone: Int, val filesTotal: Int, val current: String)
data class CompressResult(val name: String, val compressed: Boolean, val message: String)
data class CompressRun(val results: List<CompressResult>, val undo: List<CompressUndo>)

/**
 * Compresses loose ROMs into archives, safely. Ported from Chameleon.
 *
 * The archive is written under a temporary name and its entry checked against the source
 * before anything is finalised, and the original is only ever removed after that check
 * passes. Keeping the original is an option, so this can also be a space-saving copy that
 * leaves the source in place. Every action is recorded so a whole run can be undone.
 */
object RomCompression {

    // JoeyOS: each console carries its display name and the folder names it's found under.
    // GB and GBC share extensions, since many people keep both in one folder.
    val Systems: List<CompressSystem> = listOf(
        CompressSystem("nes", CompressionMethod.Zip, setOf("nes", "unf", "unif"),
            "NES", setOf("nes", "famicom", "fc")),
        CompressSystem("snes", CompressionMethod.Zip, setOf("sfc", "smc", "swc", "fig"),
            "SNES", setOf("snes", "sfc", "superfamicom")),
        CompressSystem("n64", CompressionMethod.Zip, setOf("n64", "z64", "v64"),
            "Nintendo 64", setOf("n64")),
        CompressSystem("gb", CompressionMethod.Zip, setOf("gb", "gbc"),
            "Game Boy", setOf("gb")),
        CompressSystem("gbc", CompressionMethod.Zip, setOf("gbc", "gb"),
            "Game Boy Color", setOf("gbc")),
        CompressSystem("gba", CompressionMethod.Zip, setOf("gba"),
            "Game Boy Advance", setOf("gba")),
        CompressSystem("nds", CompressionMethod.Zip, setOf("nds"),
            "Nintendo DS", setOf("nds", "ds")),
        CompressSystem("gamegear", CompressionMethod.Zip, setOf("gg"),
            "Game Gear", setOf("gamegear", "gg")),
        CompressSystem("genesis", CompressionMethod.Zip, setOf("md", "bin", "gen", "smd"),
            "Genesis / Mega Drive", setOf("genesis", "megadrive", "md")),
        CompressSystem("master", CompressionMethod.Zip, setOf("sms"),
            "Master System", setOf("mastersystem", "master", "sms")),
        CompressSystem("ngpc", CompressionMethod.Zip, setOf("ngc"),
            "Neo Geo Pocket Color", setOf("ngpc")),
        CompressSystem("ngp", CompressionMethod.Zip, setOf("ngp"),
            "Neo Geo Pocket", setOf("ngp")),
    )

    /** Every `ROMs/<folder>` on every storage volume, grouped by the console it holds. */
    fun romFolders(roots: List<File>): Map<CompressSystem, List<File>> {
        val found = mutableMapOf<CompressSystem, MutableList<File>>()
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

    private val ArchiveExtensions = setOf("zip", "7z")

    /** How many ROMs under [folder] are already archives. */
    fun compressedCount(folder: File): Int {
        if (!folder.isDirectory) return 0
        return folder.listFiles { f -> f.isFile && f.extension.lowercase() in ArchiveExtensions }
            ?.size ?: 0
    }

    /** How many `.nds` under [folder] are DSi-exclusive, and so deliberately not compressed. */
    fun dsiExclusiveCount(folder: File): Int {
        if (!folder.isDirectory) return 0
        return folder.listFiles { f -> f.isFile && f.extension.equals("nds", ignoreCase = true) }
            ?.count { isDsiExclusive(it) } ?: 0
    }

    /** The loose ROMs under [folder] that could be compressed for [system]. */
    fun plan(folder: File, system: CompressSystem): List<CompressJob> {
        if (!folder.isDirectory) return emptyList()
        val files = folder.listFiles { f -> f.isFile }.orEmpty()
        return files.filter { it.extension.lowercase() in system.sourceExtensions }
            // DSiWare and other DSi-exclusive titles cannot be zipped: their emulator
            // needs them raw. They carry no filename tag, so they are found by the DSi
            // unit code in the ROM header rather than guessed at from the name.
            .filterNot { system.shortname == "nds" && isDsiExclusive(it) }
            .map { source ->
                val target = File(folder, source.nameWithoutExtension + "." + system.method.extension)
                val exists = target.exists()
                CompressJob(
                    system = system.shortname,
                    source = source,
                    target = target,
                    method = system.method,
                    status = if (exists) CompressStatus.Conflict else CompressStatus.Ready,
                    conflict = if (exists) "an archive named ${target.name} is already there" else null,
                )
            }
            .sortedBy { it.source.name }
    }

    /**
     * Compresses many ROMs with progress, returning what it would take to undo them.
     *
     * [removeOriginal] decides whether the loose file is deleted after its archive is
     * verified, or kept beside it. Cancellable between files; a file in flight finishes
     * or leaves nothing behind.
     */
    suspend fun compressAll(
        jobs: List<CompressJob>,
        removeOriginal: Boolean,
        overwrite: (CompressJob) -> Boolean,
        shouldStop: () -> Boolean = { false },
        onProgress: (CompressProgress) -> Unit,
    ): CompressRun = withContext(Dispatchers.IO) {
        val total = jobs.size
        var done = 0
        val results = mutableListOf<CompressResult>()
        val undos = mutableListOf<CompressUndo>()
        for (job in jobs) {
            if (shouldStop()) break
            onProgress(CompressProgress(done, total, job.source.name))
            val (result, undo) = compress(job, removeOriginal, overwrite(job))
            results += result
            undo?.let { undos += it }
            done++
        }
        onProgress(CompressProgress(done, total, ""))
        CompressRun(results, undos)
    }

    private fun compress(
        job: CompressJob,
        removeOriginal: Boolean,
        overwrite: Boolean,
    ): Pair<CompressResult, CompressUndo?> {
        if (job.status == CompressStatus.Conflict && !overwrite) {
            return CompressResult(job.source.name, false, "skipped") to null
        }
        val source = job.source
        val target = job.target
        val temp = File(target.parentFile, target.name + ".part")
        runCatching { temp.delete() }

        val zipped = runCatching {
            ZipOutputStream(temp.outputStream().buffered()).use { zos ->
                zos.putNextEntry(ZipEntry(source.name))
                source.inputStream().buffered().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }.isSuccess

        // Verified before anything is removed: the archive must open and hold the ROM at
        // its full length. A short or unreadable archive is deleted and the ROM untouched.
        if (!zipped || !verify(temp, source.name, source.length())) {
            runCatching { temp.delete() }
            return CompressResult(source.name, false, "could not compress ${source.name}") to null
        }

        if (target.exists() && !target.delete()) {
            runCatching { temp.delete() }
            return CompressResult(source.name, false, "could not replace ${target.name}") to null
        }
        if (!temp.renameTo(target)) {
            runCatching { temp.delete() }
            return CompressResult(source.name, false, "could not finish ${target.name}") to null
        }
        AppLog.i("Compress", "compress: wrote ${target.name}")

        // Only now, with a verified archive in place, is removing the original safe.
        if (removeOriginal) {
            if (source.delete()) AppLog.i("Compress", "compress: removed ${source.name}")
            else AppLog.w("Compress", "compress: kept ${source.name}, could not remove it")
        }
        return CompressResult(source.name, true, "compressed ${source.name}") to
            CompressUndo(source, target, removedOriginal = removeOriginal && !source.exists())
    }

    /**
     * Reverses a run: the ROM back out of its archive when it was removed, the archive
     * dropped. Returns how many were put back.
     */
    suspend fun undo(actions: List<CompressUndo>): Int = withContext(Dispatchers.IO) {
        var restored = 0
        for (action in actions) {
            if (action.removedOriginal) {
                if (extract(action.target, action.source)) {
                    runCatching { action.target.delete() }
                    AppLog.i("Compress", "compress: restored ${action.source.name}")
                    restored++
                } else {
                    // Leave the archive rather than lose the ROM if extraction fails.
                    AppLog.w("Compress", "compress: could not restore ${action.source.name}")
                }
            } else {
                runCatching { action.target.delete() }
                restored++
            }
        }
        restored
    }

    /**
     * True when a `.nds` is DSi-exclusive, from the unit-code byte at header offset 0x12.
     *
     * 0x00 is a plain DS cart and 0x02 is a DSi-enhanced DS cart, both of which zip and
     * load fine. 0x03 is DSi-exclusive, the DSiWare and DSi-only titles that must stay
     * raw. Anything unreadable is treated as compressible rather than silently dropped.
     */
    private fun isDsiExclusive(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(0x13)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            read > 0x12 && (header[0x12].toInt() and 0xFF) == 0x03
        }
    }.getOrDefault(false)

    private fun verify(zip: File, entryName: String, size: Long): Boolean = runCatching {
        ZipFile(zip).use { zf -> zf.getEntry(entryName)?.size == size }
    }.getOrDefault(false)

    private fun extract(zip: File, dest: File): Boolean = runCatching {
        ZipFile(zip).use { zf ->
            val entry = zf.entries().asSequence().firstOrNull() ?: return false
            val temp = File(dest.parentFile, dest.name + ".part")
            zf.getInputStream(entry).use { input ->
                temp.outputStream().buffered().use { input.copyTo(it) }
            }
            if (temp.length() != entry.size) {
                temp.delete()
                return false
            }
            temp.renameTo(dest)
        }
    }.getOrDefault(false)
}
