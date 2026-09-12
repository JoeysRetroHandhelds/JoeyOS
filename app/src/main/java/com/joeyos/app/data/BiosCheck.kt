package com.joeyos.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** How one file came out of the check. */
enum class BiosStatus {
    /** Present, and its hash matches a known-good one. */
    Verified,

    /** Present, but we hold no hash to check it against. */
    Present,

    /** Present, and we hold hashes for it, but the file could not be read to hash it. */
    Unreadable,

    /** Present, but its hash is none we recognise: a wrong version or a bad dump. */
    Unrecognised,

    /** Not in the folder. A failure only when the file was required. */
    Missing,
}

/** One file's outcome. [required] is false for optional and any-of members. */
data class BiosFileResult(
    val name: String,
    val status: BiosStatus,
    val required: Boolean,
)

/**
 * One system's outcome.
 *
 * [satisfied] is the single question a user asks: will my games run. True when every
 * required file is present and every any-of group has at least one member. Optional
 * files never decide it.
 */
data class BiosSystemResult(
    val system: String,
    val shortname: String,
    val files: List<BiosFileResult>,
    val satisfied: Boolean,
) {
    /** True when nothing is missing and every present file that could be checked verified. */
    val allVerified: Boolean
        get() = satisfied && files.none {
            it.status == BiosStatus.Unrecognised || it.status == BiosStatus.Unreadable
        }
}

/**
 * Checks a folder of BIOS files against what each system needs. Ported from Chameleon.
 *
 * The folder is the one place a launcher can honestly look on Android: another emulator's
 * BIOS lives in its own private storage, which scoped storage hides from us even with
 * all-files access, so this reads a folder the user keeps and points their emulators at.
 *
 * Matching is by filename, case-insensitively, anywhere under the folder, because people
 * nest BIOS in per-system subfolders. Presence passes; a known hash upgrades that to
 * verified; a present file whose hash we cannot place is reported as unrecognised rather
 * than failed, since our hash table is deliberately partial.
 */
object BiosCheck {

    suspend fun run(
        folders: List<File>,
        systems: List<BiosSystem> = BiosRequirements.systems,
        /** When set, only these systems are checked (those with an emulator installed). */
        limitTo: Set<String>? = null,
    ): List<BiosSystemResult> = withContext(Dispatchers.IO) {
        val readable = folders.filter { it.isDirectory }
        if (readable.isEmpty()) return@withContext emptyList()

        // One index across every candidate folder, earlier folders winning a duplicate
        // name so the order they are passed in is their priority. Lowercased so a BIOS
        // named SCPH.BIN matches a rule written scph.bin, and recursive so BIOS nested in
        // per-system subfolders is still found.
        val byName = HashMap<String, File>()
        for (folder in readable) {
            runCatching {
                folder.walkTopDown().filter { it.isFile }.take(20000).forEach {
                    byName.putIfAbsent(it.name.lowercase(), it)
                }
            }
        }

        systems
            .filter { limitTo == null || it.shortname in limitTo }
            .map { system -> evaluate(system, byName) }
    }

    private fun evaluate(system: BiosSystem, byName: Map<String, File>): BiosSystemResult {
        val results = mutableListOf<BiosFileResult>()

        var satisfied = true

        system.required.forEach { file ->
            val status = statusOf(file, byName)
            results += BiosFileResult(file.name, status, required = true)
            if (status == BiosStatus.Missing) satisfied = false
        }

        system.anyOf.forEach { group ->
            val statuses = group.map { it to statusOf(it, byName) }
            val anyPresent = statuses.any { it.second != BiosStatus.Missing }
            if (!anyPresent) satisfied = false
            // Every variant is listed so a user sees which one they have, but a missing
            // variant is not a failure on its own: only the whole group being empty is.
            statuses.forEach { (file, status) ->
                results += BiosFileResult(file.name, status, required = false)
            }
        }

        system.optional.forEach { file ->
            results += BiosFileResult(file.name, statusOf(file, byName), required = false)
        }

        return BiosSystemResult(system.system, system.shortname, results, satisfied)
    }

    private fun statusOf(file: BiosFile, byName: Map<String, File>): BiosStatus {
        val found = byName[file.name.lowercase()] ?: return BiosStatus.Missing
        if (file.md5s.isEmpty()) return BiosStatus.Present
        // We hold hashes for this file, so a read failure is "could not confirm", not the
        // "nothing to check against" that Present means. The file is still on disk and the
        // emulator reads it directly, so this does not fail the system (see evaluate); it
        // only tells the user we could not verify it.
        val md5 = md5Of(found) ?: return BiosStatus.Unreadable
        return if (md5 in file.md5s) BiosStatus.Verified else BiosStatus.Unrecognised
    }

    private fun md5Of(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
