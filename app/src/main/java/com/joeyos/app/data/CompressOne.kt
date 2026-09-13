package com.joeyos.app.data

import android.content.Context
import com.joeyos.app.AppLog
import java.io.File

/**
 * Compresses one freshly made game (a patched ROM, a romhack) in the best format for its console,
 * the same choices as the Compress ROMs tool: zip for cartridges, CHD for PlayStation / PS2 / PSP
 * discs, RVZ for GameCube and Wii, ZCCI for 3DS. Used by "Save compressed" in the save popup.
 *
 * A patched disc that comes out as a lone .bin has no cue sheet to make a CHD from, so it isn't
 * offered; neither is a format whose tool isn't bundled for this device (dolphin-tool and the
 * Azahar compressor are 64-bit only).
 */
object CompressOne {

    enum class Format(val label: String, val extension: String) {
        Zip("ZIP", "zip"), Chd("CHD", "chd"), Rvz("RVZ", "rvz"), Zcci("ZCCI", "zcci")
    }

    /**
     * The compressed format [fileName] can be saved in, or null. [console] is the romhack tool's
     * console key when known; otherwise the console is read from [folder]'s name (ROMs/psx, …),
     * which is what tells a PlayStation iso from a GameCube one.
     */
    fun formatFor(context: Context, fileName: String, console: String?, folder: File?): Format? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val libDir = context.applicationInfo.nativeLibraryDir
        val folderName = folder?.name?.lowercase()
        fun isChd() = ChdConversion.Systems.any { s ->
            (s.shortname == console || folderName in s.folderNames) && ext in s.inputExtensions
        }
        fun isRvz() = RvzConversion.Systems.any { s ->
            (s.shortname == console || folderName in s.folderNames) && ext in s.inputExtensions
        }
        return when {
            ext in RomCompression.ZipExtensions -> Format.Zip
            ext in ThreeDsCompression.InputExtensions -> Format.Zcci.takeIf { ThreeDsCompression.binary(libDir) != null }
            isRvz() -> Format.Rvz.takeIf { RvzConversion.binary(libDir) != null }
            isChd() -> Format.Chd.takeIf { ChdConversion.binary(libDir) != null }
            else -> null
        }
    }

    /**
     * Compresses [file] in place to [format], removing the uncompressed file once the result is
     * verified. The compressed file, or null (logged) when it couldn't be done — the uncompressed
     * file is then left as it was.
     */
    suspend fun compress(context: Context, file: File, format: Format, console: String?): File? {
        val libDir = context.applicationInfo.nativeLibraryDir
        val folder = file.parentFile ?: return null
        val result: File? = when (format) {
            Format.Zip -> RomCompression.zipOne(file)
            Format.Chd -> {
                val tool = ChdConversion.binary(libDir) ?: return null
                val sys = ChdConversion.Systems.firstOrNull { it.shortname == console || folder.name.lowercase() in it.folderNames }
                    ?: return null
                val job = ChdConversion.ChdJob(sys.shortname, sys.mode, file, ChdConversion.referencedFiles(file),
                    File(folder, file.nameWithoutExtension + ".chd"), ChdConversion.ChdStatus.Ready, null)
                ChdConversion.convertAll(tool, listOf(job), removeSource = true, overwrite = { false }) {}
                    .undo.firstOrNull()?.target
            }
            Format.Rvz -> {
                val tool = RvzConversion.binary(libDir) ?: return null
                val job = RvzConversion.RvzJob(console ?: "gcwii", file, File(folder, file.nameWithoutExtension + ".rvz"),
                    RvzConversion.RvzStatus.Ready, null)
                RvzConversion.convertAll(tool, listOf(job), removeSource = true, overwrite = { false }) {}
                    .undo.firstOrNull()?.target
            }
            Format.Zcci -> {
                val tool = ThreeDsCompression.binary(libDir) ?: return null
                val job = ThreeDsCompression.plan(folder).firstOrNull { it.source.absolutePath == file.absolutePath }
                    ?.takeIf { it.status == ThreeDsCompression.Status.Ready } ?: return null
                ThreeDsCompression.compressAll(tool, listOf(job), removeSource = true, overwrite = { false }) {}
                    .undo.firstOrNull()?.target
            }
        }
        if (result == null) AppLog.w("Compress", "Couldn't save ${file.name} as ${format.label}; left uncompressed")
        return result
    }
}
