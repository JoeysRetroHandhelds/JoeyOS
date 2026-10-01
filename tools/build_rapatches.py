#!/usr/bin/env python3
"""Build the ra-patches index: which romhacks apply to which ROM, per console.

JoeyOS's copy of chameleon-data's builder (Chameleon is no longer maintained). The weekly
"Romhack index" Action runs it and publishes the result to this repo's `data` branch, which the
app reads from raw.githubusercontent.com. Only public sources are used (RAPatches and the
libretro-database No-Intro dats) and only the Python standard library.


The app's romhack tool (and the club's Romhack of the Month) look up a base ROM's CRC and
offer the hacks made for it, from RetroAchievements' RAPatches repository. RAPatches stores
each hack as an archive under `System/Type/.../<gameid>-<name>.zip`, and the archive holds a
patch named after No-Intro: `Base Game - Hack Name (v2.0).bps`. So both facts we need are
here without the RetroAchievements API:

- the **base game**, from the RAPatches base-game folder (falling back to the patch filename
  for translations, which file by language), resolved to its CRC through the No-Intro dats, and
- the **format** (bps/ips/xdelta), from the patch's extension, which the app needs to parse it.

The patch filename lives in the archive's central directory at the very end of the file, so a
short range request reads it without pulling the whole (often tens of MB) archive. The result
is `<out>/<shortname>.json`: `{ base_crc: [ {name, type, path, format} ] }`, exactly what
`RaPatches.parse` reads. A hack whose base cannot be resolved is skipped, so a wrong guess is
never published. Run from the repo root:

    python tools/build_rapatches.py                        # every system, into build/ra-patches
    python tools/build_rapatches.py --system GBA --out x   # one, for a quick check
"""
import argparse
import json
import os
import re
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nointro  # noqa: E402

TREE = "https://api.github.com/repos/RetroAchievements/RAPatches/git/trees/main?recursive=1"
RAW = "https://github.com/RetroAchievements/RAPatches/raw/refs/heads/main/"
UA = {"User-Agent": "JoeyOS ra-patches builder"}

# The No-Intro/Redump dat (libretro-database system name) for each shortname: what a platform
# pack's `LIBRETRO:` source declared in chameleon-data, carried here so nothing else is needed.
LIBRETRO = {
    "3do": "The 3DO Company - 3DO", "atari2600": "Atari - 2600", "dreamcast": "Sega - Dreamcast",
    "fds": "Nintendo - Family Computer Disk System", "gamegear": "Sega - Game Gear",
    "gb": "Nintendo - Game Boy", "gba": "Nintendo - Game Boy Advance",
    "gbc": "Nintendo - Game Boy Color", "gc": "Nintendo - GameCube",
    "genesis": "Sega - Mega Drive - Genesis", "master": "Sega - Master System - Mark III",
    "msx": "Microsoft - MSX", "n64": "Nintendo - Nintendo 64", "nds": "Nintendo - Nintendo DS",
    "neogeocd": "SNK - Neo Geo CD", "nes": "Nintendo - Nintendo Entertainment System",
    "ngp": "SNK - Neo Geo Pocket", "pcfx": "NEC - PC-FX", "ps2": "Sony - PlayStation 2",
    "psp": "Sony - PlayStation Portable", "psx": "Sony - PlayStation", "saturn": "Sega - Saturn",
    "sega32x": "Sega - 32X", "segacd": "Sega - Mega-CD - Sega CD", "sg1000": "Sega - SG-1000",
    "snes": "Nintendo - Super Nintendo Entertainment System",
    "tg16": "NEC - PC Engine - TurboGrafx 16", "wii": "Nintendo - Wii",
}

# RAPatches' top-level console folder -> the pack shortname the app matches on. Folders with
# no console pack (DLC, Removed, Standalone, Saves, Incompatible) are simply absent, so their
# files are skipped. Regional names follow the packs (MD -> genesis, PC Engine -> tg16).
SYSTEMS = {
    "3DO": "3do", "Amstrad CPC": "amstradcpc", "Apple II": "appleii",
    "Arcadia 2001": "arcadia2001", "Atari 2600": "atari2600", "Atari Jaguar": "atarijaguar",
    "Dreamcast": "dreamcast", "Famicom Disk System": "fds", "GBA": "gba", "GBC": "gbc",
    "Game Boy": "gb", "Game Gear": "gamegear", "GameCube": "gc", "MD": "genesis", "MSX": "msx",
    "Master System": "master", "N64": "n64", "NDS": "nds", "NES": "nes",
    "Neo Geo CD": "neogeocd", "Neo Geo Pocket": "ngp", "Nintendo 64DD": "n64",
    "PC Engine CD": "tg16", "PC Engine": "tg16", "PC-FX": "pcfx", "PS2": "ps2",
    "PlayStation Portable": "psp", "PlayStation": "psx", "Pokemon Mini": "pokemonmini",
    "SG-1000": "sg1000", "SNES": "snes", "Saturn": "saturn", "Sega 32X": "sega32x",
    "Sega CD": "segacd", "Wii": "wii",
}

PATCH_IN_NAME = re.compile(rb"([\x20-\x7e]+?\.(?:bps|ips|xdelta|xdelta3|ups))", re.I)


def _get(url, headers=None):
    auth = {}
    # In Actions the API gets the job token: 1000 requests/hour instead of 60 anonymous.
    if url.startswith("https://api.github.com/") and os.environ.get("GITHUB_TOKEN"):
        auth = {"Authorization": "Bearer " + os.environ["GITHUB_TOKEN"]}
    req = urllib.request.Request(url, headers={**UA, **auth, **(headers or {})})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def tree():
    """Every patch archive in the repo, as (system, type, path, blob sha).

    The blob sha is git's fingerprint of the file's contents, so it changes exactly when the
    archive does: it's the key the formats cache (see build) remembers each archive by."""
    data = json.loads(_get(TREE))
    for node in data["tree"]:
        if node["type"] != "blob":
            continue
        path = node["path"]
        if not path.lower().endswith((".zip", ".7z")):
            continue
        parts = path.split("/")
        if len(parts) < 3:
            continue
        yield parts[0], parts[1], path, node["sha"]


def inner_patch(path):
    """(patch filename, format) read from the archive's tail; ("", "") when the archive was
    read but holds no patch we recognise (cached, so it isn't re-read every run); (None, None)
    when the read itself failed (not cached, so the next run tries again).

    The central directory sits at the end of a zip, so the last few KB name the files inside;
    a range request reads them without the whole archive. 7z is not laid out the same way, so
    those fall back to the archive's own extension and a bps assumption.
    """
    if path.lower().endswith(".7z"):
        return os.path.basename(path), "bps"
    url = RAW + urllib.parse.quote(path)
    try:
        tail = _get(url, {"Range": "bytes=-16384"})
    except urllib.error.HTTPError as e:
        # GitHub answers 416 when the archive is smaller than the range asked for, rather than
        # sending the whole file. Such an archive is under 16 KB, so just fetch all of it.
        # (Found porting this from chameleon-data: every small patch was silently dropped.)
        if e.code != 416:
            return None, None
        try:
            tail = _get(url)
        except Exception:
            return None, None
    except Exception:
        return None, None
    best = None
    for m in PATCH_IN_NAME.finditer(tail):
        name = m.group(1).decode("latin-1")
        # The newest revision when several ship together, so the CRC the site knows wins.
        if best is None or name > best:
            best = name
    if not best:
        return "", ""
    return best, best.rsplit(".", 1)[1].lower()


def hack_name(patch_name, base, fallback):
    """A readable name for one hack, for the app to show under its base game.

    The patch inside the archive is No-Intro named ("Base Game - Hack Name (v2.0).bps"), so
    its filename carries the real title the archive's own slug ("695-dkl3-dinkydixiehack")
    does not. We drop the extension and, when the name leads with the base game the app
    already shows as the row's heading, that redundant prefix too, leaving "Dinky & Dixie
    Hack (v2.0)". A 7z, whose inner name we cannot read cheaply, keeps the slug fallback.
    """
    stem = os.path.splitext(patch_name)[0].strip()
    if not stem:
        return fallback
    prefix = base + " - "
    if stem.lower().startswith(prefix.lower()) and len(stem) > len(prefix):
        stem = stem[len(prefix):].strip()
    return stem or fallback


def base_title(parts, type_folder, patch_name):
    """The base game a patch is for.

    For a hack, subset or fix the base-game folder is the reliable source - it is the whole
    title, so a name that itself contains ' - ' (Fire Emblem - The Sacred Stones) survives.
    Translations file by language instead, and a few patches sit directly under the type with
    no folder, so those fall back to the patch filename's leading part.
    """
    if type_folder != "Translation" and len(parts) >= 4:
        return parts[-2]
    name = patch_name.rsplit(".", 1)[0]
    return name.split(" - ", 1)[0].strip()


def base_crcs(resolver, title):
    """The CRCs for a base title, tolerating the informal names patches use.

    Tries an exact fold first; failing that, treats the title as the start of a catalogue name,
    so "Pokemon Emerald" reaches "Pokemon Emerald Version". Being a shade over-inclusive is safe:
    a BPS patch carries its source checksum and the app refuses to apply it to the wrong ROM, so
    a hack listed under a near neighbour simply never takes there.
    """
    key = nointro.fold(title)
    if not key:
        return set()
    exact = resolver.by_title.get(key)
    if exact:
        return exact
    out = set()
    for name, crcs in resolver.by_title.items():
        if name.startswith(key + " "):
            out |= crcs
    return out


def build(out_dir, only=None, workers=16, cache_path=None):
    """Writes the index to [out_dir].

    [cache_path] is the formats cache: blob sha -> (patch name, format) for every archive read
    before. Only new or changed archives are read, so a run after a small RAPatches change
    makes a handful of range requests instead of five thousand, which is what lets the Action
    run every 15 minutes. The cache is rewritten pruned to the archives that still exist.
    """
    resolvers = {}
    cache = {}
    if cache_path and os.path.isfile(cache_path):
        with open(cache_path, encoding="utf-8") as f:
            cache = {k: tuple(v) for k, v in json.load(f).items()}

    # The archives worth reading: those on a console we have a pack and a dat for.
    jobs = []
    shas = {}
    for system, type_folder, path, sha in tree():
        if only and system != only:
            continue
        short = SYSTEMS.get(system)
        if not short or short not in LIBRETRO:
            continue
        if short not in resolvers:
            resolvers[short] = nointro.resolver_for(LIBRETRO[short])
        if resolvers[short]:
            jobs.append((short, type_folder, path))
            shas[path] = sha

    # The format needs a peek inside each archive; those reads are all network, so run them
    # in parallel - a few thousand tiny range requests one at a time would take an age.
    formats = {path: cache[shas[path]] for _, _, path in jobs if shas[path] in cache}
    to_read = [j for j in jobs if j[2] not in formats]
    print(f"{len(jobs)} archives, {len(formats)} known from the cache, reading {len(to_read)}...")
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for path, (patch_name, fmt) in zip(
            [j[2] for j in to_read], pool.map(lambda j: inner_patch(j[2]), to_read)
        ):
            formats[path] = (patch_name, fmt)
            # A failed read (None) is left out of the cache, so the next run tries it again.
            if patch_name is not None:
                cache[shas[path]] = (patch_name, fmt)

    if cache_path:
        live = set(shas.values())
        os.makedirs(os.path.dirname(cache_path) or ".", exist_ok=True)
        with open(cache_path, "w", encoding="utf-8") as f:
            json.dump({k: list(v) for k, v in sorted(cache.items()) if k in live}, f, separators=(",", ":"))

    index = {}  # shortname -> {crc -> [hack]}
    for short, type_folder, path in jobs:
        patch_name, fmt = formats.get(path, (None, None))
        if not patch_name:
            continue
        title = base_title(path.split("/"), type_folder, patch_name)
        crcs = base_crcs(resolvers[short], title)
        if not crcs:
            print(f"  unresolved base: {short} {title!r}  ({path})")
            continue
        slug = os.path.splitext(os.path.basename(path))[0]
        hack = {
            "name": hack_name(patch_name, title, slug),
            "type": type_folder,
            "path": path,
            "format": fmt,
        }
        table = index.setdefault(short, {})
        for crc in sorted(crcs):
            table.setdefault(crc, []).append(hack)

    if not index:
        raise SystemExit("No hacks resolved at all: refusing to publish an empty index.")
    os.makedirs(out_dir, exist_ok=True)
    for short, table in index.items():
        with open(os.path.join(out_dir, f"{short}.json"), "w", encoding="utf-8") as f:
            json.dump(table, f, indent=2)
            f.write("\n")
        hacks = sum(len(v) for v in table.values())
        print(f"wrote {out_dir}/{short}.json: {len(table)} base ROMs, {hacks} hacks")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--system", help="only this RAPatches system folder, e.g. GBA")
    ap.add_argument("--out", default=os.path.join("build", "ra-patches"), help="output folder")
    ap.add_argument("--formats-cache", help="json cache of archives already read (read and rewritten)")
    args = ap.parse_args()
    build(args.out, args.system, cache_path=args.formats_cache)


if __name__ == "__main__":
    main()
