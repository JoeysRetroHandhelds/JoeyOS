package com.joeyos.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.CRC32

/**
 * Applies IPS, UPS, BPS, PPF, APS and xdelta/VCDIFF patches to a base ROM, on-device and in
 * pure Kotlin.
 *
 * The scene ships fixes as patches, not as modified ROMs: a romhack or a fan translation is
 * a byte-diff against the original, so distributing it is legal where shipping the whole
 * game would not be. Applying one is the last step before it will run, and every other
 * frontend leaves it to a separate tool. Doing it here keeps a hack a hold away from
 * playing.
 *
 * Six formats between them cover what circulates. IPS is the old offset/length diff with no
 * integrity check. UPS and BPS are the modern cartridge pair that carry CRC32s of the input,
 * the output and the patch, so a wrong base ROM or a corrupt patch is caught rather than
 * silently producing a broken file. PPF is the PlayStation/disc convention. APS comes in an
 * N64 and a GBA flavour, told apart by their magic. xdelta/VCDIFF (RFC 3284) is the modern
 * choice for large patches; we handle the same subset the reference does (no secondary
 * compressor, the default code table), which is what real patches use. We verify checksums
 * wherever the format provides them.
 *
 * The original is never touched: apply writes a new file beside it. That is the one rule
 * that makes an undo unnecessary and a mistake harmless.
 *
 * Format details follow the specs the community keeps (byuu's BPS/UPS notes, the IPS
 * convention); RomPatcher.js (MIT, Marc Robledo) is the reference implementation these were
 * checked against. No code is taken from it.
 */
object RomPatcher {

    enum class Kind { Ips, Ups, Bps, Ppf, Aps, Vcdiff }

    /** What went wrong, phrased for someone who just wants the hack to run. */
    class PatchException(message: String) : Exception(message)

    fun kindOf(patch: File): Kind? = when (patch.extension.lowercase()) {
        "ips" -> Kind.Ips
        "ups" -> Kind.Ups
        "bps" -> Kind.Bps
        "ppf" -> Kind.Ppf
        "aps" -> Kind.Aps
        "xdelta", "xdelta3", "vcdiff", "vcd" -> Kind.Vcdiff
        else -> null
    }

    /**
     * Reads [patch], applies it to [rom], and writes the result to [output]. The base ROM is
     * only read. Throws [PatchException] with a readable reason on any mismatch.
     */
    suspend fun apply(patch: File, rom: File, output: File): Unit = withContext(Dispatchers.IO) {
        val result = applyBytes(patch.name, patch.readBytes(), rom.readBytes())
        output.writeBytes(result)
    }

    /**
     * Applies a patch chosen by its file name to [rom]'s bytes. The single dispatch point, so
     * both the file API above and the UI go through the same guard: a patch whose offsets run
     * off the end of the ROM or the patch (a truncated or wrong-ROM file the format has no
     * checksum to catch) surfaces as a readable [PatchException] rather than an index crash.
     */
    fun applyBytes(patchName: String, patch: ByteArray, rom: ByteArray): ByteArray = try {
        when (kindOf(File(patchName))) {
            Kind.Ips -> applyIps(patch, rom)
            Kind.Ups -> applyUps(patch, rom)
            Kind.Bps -> applyBps(patch, rom)
            Kind.Ppf -> applyPpf(patch, rom)
            Kind.Aps -> applyAps(patch, rom)
            Kind.Vcdiff -> applyVcdiff(patch, rom)
            null -> throw PatchException("Unsupported patch format.")
        }
    } catch (e: PatchException) {
        throw e
    } catch (e: IndexOutOfBoundsException) {
        throw PatchException("This patch is damaged, or is not made for this ROM.")
    } catch (e: NegativeArraySizeException) {
        throw PatchException("This patch is damaged, or is not made for this ROM.")
    }

    // ---- IPS -------------------------------------------------------------------------

    /**
     * IPS is a list of records, each an offset and either a run of literal bytes or, when the
     * length is zero, a run-length pair. "EOF" ends it; an optional three-byte length after
     * that truncates the output. No checksum exists in the format, so none is verified.
     */
    fun applyIps(patch: ByteArray, rom: ByteArray): ByteArray {
        if (patch.size < 8 || String(patch, 0, 5, Charsets.US_ASCII) != "PATCH")
            throw PatchException("This is not a valid IPS patch.")

        // Parse the records once, working out the final size as we go, then apply into a
        // single ByteArray. A byte-boxing list here turned a 32 MB ROM into hundreds of MB
        // on the heap; a record whose byte value happens to be part of the ROM does not.
        // rleValue set marks a run; otherwise dataAt points at the literal bytes in [patch].
        class Rec(val offset: Int, val len: Int, val rleValue: Byte, val dataAt: Int)
        val records = ArrayList<Rec>()
        var p = 5
        fun u8() = patch[p++].toInt() and 0xFF
        fun u16() = (u8() shl 8) or u8()
        fun u24() = (u8() shl 16) or (u8() shl 8) or u8()

        var truncate = -1
        var end = rom.size
        while (p + 3 <= patch.size) {
            // "EOF" as the offset marks the end.
            if (patch[p].toInt() == 0x45 && patch[p + 1].toInt() == 0x4F && patch[p + 2].toInt() == 0x46) {
                p += 3
                if (p + 3 <= patch.size) truncate = u24() // optional truncation length
                break
            }
            val offset = u24()
            val length = u16()
            if (length == 0) {
                // RLE: a count and one byte to repeat.
                if (p + 3 > patch.size) throw PatchException("This IPS patch is truncated.")
                val runLength = u16()
                val value = patch[p++]
                records += Rec(offset, runLength, value, -1)
                end = maxOf(end, offset + runLength)
            } else {
                if (p + length > patch.size) throw PatchException("This IPS patch is truncated.")
                records += Rec(offset, length, 0, p)
                p += length
                end = maxOf(end, offset + length)
            }
        }

        // copyOf(end) zero-pads when a record wrote past the source, which is what IPS's
        // grow-the-file behaviour means.
        val out = rom.copyOf(end)
        for (r in records) {
            if (r.dataAt < 0) java.util.Arrays.fill(out, r.offset, r.offset + r.len, r.rleValue)
            else System.arraycopy(patch, r.dataAt, out, r.offset, r.len)
        }
        // Truncation only ever shrinks the output; a value past the end is ignored.
        return if (truncate in 0 until end) out.copyOf(truncate) else out
    }

    // ---- UPS -------------------------------------------------------------------------

    /**
     * UPS stores the input and output sizes, then a series of blocks: a gap to skip, then
     * bytes to XOR against the source until a zero terminator. It carries CRC32s of the
     * input, output and patch, which we check.
     */
    fun applyUps(patch: ByteArray, rom: ByteArray): ByteArray {
        if (patch.size < 4 || String(patch, 0, 4, Charsets.US_ASCII) != "UPS1")
            throw PatchException("This is not a valid UPS patch.")

        // The last twelve bytes are three CRC32s; the body is between the header and those.
        val footer = patch.size - 12
        if (footer < 4) throw PatchException("This UPS patch is truncated.")
        val inputCrc = le32(patch, footer)
        val outputCrc = le32(patch, footer + 4)
        val patchCrc = le32(patch, footer + 8)
        // The patch checksum covers the whole file except its own 4 bytes (so it includes the
        // input and output checksums that precede it), not just the body before the footer.
        if (crc32(patch, 0, patch.size - 4) != patchCrc)
            throw PatchException("This patch file is damaged: its download looks incomplete or corrupted. Download it again and retry.")
        if (crc32(rom, 0, rom.size) != inputCrc)
            throw PatchException("This patch is for a different version of this game than the ROM you chose.")

        val r = Reader(patch, 4)
        val inputSize = r.vlv()
        val outputSize = r.vlv()
        val out = ByteArray(maxOf(outputSize, inputSize))
        // Start as a copy of the source; the patch only records the differing bytes.
        System.arraycopy(rom, 0, out, 0, minOf(rom.size, out.size))

        var pos = 0
        while (r.pos < footer) {
            pos += r.vlv()
            while (true) {
                val x = r.u8()
                if (x == 0) break
                if (pos < out.size) {
                    val base = if (pos < rom.size) rom[pos].toInt() and 0xFF else 0
                    out[pos] = (base xor x).toByte()
                }
                pos++
            }
            pos++
        }
        val trimmed = if (out.size != outputSize) out.copyOf(outputSize) else out
        if (crc32(trimmed, 0, trimmed.size) != outputCrc)
            throw PatchException("The patched ROM did not match the expected checksum.")
        return trimmed
    }

    // ---- BPS -------------------------------------------------------------------------

    /**
     * BPS is the richest of the three: four actions that copy runs from the source, from the
     * patch, or from earlier in the source or output. It carries CRC32s of the source, target
     * and patch, all checked.
     */
    fun applyBps(patch: ByteArray, rom: ByteArray): ByteArray {
        if (patch.size < 4 || String(patch, 0, 4, Charsets.US_ASCII) != "BPS1")
            throw PatchException("This is not a valid BPS patch.")

        val footer = patch.size - 12
        if (footer < 4) throw PatchException("This BPS patch is truncated.")
        val sourceCrc = le32(patch, footer)
        val targetCrc = le32(patch, footer + 4)
        val patchCrc = le32(patch, footer + 8)
        // The patch checksum covers the whole file except its own 4 bytes (so it includes the
        // source and target checksums that precede it), not just the body before the footer.
        if (crc32(patch, 0, patch.size - 4) != patchCrc)
            throw PatchException("This patch file is damaged: its download looks incomplete or corrupted. Download it again and retry.")
        if (crc32(rom, 0, rom.size) != sourceCrc)
            throw PatchException("This patch is for a different version of this game than the ROM you chose.")

        val r = Reader(patch, 4)
        r.vlv() // source size, not needed to apply
        val targetSize = r.vlv()
        val metadataSize = r.vlv()
        r.pos += metadataSize

        val out = ByteArray(targetSize)
        var outPos = 0
        var sourceRelative = 0
        var targetRelative = 0
        while (r.pos < footer) {
            val data = r.vlv()
            val command = data and 3
            val length = (data shr 2) + 1
            when (command) {
                0 -> { // SourceRead: same offset in the source
                    for (i in 0 until length) {
                        out[outPos] = rom[outPos]
                        outPos++
                    }
                }
                1 -> { // TargetRead: literal bytes from the patch
                    for (i in 0 until length) out[outPos++] = patch[r.pos++]
                }
                2 -> { // SourceCopy: a run from anywhere in the source
                    sourceRelative += r.signedVlv()
                    for (i in 0 until length) out[outPos++] = rom[sourceRelative++]
                }
                3 -> { // TargetCopy: a run from what has already been written
                    targetRelative += r.signedVlv()
                    for (i in 0 until length) {
                        out[outPos++] = out[targetRelative++]
                    }
                }
            }
        }
        if (crc32(out, 0, out.size) != targetCrc)
            throw PatchException("The patched ROM did not match the expected checksum.")
        return out
    }

    // ---- PPF -------------------------------------------------------------------------

    /**
     * PPF overwrites runs of bytes at absolute offsets, the disc-patch convention. Versions
     * 1, 2 and 3 differ only in the header and whether offsets are 32 or 64 bit. It has no
     * source checksum, so a wrong base disc is not caught; undo data, when present, is
     * skipped, since we only ever patch forward to a new file.
     */
    fun applyPpf(patch: ByteArray, rom: ByteArray): ByteArray {
        if (patch.size < 58 || String(patch, 0, 3, Charsets.US_ASCII) != "PPF")
            throw PatchException("This is not a valid PPF patch.")
        val version = String(patch, 3, 2, Charsets.US_ASCII).toIntOrNull()?.div(10)
        if (version == null || version != (u8(patch, 5) + 1) || version > 3)
            throw PatchException("This PPF version is not supported.")

        val c = Cur(patch, 6)
        c.pos += 50 // description
        var undoData = false
        var blockCheck = false
        when (version) {
            3 -> {
                c.pos += 1 // image type
                blockCheck = c.u8() != 0
                undoData = c.u8() != 0
                c.pos += 1 // dummy
            }
            2 -> {
                blockCheck = true
                c.pos += 4 // input file size
            }
        }
        if (blockCheck) c.pos += 1024

        // First pass: how far the records reach, so the output can grow past the source.
        data class Rec(val offset: Int, val at: Int, val len: Int)
        val records = ArrayList<Rec>()
        var maxEnd = rom.size
        while (c.pos + (if (version == 3) 9 else 5) <= patch.size) {
            // The optional FILE_ID.DIZ block ends the records.
            if (c.pos + 4 <= patch.size && String(patch, c.pos, 4, Charsets.US_ASCII) == "@BEG") break
            val offset = if (version == 3) {
                val lo = c.u32le(); val hi = c.u32le()
                lo + hi * 0x1_0000_0000L
            } else c.u32le().toLong()
            val len = c.u8()
            if (offset < 0 || offset > Int.MAX_VALUE)
                throw PatchException("This PPF patch addresses past the supported size.")
            val start = offset.toInt()
            records.add(Rec(start, c.pos, len))
            c.pos += len
            if (undoData) c.pos += len
            maxEnd = maxOf(maxEnd, start + len)
        }

        val out = rom.copyOf(maxEnd)
        for (r in records) {
            System.arraycopy(patch, r.at, out, r.offset, r.len)
        }
        return out
    }

    // ---- APS -------------------------------------------------------------------------

    /**
     * APS has two unrelated formats sharing an extension. The N64 one ("APS10") writes runs,
     * with a run-length shorthand, at absolute offsets. The GBA one ("APS1") XORs 64 KB
     * blocks and carries the source size, which we check to catch a wrong base ROM. They are
     * told apart by the magic, longest first since one prefixes the other.
     */
    fun applyAps(patch: ByteArray, rom: ByteArray): ByteArray = when {
        patch.size >= 5 && String(patch, 0, 5, Charsets.US_ASCII) == "APS10" -> applyApsN64(patch, rom)
        patch.size >= 4 && String(patch, 0, 4, Charsets.US_ASCII) == "APS1" -> applyApsGba(patch, rom)
        else -> throw PatchException("This is not a valid APS patch.")
    }

    private fun applyApsN64(patch: ByteArray, rom: ByteArray): ByteArray {
        val c = Cur(patch, 5)
        val headerType = c.u8()
        c.pos += 1 // encoding method
        c.pos += 50 // description
        if (headerType == 1) c.pos += 1 + 3 + 8 + 5 // N64 header: format, cartId, crc, pad
        val outputSize = c.u32le()
        if (outputSize < 0) throw PatchException("This APS patch is not supported.")
        val out = rom.copyOf(outputSize)
        while (c.pos + 5 <= patch.size) {
            val offset = c.u32le()
            val length = c.u8()
            if (length == 0) {
                val value = c.u8().toByte()
                val runLength = c.u8()
                for (i in 0 until runLength) if (offset + i < out.size) out[offset + i] = value
            } else {
                for (i in 0 until length) if (c.pos < patch.size && offset + i < out.size)
                    out[offset + i] = patch[c.pos + i]
                c.pos += length
            }
        }
        return out
    }

    private fun applyApsGba(patch: ByteArray, rom: ByteArray): ByteArray {
        val block = 0x10000
        val recordSize = 4 + 2 + 2 + block
        if (patch.size < 12 + recordSize || (patch.size - 12) % recordSize != 0)
            throw PatchException("This APS patch is truncated.")
        val c = Cur(patch, 4)
        val sourceSize = c.u32le()
        val targetSize = c.u32le()
        if (rom.size != sourceSize)
            throw PatchException("This patch is for a different ROM than the one chosen.")
        val out = rom.copyOf(targetSize)
        while (c.pos < patch.size) {
            val offset = c.u32le()
            c.pos += 4 // source and target CRC16, not verified
            for (j in 0 until block) {
                val base = if (offset + j < rom.size) rom[offset + j].toInt() and 0xFF else 0
                if (offset + j < out.size) out[offset + j] = (base xor (patch[c.pos + j].toInt() and 0xFF)).toByte()
            }
            c.pos += block
        }
        return out
    }

    // ---- xdelta / VCDIFF -------------------------------------------------------------

    /**
     * VCDIFF (RFC 3284), the format xdelta writes. A patch is a series of windows, each a run
     * of instructions from a fixed code table that ADD literal bytes, RUN a repeated byte, or
     * COPY from the source or from earlier in the output. This handles the standard subset:
     * no secondary compressor and the default code table, which is what xdelta produces for
     * ROM patches. Where a window carries an Adler-32 of its output, it is checked, which
     * catches a wrong base ROM.
     */
    fun applyVcdiff(patch: ByteArray, rom: ByteArray): ByteArray {
        if (patch.size < 5 || u8(patch, 0) != 0xD6 || u8(patch, 1) != 0xC3 ||
            u8(patch, 2) != 0xC4)
            throw PatchException("This is not a valid xdelta (VCDIFF) patch.")

        val h = Cur(patch, 4)
        val headerIndicator = h.u8()
        if (headerIndicator and 0x01 != 0) { // VCD_DECOMPRESS
            if (h.u8() != 0) throw PatchException("This xdelta patch uses a secondary compressor, which is not supported.")
        }
        if (headerIndicator and 0x02 != 0) { // VCD_CODETABLE
            if (h.read7Bit() != 0) throw PatchException("This xdelta patch uses a custom code table, which is not supported.")
        }
        if (headerIndicator and 0x04 != 0) { // VCD_APPHEADER
            h.pos += h.read7Bit()
        }
        val headerEnd = h.pos

        // First pass: total target size.
        var targetSize = 0
        run {
            val p = Cur(patch, headerEnd)
            while (p.pos < patch.size) {
                val w = decodeWindow(p)
                targetSize += w.targetLength
                p.pos += w.addRunDataLength + w.instructionsLength + w.addressesLength
            }
        }

        val out = ByteArray(targetSize)
        val cache = AddressCache()
        var targetWindowPosition = 0
        val p = Cur(patch, headerEnd)
        while (p.pos < patch.size) {
            val w = decodeWindow(p)
            val addRun = Cur(patch, p.pos)
            val instr = Cur(patch, addRun.pos + w.addRunDataLength)
            val addresses = Cur(patch, instr.pos + w.instructionsLength)
            val addressesStart = addresses.pos
            cache.reset(addresses)

            var targetOffset = 0 // offset within this window
            while (instr.pos < addressesStart) {
                val instructionIndex = instr.u8()
                for (i in 0 until 2) {
                    val inst = CODE_TABLE[instructionIndex][i]
                    var size = inst.size
                    if (size == 0 && inst.type != VCD_NOOP) size = instr.read7Bit()
                    when (inst.type) {
                        VCD_NOOP -> {}
                        VCD_ADD -> {
                            System.arraycopy(patch, addRun.pos, out, targetWindowPosition + targetOffset, size)
                            addRun.pos += size
                            targetOffset += size
                        }
                        VCD_RUN -> {
                            val runByte = patch[addRun.pos]; addRun.pos += 1
                            val at = targetWindowPosition + targetOffset
                            for (j in 0 until size) out[at + j] = runByte
                            targetOffset += size
                        }
                        VCD_COPY -> {
                            val addr = cache.decodeAddress(targetOffset + w.sourceLength, inst.mode)
                            var absAddr: Int
                            val fromSource: Boolean
                            if (addr < w.sourceLength) {
                                absAddr = w.sourcePosition + addr
                                fromSource = w.indicator and 0x01 != 0 // VCD_SOURCE, else target
                            } else {
                                absAddr = targetWindowPosition + (addr - w.sourceLength)
                                fromSource = false
                            }
                            var n = size
                            while (n-- > 0) {
                                out[targetWindowPosition + targetOffset++] =
                                    if (fromSource) rom[absAddr++] else out[absAddr++]
                            }
                        }
                    }
                }
            }

            if (w.adler32 != -1L) {
                if (adler32(out, targetWindowPosition, w.targetLength) != w.adler32)
                    throw PatchException("This patch is for a different ROM than the one chosen.")
            }

            p.pos += w.addRunDataLength + w.instructionsLength + w.addressesLength
            targetWindowPosition += w.targetLength
        }
        return out
    }

    private class Window(
        val indicator: Int,
        val sourceLength: Int,
        val sourcePosition: Int,
        val targetLength: Int,
        val addRunDataLength: Int,
        val instructionsLength: Int,
        val addressesLength: Int,
        val adler32: Long,
    )

    private fun decodeWindow(c: Cur): Window {
        val indicator = c.u8()
        var sourceLength = 0
        var sourcePosition = 0
        if (indicator and 0x03 != 0) { // VCD_SOURCE | VCD_TARGET
            sourceLength = c.read7Bit()
            sourcePosition = c.read7Bit()
        }
        c.read7Bit() // delta length, not needed
        val targetLength = c.read7Bit()
        if (c.u8() != 0) throw PatchException("This xdelta patch uses window compression, which is not supported.")
        val addRunDataLength = c.read7Bit()
        val instructionsLength = c.read7Bit()
        val addressesLength = c.read7Bit()
        // The Adler-32 word is a big-endian 32-bit value, unlike the 7-bit ints above.
        val adler32 = if (indicator and 0x04 != 0) c.u32be() else -1L
        return Window(indicator, sourceLength, sourcePosition, targetLength, addRunDataLength, instructionsLength, addressesLength, adler32)
    }

    private const val VCD_NOOP = 0
    private const val VCD_ADD = 1
    private const val VCD_RUN = 2
    private const val VCD_COPY = 3

    private class Inst(val type: Int, val size: Int, val mode: Int)

    /** The default VCDIFF code table from RFC 3284, built the same way the spec lays it out. */
    private val CODE_TABLE: Array<Array<Inst>> = buildCodeTable()

    private fun buildCodeTable(): Array<Array<Inst>> {
        val empty = Inst(VCD_NOOP, 0, 0)
        val entries = ArrayList<Array<Inst>>(256)
        entries.add(arrayOf(Inst(VCD_RUN, 0, 0), empty))
        for (size in 0 until 18) entries.add(arrayOf(Inst(VCD_ADD, size, 0), empty))
        for (mode in 0 until 9) {
            entries.add(arrayOf(Inst(VCD_COPY, 0, mode), empty))
            for (size in 4 until 19) entries.add(arrayOf(Inst(VCD_COPY, size, mode), empty))
        }
        for (mode in 0 until 6)
            for (addSize in 1 until 5)
                for (copySize in 4 until 7)
                    entries.add(arrayOf(Inst(VCD_ADD, addSize, 0), Inst(VCD_COPY, copySize, mode)))
        for (mode in 6 until 9)
            for (addSize in 1 until 5)
                entries.add(arrayOf(Inst(VCD_ADD, addSize, 0), Inst(VCD_COPY, 4, mode)))
        for (mode in 0 until 9)
            entries.add(arrayOf(Inst(VCD_COPY, 4, mode), Inst(VCD_ADD, 1, 0)))
        return entries.toTypedArray()
    }

    /** The VCDIFF address cache (RFC 3284): near and same tables for compact COPY addresses. */
    private class AddressCache(val nearSize: Int = 4, val sameSize: Int = 3) {
        private val near = IntArray(nearSize)
        private val same = IntArray(sameSize * 256)
        private var nextNearSlot = 0
        private lateinit var addresses: Cur

        fun reset(addresses: Cur) {
            nextNearSlot = 0
            near.fill(0)
            same.fill(0)
            this.addresses = addresses
        }

        fun decodeAddress(here: Int, mode: Int): Int {
            val address: Int = when {
                mode == 0 -> addresses.read7Bit() // VCD_MODE_SELF
                mode == 1 -> here - addresses.read7Bit() // VCD_MODE_HERE
                mode - 2 < nearSize -> near[mode - 2] + addresses.read7Bit()
                else -> {
                    val m = mode - (2 + nearSize)
                    same[m * 256 + addresses.u8()]
                }
            }
            update(address)
            return address
        }

        private fun update(address: Int) {
            if (nearSize > 0) {
                near[nextNearSlot] = address
                nextNearSlot = (nextNearSlot + 1) % nearSize
            }
            if (sameSize > 0) same[address % (sameSize * 256)] = address
        }
    }

    private fun adler32(data: ByteArray, offset: Int, length: Int): Long {
        val mod = 65521L
        var a = 1L
        var b = 0L
        for (i in 0 until length) {
            a = (a + (data[offset + i].toInt() and 0xFF)) % mod
            b = (b + a) % mod
        }
        return (b shl 16) or a
    }

    // ---- shared helpers --------------------------------------------------------------

    /** A read cursor over a patch, for the offset-and-length formats and VCDIFF's ints. */
    private class Cur(val bytes: ByteArray, var pos: Int) {
        fun u8(): Int {
            if (pos >= bytes.size) throw PatchException("This patch is truncated.")
            return bytes[pos++].toInt() and 0xFF
        }

        fun u32le(): Int = (u8()) or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)

        fun u32be(): Long {
            val v = (u8().toLong() shl 24) or (u8().toLong() shl 16) or
                (u8().toLong() shl 8) or u8().toLong()
            return v
        }

        /** VCDIFF's big-endian 7-bit continuation integer. */
        fun read7Bit(): Int {
            var num = 0
            while (true) {
                val bits = u8()
                num = (num shl 7) + (bits and 0x7F)
                if (bits and 0x80 == 0) break
            }
            return num
        }
    }

    /** Reads the variable-length values UPS and BPS both use. */
    private class Reader(val bytes: ByteArray, var pos: Int) {
        fun u8(): Int {
            if (pos >= bytes.size) throw PatchException("This patch is truncated.")
            return bytes[pos++].toInt() and 0xFF
        }

        fun vlv(): Int {
            var data = 0
            var shift = 1
            while (true) {
                val x = u8()
                data += (x and 0x7F) * shift
                if (x and 0x80 != 0) break
                shift = shift shl 7
                data += shift
            }
            return data
        }

        /** BPS's relative offsets: low bit is the sign, the rest the magnitude. */
        fun signedVlv(): Int {
            val v = vlv()
            return if (v and 1 != 0) -(v shr 1) else (v shr 1)
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun le32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    private fun crc32(b: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(b, offset, length)
        return crc.value
    }
}
