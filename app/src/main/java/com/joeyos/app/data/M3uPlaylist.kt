package com.joeyos.app.data

import com.joeyos.app.AppLog

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Where a disc file currently sits relative to the platform folder. */
enum class DiscLocation { Root, Hidden }

/** One disc of a multi-disc game. */
data class DiscFile(val file: File, val number: Int, val location: DiscLocation)

/** Whether a game can be done cleanly, or needs a decision first. */
enum class M3uStatus {
    /** Nothing in the way: create the playlist and move the discs. */
    Ready,

    /** Something already there, so the user is asked before it is touched. */
    Conflict,
}

/**
 * One multi-disc game the tool would turn into a playlist.
 *
 * [conflicts] is empty unless [status] is Conflict, and each line is a plain reason to
 * show the user: a playlist already there, or a disc that would land on a different file
 * already hidden.
 */
data class M3uGame(
    val system: String,
    val folder: File,
    val baseName: String,
    val discs: List<DiscFile>,
    val m3u: File,
    val status: M3uStatus,
    val conflicts: List<String>,
)

/** What one apply did. */
data class M3uResult(val game: String, val created: Boolean, val message: String)

/** One file's move, kept so it can be put back. */
data class M3uMove(val from: File, val to: File)

/**
 * Everything one game's apply changed, enough to reverse it.
 *
 * [moves] can be walked backwards to return the discs, and [createdM3u] is the playlist to
 * delete. A move made by overwriting a file the user chose to replace is not reversible,
 * since the replaced file is gone; only clean moves are recorded here.
 */
data class M3uUndoAction(val game: String, val moves: List<M3uMove>, val createdM3u: File?)

/** Progress across a whole run, for a bar and a current-file line. */
data class M3uProgress(val filesDone: Int, val filesTotal: Int, val current: String)

/** The outcome of a run: what happened, and what it would take to undo it. */
data class M3uRunResult(val results: List<M3uResult>, val undo: List<M3uUndoAction>)

/**
 * Turns multi-disc games into .m3u playlists, the way joeysretrohandhelds.com's guide
 * lays them out. Ported from Chameleon.
 *
 * The convention, learned from the guide and the live library rather than copied from
 * another tool: discs are named `Game (Region) (Disc N)`, the loose disc files move into
 * a `.hidden` subfolder so the frontend stops showing each disc as its own game, and a
 * `Game (Region).m3u` in the platform root lists them as `.hidden/<file>`, one per line,
 * sorted, with no trailing blank line.
 *
 * Only the systems whose emulators actually read an m3u are offered. PS2's do not, which
 * is why a loose PS2 disc set is left exactly where it is.
 *
 * It moves files, so it never acts on its own: a caller previews the plan, and anything
 * that would overwrite is a [M3uStatus.Conflict] the user decides on rather than a silent
 * skip or a silent clobber.
 */
object M3uPlaylist {

    /**
     * The consoles whose emulators load an m3u (PS2's do not, so it is deliberately absent),
     * each with the ROM folder names people use for it. JoeyOS reads `ROMs/<folder>` directly,
     * so a console is found under any of its common names, case-insensitively.
     */
    data class M3uSystem(val label: String, val folderNames: Set<String>)

    val SupportedSystems = listOf(
        M3uSystem("PlayStation", RomFolders.namesFor("psx")),
        M3uSystem("Dreamcast", RomFolders.namesFor("dreamcast")),
        M3uSystem("Saturn", RomFolders.namesFor("saturn")),
        M3uSystem("Sega CD", RomFolders.namesFor("segacd")),
        M3uSystem("PC Engine CD", RomFolders.namesFor("pcenginecd")),
        M3uSystem("Neo Geo CD", RomFolders.namesFor("neogeocd")),
        M3uSystem("3DO", RomFolders.namesFor("3do")),
        M3uSystem("PC-FX", RomFolders.namesFor("pcfx")),
    )

    /** Every `ROMs/<folder>` on every storage volume, grouped by the console it holds. */
    fun romFolders(roots: List<File>): Map<M3uSystem, List<File>> =
        RomFolders.group(roots, SupportedSystems) { it.folderNames }

    private val DiscExtensions = setOf("chd", "cue", "gdi", "iso", "ccd", "mds", "nrg")

    // "Disc" only, as No-Intro and Redump write it, with their optional " of N" total.
    // Not Disk or CD: those are other conventions, and the user's library uses Disc.
    private val DiscTag = Regex("""\s*\(Disc (\d+)(?: of \d+)?\)""", RegexOption.IGNORE_CASE)

    /** How many multi-disc games under [folder] already have their playlist. */
    fun doneCount(folder: File): Int {
        if (!folder.isDirectory) return 0
        return folder.listFiles { f -> f.isFile && f.extension.equals("m3u", ignoreCase = true) }
            ?.size ?: 0
    }

    /**
     * The playlists that could be made under [folder], newest decisions first.
     *
     * [system] tags each game so a caller can group by console and, after, rescan or
     * rescrape exactly the ones that changed. Defaults to the folder's own name.
     */
    fun plan(folder: File, system: String = folder.name): List<M3uGame> {
        if (!folder.isDirectory) return emptyList()
        val hiddenDir = File(folder, ".hidden")
        val root = folder.listFiles()?.filter { it.isFile }.orEmpty()
        val hidden = hiddenDir.listFiles()?.filter { it.isFile }.orEmpty()

        val discs = (root.map { it to DiscLocation.Root } + hidden.map { it to DiscLocation.Hidden })
            .mapNotNull { (file, location) ->
                if (file.extension.lowercase() !in DiscExtensions) return@mapNotNull null
                val match = DiscTag.find(file.name) ?: return@mapNotNull null
                val number = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val base = DiscTag.replaceFirst(file.name, "").removeSuffix("." + file.extension)
                base to DiscFile(file, number, location)
            }

        return discs.groupBy({ it.first }, { it.second }).mapNotNull { (base, members) ->
            val sorted = members.distinctBy { it.file.name }.sortedBy { it.number }
            // Multi-disc only: a single disc needs no playlist.
            if (sorted.size < 2) return@mapNotNull null

            val m3u = File(folder, "$base.m3u")
            val rootDiscs = sorted.filter { it.location == DiscLocation.Root }

            // Nothing to do when the discs are already hidden and the playlist is written.
            // Checked before conflicts, so a finished game is skipped rather than reported
            // as a playlist that is "in the way" of itself.
            val hasWork = rootDiscs.isNotEmpty() || !m3u.exists()
            if (!hasWork) return@mapNotNull null

            val conflicts = buildList {
                // Only a conflict because there is work to do: regenerating over a
                // playlist that is already there would replace it.
                if (m3u.exists()) add("a playlist named ${m3u.name} is already there")
                rootDiscs.forEach { disc ->
                    val target = File(hiddenDir, disc.file.name)
                    if (target.exists() && target.absolutePath != disc.file.absolutePath) {
                        add("${disc.file.name} is already in .hidden")
                    }
                }
            }

            M3uGame(
                system = system,
                folder = folder,
                baseName = base,
                discs = sorted,
                m3u = m3u,
                status = if (conflicts.isEmpty()) M3uStatus.Ready else M3uStatus.Conflict,
                conflicts = conflicts,
            )
        }.sortedBy { it.baseName }
    }

    /** The exact bytes written: each disc as `.hidden/<file>`, sorted, no trailing line. */
    fun contentFor(game: M3uGame): String =
        game.discs.sortedBy { it.number }.joinToString("\n") { ".hidden/${it.file.name}" }

    /**
     * Creates one game's playlist and moves its discs, all or nothing.
     *
     * A move that fails partway rolls back the discs it had already moved, so a game is
     * never left with some discs hidden, some loose, and no working playlist. The
     * playlist is written only once every disc is safely in place. Used by the batch
     * below and by tests.
     */
    suspend fun apply(
        game: M3uGame,
        overwrite: Boolean,
        onFile: (String) -> Unit = {},
    ): Pair<M3uResult, M3uUndoAction?> = withContext(Dispatchers.IO) {
        if (game.status == M3uStatus.Conflict && !overwrite) {
            return@withContext M3uResult(game.baseName, false, "skipped") to null
        }
        val hiddenDir = File(game.folder, ".hidden")
        if (!hiddenDir.isDirectory && !hiddenDir.mkdirs()) {
            return@withContext M3uResult(game.baseName, false, "could not make .hidden") to null
        }

        val moved = mutableListOf<M3uMove>()
        for (disc in game.discs.filter { it.location == DiscLocation.Root }) {
            val target = File(hiddenDir, disc.file.name)
            if (target.absolutePath == disc.file.absolutePath) continue
            onFile(disc.file.name)

            // Overwriting a real file: only when the user asked, and logged because it is
            // the one place this deletes something they had.
            if (target.exists()) {
                if (!overwrite) {
                    rollback(moved)
                    return@withContext M3uResult(game.baseName, false, "skipped") to null
                }
                AppLog.w("M3uPlaylist", "m3u: overwriting ${target.name} in ${game.folder.name}/.hidden")
                if (!target.delete()) {
                    rollback(moved)
                    return@withContext M3uResult(game.baseName, false, "could not replace ${target.name}") to null
                }
            }

            if (safeMove(disc.file, target)) {
                moved += M3uMove(disc.file, target)
                AppLog.i("M3uPlaylist", "m3u: moved ${disc.file.name} into ${game.folder.name}/.hidden")
            } else {
                // Put back whatever this game had already moved before giving up.
                rollback(moved)
                return@withContext M3uResult(game.baseName, false, "could not move ${disc.file.name}; left unchanged") to null
            }
        }

        val wrote = runCatching { game.m3u.writeText(contentFor(game)) }.isSuccess
        if (!wrote) {
            rollback(moved)
            return@withContext M3uResult(game.baseName, false, "could not write ${game.m3u.name}; left unchanged") to null
        }
        AppLog.i("M3uPlaylist", "m3u: wrote ${game.m3u.name}")
        M3uResult(game.baseName, true, "created ${game.m3u.name}") to
            M3uUndoAction(game.baseName, moved, game.m3u)
    }

    /**
     * Applies many games with progress, and returns what it would take to undo them.
     *
     * Cancellable between games: a cancelled run stops starting new ones, and because
     * each game is all-or-nothing, the most that can be in flight is the one game being
     * moved, which finishes or rolls itself back. Nothing is ever left half-done.
     */
    suspend fun applyAll(
        games: List<M3uGame>,
        overwrite: (M3uGame) -> Boolean,
        /** Checked between games; returning true stops the run cleanly, keeping the undo. */
        shouldStop: () -> Boolean = { false },
        onProgress: (M3uProgress) -> Unit,
    ): M3uRunResult = withContext(Dispatchers.IO) {
        val total = games.sumOf { g -> g.discs.count { it.location == DiscLocation.Root } }
        var done = 0
        val results = mutableListOf<M3uResult>()
        val undos = mutableListOf<M3uUndoAction>()
        for (game in games) {
            // Stopped between games, never mid-game, so a cancel leaves no game half-done
            // and every game already finished can still be undone.
            if (shouldStop()) break
            val (result, undo) = apply(game, overwrite(game)) { name ->
                onProgress(M3uProgress(done, total, name))
                done++
            }
            results += result
            undo?.let { undos += it }
        }
        onProgress(M3uProgress(done, total, ""))
        M3uRunResult(results, undos)
    }

    /**
     * Reverses a run: discs back where they were, the playlists it made removed.
     *
     * Returns how many games were put back. Files the user chose to overwrite are not
     * restored, since replacing them destroyed the originals; only clean moves reverse.
     */
    suspend fun undo(actions: List<M3uUndoAction>): Int = withContext(Dispatchers.IO) {
        var restored = 0
        for (action in actions) {
            rollback(action.moves)
            action.createdM3u?.let { runCatching { it.delete() } }
            AppLog.i("M3uPlaylist", "m3u: undid ${action.game}")
            restored++
        }
        restored
    }

    private fun rollback(moved: List<M3uMove>) {
        // Backwards, so the last disc moved is the first put back.
        moved.asReversed().forEach { move ->
            if (!safeMove(move.to, move.from)) {
                AppLog.w("M3uPlaylist", "m3u: could not put ${move.to.name} back")
            }
        }
    }

    /**
     * Moves a file without ever risking the source before the destination is proven.
     *
     * An atomic rename on the same volume is the fast, safe path and is tried first. When
     * that is not possible (a different volume), the file is copied to a temporary name,
     * its length checked against the source, and only then is the source removed and the
     * temp put in place. A failure anywhere leaves the source exactly as it was.
     */
    private fun safeMove(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) return true

        val size = src.length()
        val temp = File(dst.parentFile, dst.name + ".part")
        runCatching { temp.delete() }
        val copied = runCatching { src.copyTo(temp, overwrite = true) }.isSuccess
        if (!copied || temp.length() != size) {
            runCatching { temp.delete() }
            return false
        }
        if (!temp.renameTo(dst)) {
            val moved = runCatching { temp.copyTo(dst, overwrite = true) }.isSuccess &&
                dst.length() == size
            if (!moved) {
                runCatching { temp.delete() }
                return false
            }
            runCatching { temp.delete() }
        }
        // The destination is verified; only now is the source safe to remove.
        runCatching { src.delete() }
        return true
    }
}
