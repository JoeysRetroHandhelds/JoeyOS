package com.joeyos.app.data

import com.joeyos.app.AppLog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Compresses 3DS games to ZCCI with Azahar's compression tool. Ported from Chameleon.
 *
 * ZCCI is Azahar's zstd-wrapped CCI, so only Azahar's code makes one. Azahar has no
 * command-line tool of its own, but it does carry the compression as an internal CLI; a
 * tiny standalone wrapper around that (built as `jniLibs/<abi>/libazahar.so`) is what this
 * runs. joeysretrohandhelds.com's file-types guide recommends ZCCI for 3DS.
 *
 * The important part is what the tool does NOT do: it compresses the bytes as they are,
 * so an encrypted 3DS file would compress into a ZCCI that will not play in Azahar. The
 * tool cannot tell, so this does: it reads the NCCH crypto flag and refuses to compress an
 * encrypted file, telling the user to decrypt it first rather than making a broken ZCCI.
 *
 * Azahar is GPL-2.0-or-later, invoked as a separate executable; its licence and source
 * offer ship in the app's notices.
 */
object ThreeDsCompression {

    const val Shortname = "3ds"

    /** The ROM folder names people use for 3DS (JoeyOS reads `ROMs/<folder>`). */
    val FolderNames = RomFolders.namesFor(Shortname)

    /** Every 3DS folder under `ROMs/` on every storage volume. */
    fun romFolders(roots: List<File>): List<File> = RomFolders.systemDirs(roots, Shortname)

    /** Uncompressed 3DS containers. A .3ds is the same NCSD format as a .cci. */
    val InputExtensions = setOf("3ds", "cci")

    enum class Status {
        /** Decrypted and not yet compressed: ready to go. */
        Ready,

        /** A ZCCI of this name is already there. */
        Conflict,

        /** Encrypted: compressing it would make a ZCCI that cannot be played. */
        Encrypted,
    }

    data class Job(
        val source: File,
        val target: File,
        val status: Status,
        val conflict: String?,
    )

    data class Progress(val filesDone: Int, val filesTotal: Int, val current: String)
    data class Result(val name: String, val compressed: Boolean, val message: String)
    data class Undo(val target: File, val source: File, val removedSource: Boolean)
    data class Run(val results: List<Result>, val undo: List<Undo>)

    /** The bundled Azahar compression tool, or null when not shipped for this ABI. */
    fun binary(nativeLibraryDir: String): File? =
        File(nativeLibraryDir, "libazahar.so").takeIf { it.exists() && it.canExecute() }

    /** The 3DS files under [folder] that could be compressed, encrypted ones marked. */
    fun plan(folder: File): List<Job> {
        if (!folder.isDirectory) return emptyList()
        return folder.listFiles { f -> f.isFile }.orEmpty()
            .filter { it.extension.lowercase() in InputExtensions }
            .map { source ->
                val target = File(folder, source.nameWithoutExtension + "." + compressedExtension(source))
                val decrypted = isDecrypted(source)
                val status = when {
                    decrypted == false -> Status.Encrypted
                    target.exists() -> Status.Conflict
                    else -> Status.Ready
                }
                Job(
                    source = source,
                    target = target,
                    status = status,
                    conflict = if (status == Status.Conflict) "a ZCCI named ${target.name} is already there" else null,
                )
            }
            .sortedBy { it.source.name }
    }

    /**
     * The compressed extension Azahar gives an input, so the predicted output name matches
     * what the tool actually writes. An NCSD (.cci/.3ds) becomes .zcci; the raw NCCH and
     * homebrew forms have their own, kept here so extending [InputExtensions] later cannot
     * silently mispredict the name.
     */
    private fun compressedExtension(source: File): String = when (source.extension.lowercase()) {
        "cxi", "app" -> "zcxi"
        "3dsx" -> "z3dsx"
        else -> "zcci" // cci, 3ds (NCSD)
    }

    /** How many 3DS games under [folder] are already ZCCI. */
    fun doneCount(folder: File): Int {
        if (!folder.isDirectory) return 0
        return folder.listFiles { f -> f.isFile && f.extension.equals("zcci", ignoreCase = true) }
            ?.size ?: 0
    }

    /**
     * Whether a 3DS file is decrypted, from the NCCH crypto flag, or null when it is not a
     * recognised 3DS container.
     *
     * A .3ds/.cci is an NCSD wrapping NCCH partitions; a raw NCCH sits at the start. The
     * NCCH flags byte at offset 0x18F has bit 0x04 (NoCrypto) set when the content is
     * decrypted. No keys are needed to read this; the same small-header trick the DSiWare
     * check uses.
     */
    fun isDecrypted(file: File): Boolean? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.seek(0x100); raf.readFully(magic)
            val tag = String(magic, Charsets.US_ASCII)
            val ncchOffset: Long = when (tag) {
                "NCSD" -> {
                    // Partition 0 offset, at 0x120, in 0x200-byte media units.
                    val mu = ByteArray(4)
                    raf.seek(0x120); raf.readFully(mu)
                    val units = (mu[0].toLong() and 0xFF) or
                        ((mu[1].toLong() and 0xFF) shl 8) or
                        ((mu[2].toLong() and 0xFF) shl 16) or
                        ((mu[3].toLong() and 0xFF) shl 24)
                    units * 0x200
                }
                "NCCH" -> 0L
                else -> return null
            }
            // Confirm the partition really is NCCH before trusting the flag.
            raf.seek(ncchOffset + 0x100); raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "NCCH") return null
            raf.seek(ncchOffset + 0x18F)
            val flag = raf.read()
            if (flag < 0) return null
            (flag and 0x04) != 0
        }
    }.getOrNull()

    suspend fun compressAll(
        tool: File,
        jobs: List<Job>,
        removeSource: Boolean,
        overwrite: (Job) -> Boolean,
        shouldStop: () -> Boolean = { false },
        onProgress: (Progress) -> Unit,
    ): Run = withContext(Dispatchers.IO) {
        val total = jobs.size
        var done = 0
        val results = mutableListOf<Result>()
        val undos = mutableListOf<Undo>()
        for (job in jobs) {
            if (shouldStop()) break
            onProgress(Progress(done, total, job.source.name))
            val (result, undo) = compress(tool, job, removeSource, overwrite(job), shouldStop)
            results += result
            undo?.let { undos += it }
            done++
        }
        onProgress(Progress(done, total, ""))
        Run(results, undos)
    }

    private fun compress(
        tool: File,
        job: Job,
        removeSource: Boolean,
        overwrite: Boolean,
        shouldStop: () -> Boolean,
    ): Pair<Result, Undo?> {
        if (job.status == Status.Encrypted) {
            return Result(job.source.name, false, "encrypted, left alone") to null
        }
        if (job.status == Status.Conflict && !overwrite) {
            return Result(job.source.name, false, "skipped") to null
        }
        // An existing ZCCI is set aside, not deleted, until the new one is known to be good: the
        // tool picks the output name itself, so it can't be written somewhere else first.
        val previous = File(job.target.parentFile, job.target.name + ".old")
        if (job.target.exists()) {
            previous.delete()
            if (!job.target.renameTo(previous)) {
                return Result(job.source.name, false, "could not replace ${job.target.name}") to null
            }
        }
        fun putBack() { runCatching { job.target.delete() }; if (previous.exists()) previous.renameTo(job.target) }

        // azahar-tool -c <input> -o <dir>: it names the output itself (base + .zcci) in dir.
        val ran = runTool(tool, listOf("-c", job.source.absolutePath, "-o", job.source.parent), shouldStop)
        // A good ZCCI is never tiny: a tool that exits cleanly but leaves a stub mustn't count as
        // done (the source may be deleted next).
        val size = if (job.target.isFile) job.target.length() else 0L
        val plausible = size >= 64 * 1024 && size >= job.source.length() / 50
        if (!ran || !plausible) {
            AppLog.w("Compress", "3ds: ${job.source.name} failed (ran=$ran, output=$size bytes)")
            putBack()
            return Result(job.source.name, false, "could not compress ${job.source.name}") to null
        }
        previous.delete()
        AppLog.i("Compress", "3ds: wrote ${job.target.name}")

        val removed = removeSource && job.source.delete()
        if (removed) AppLog.i("Compress", "3ds: removed ${job.source.name}")
        else if (removeSource) AppLog.w("Compress", "3ds: kept ${job.source.name}, could not remove it")
        return Result(job.source.name, true, "compressed ${job.source.name}") to
            Undo(job.target, job.source, removed)
    }

    /**
     * Reverses a run. Keep-both drops the ZCCI; a removed source is decompressed back with
     * the tool.
     */
    suspend fun undo(tool: File, actions: List<Undo>): Int = withContext(Dispatchers.IO) {
        var restored = 0
        for (action in actions) {
            if (!action.removedSource) {
                runCatching { action.target.delete() }
                restored++
                continue
            }
            val ran = runTool(tool, listOf("-x", action.target.absolutePath, "-o", action.target.parent), { false })
            if (ran && action.source.isFile) {
                runCatching { action.target.delete() }
                AppLog.i("Compress", "3ds: restored ${action.source.name}")
                restored++
            } else {
                AppLog.w("Compress", "3ds: could not restore ${action.source.name}, kept the ZCCI")
            }
        }
        restored
    }

    // Azahar prints no progress, so there is nothing to parse; NativeTool still gives it
    // the prompt cancel every tool needs.
    private fun runTool(tool: File, args: List<String>, shouldStop: () -> Boolean): Boolean =
        NativeTool.run(tool, args, shouldStop)
}
