package com.joeyos.app.data

/**
 * One BIOS file a system needs, and the hashes that prove it is the right one.
 *
 * [md5s] may be empty. A file whose hash we do not carry is checked by presence alone:
 * having it is enough to pass, and we simply cannot say it is verified. A wrong or
 * partial hash table must never turn a real BIOS red, so presence is the check and a
 * matching hash only ever an upgrade to "verified".
 */
data class BiosFile(
    val name: String,
    val md5s: Set<String> = emptySet(),
)

/**
 * What one system needs before its games will launch.
 *
 * [required] files must each be present. [anyOf] groups are regional variants where one
 * is enough (the USA, European and Japanese Sega CD BIOS, say). [optional] files improve
 * accuracy but are not needed, so their absence is reported without alarm.
 */
data class BiosSystem(
    val system: String,
    /** The JoeyOS system id (SystemData), so results can be limited to installed emulators. */
    val shortname: String,
    val required: List<BiosFile> = emptyList(),
    val anyOf: List<List<BiosFile>> = emptyList(),
    val optional: List<BiosFile> = emptyList(),
)

/**
 * The BIOS files each system needs. Ported from Chameleon (same table), keyed by JoeyOS
 * system ids instead of Chameleon's platform shortnames.
 *
 * Filenames follow joeysretrohandhelds.com's Recommended BIOS Files guide, which is the
 * list the community actually downloads against, so what this checks for is what people
 * have. MD5 hashes are listed where they are known for that exact filename; the rest are
 * checked by presence. Systems JoeyOS has no id for (Xbox, DSi) are left out.
 *
 * No BIOS is bundled or linked. This only ever reads files the user already has.
 */
object BiosRequirements {

    val systems: List<BiosSystem> = listOf(
        BiosSystem(
            "Sony PlayStation", "ps1",
            required = listOf(BiosFile("PSXONPSP660.bin")),
        ),
        BiosSystem(
            "Sony PlayStation 2", "ps2",
            anyOf = listOf(listOf(
                BiosFile("ps2-0230a-20080220.bin"),
                BiosFile("ps2-0230e-20080220.bin"),
                BiosFile("ps2-0230j-20080220.bin"),
            )),
        ),
        BiosSystem(
            "Sony PlayStation 3", "ps3",
            required = listOf(BiosFile("PS3UPDAT.PUP")),
        ),
        BiosSystem(
            "Sega Saturn", "saturn",
            anyOf = listOf(listOf(
                BiosFile("mpr-17933.bin", setOf("3240872c70984b6cbfda1586cab68dbe")),
                BiosFile("sega_101.bin", setOf("85ec9ca47d8f6807718151cbcca8b964")),
            )),
        ),
        BiosSystem(
            "Sega Dreamcast", "dc",
            required = listOf(BiosFile("dc_boot.bin", setOf("e10c53c2f8b90bab96ead2d368858623"))),
        ),
        BiosSystem(
            "Sega CD / 32X", "segacd",
            anyOf = listOf(listOf(
                BiosFile("bios_CD_U.bin", setOf("2efd74e3232ff260e371b99f84024f7f")),
                BiosFile("bios_CD_E.bin", setOf("e66fa1dc5820d254611fdcdba0662372")),
                BiosFile("bios_CD_J.bin", setOf("278a9397d192149e84e820ac621a8edd")),
            )),
        ),
        // One JoeyOS system covers both, but they are separate BIOS for separate games.
        BiosSystem(
            "Sega Naomi", "naomi",
            required = listOf(BiosFile("naomi.zip")),
        ),
        BiosSystem(
            "Atomiswave", "naomi",
            required = listOf(BiosFile("awbios.zip")),
        ),
        BiosSystem(
            "Neo Geo CD", "neogeo",
            required = listOf(BiosFile("neocd_f.rom")),
        ),
        BiosSystem(
            "PC Engine CD / TurboGrafx-CD", "pcenginecd",
            required = listOf(BiosFile("syscard3.pce", setOf("38179df8f4ac870017db21ebcbf53114"))),
        ),
        BiosSystem(
            "Famicom Disk System", "nes",
            optional = listOf(BiosFile("disksys.rom", setOf("ca30b50f880eb660a320674ed365ef7a"))),
        ),
        BiosSystem(
            "Atari Lynx", "lynx",
            required = listOf(BiosFile("lynxboot.img", setOf("fcd403db69f54290b51035d82f835e7b"))),
        ),
        BiosSystem(
            "Nintendo Game Boy / Color", "gb",
            optional = listOf(
                BiosFile("gb_bios.bin", setOf("32fbbd84168d3482956eb3c5051637f5")),
                BiosFile("gbc_bios.bin", setOf("dbfce9db9deaa2567f6a84fde55f9680")),
            ),
        ),
        BiosSystem(
            "Nintendo Game Boy Advance", "gba",
            optional = listOf(BiosFile("gba_bios.bin", setOf("a860e8c0b6d573d191e4ec7db1b1e4f6"))),
        ),
        BiosSystem(
            "Nintendo GameCube", "gc",
            optional = listOf(BiosFile("IPL.bin")),
        ),
        BiosSystem(
            "Nintendo DS", "nds",
            optional = listOf(
                BiosFile("bios7.bin"), BiosFile("bios9.bin"), BiosFile("firmware.bin"),
            ),
        ),
    )
}
