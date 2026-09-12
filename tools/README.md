# tools/

Build recipes and upkeep for the native programs the **Compress ROMs** tool bundles, and the
builder for the **RetroAchievements romhacks** index.

## Romhack index

`build_rapatches.py` (with `nointro.py`) maps each base ROM's CRC32 to the RetroAchievements
romhacks made for it, reading only public sources: RetroAchievements'
[RAPatches](https://github.com/RetroAchievements/RAPatches) repo and the No-Intro dats in
[libretro-database](https://github.com/libretro/libretro-database). The **Romhack index** GitHub
Action runs it every Monday and publishes `ra-patches/<console>.json` to this repo's `data` branch
(one commit, replaced each time; nothing is pushed when unchanged). The app downloads the index
from there and the patch files straight from RAPatches.

```
python tools/build_rapatches.py --system "Game Gear" --out build/ra-patches   # quick local check
```

Ported from chameleon-data, whose copy (used by the Romhack of the Month bot) still runs on its own.

## Bundled programs


| Program | Upstream | Binary | Recipe | Licence notice |
|---|---|---|---|---|
| chdman (CHD) | [MAME](https://github.com/mamedev/mame) | `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libchdman.so` | `build-chdman.sh` | `app/src/main/assets/licences/gpl-2.0-chdman.txt` |
| dolphin-tool (RVZ) | [Dolphin](https://github.com/dolphin-emu/dolphin) | `app/src/main/jniLibs/arm64-v8a/libdolphintool.so` | `build-dolphin-tool.sh` | `…/gpl-2.0-dolphintool.txt` |
| Azahar compressor (ZCCI) | [Azahar](https://github.com/azahar-emu/azahar) | `app/src/main/jniLibs/arm64-v8a/libazahar.so` | `build-azahar-compress.sh` | `…/gpl-2.0-azahar.txt` |

All three are GPL-2.0-or-later, built from official upstream source (not forks), and run by the
app as separate executables. The recipes need the Android NDK; see each script's header. The
binaries were originally built for Chameleon, which is no longer maintained; JoeyOS is their home.

## Staying up to date

`upstream-pins.json` records the exact upstream commit each binary was built from, and the source
paths that binary depends on. `check_upstream.py` compares each pin with its upstream:

```
python tools/check_upstream.py
```

It lists any newer release (for information) and every commit since the pin that touches the
tool's code. The **Bundled tools upstream check** GitHub Action runs it on the 1st of each month
and opens an `upstream-tools` issue when something needs a look, without repeating a finding it
has already reported.

When a change is reviewed:

- **Not worth a rebuild** (a comment or copyright edit, an unrelated platform fix): add its sha to
  that tool's `reviewed` list in `upstream-pins.json`. It stops being flagged.
- **Worth a rebuild:** run the recipe at the new commit, replace the binary, then update the
  commit in `upstream-pins.json` (and clear `reviewed`) and in the licence notice, whose source
  offer names the exact commit. Test a conversion on a device before releasing.
