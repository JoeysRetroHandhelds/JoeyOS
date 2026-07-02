#!/usr/bin/env python3
"""
Fetches game title databases and writes them to app/src/main/assets/gamedb/.
Run automatically by Gradle before each build.

Exit codes:
  0 — all databases updated successfully
  1 — one or more databases need manual action (Gradle will surface a warning)

Sources:
  GC/Wii/WiiU/3DS — GameTDB (https://www.gametdb.com)
  Switch          — blawar/titledb (https://github.com/blawar/titledb)
  PS2             — Redump (https://redump.org) — may require manual download
"""

import io
import json
import os
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ETAG_CACHE_FILE  = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".gamedb_etags.json")
LAST_FETCH_FILE  = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".gamedb_last_fetch")
FETCH_INTERVAL_DAYS = 7

# Force UTF-8 on Windows console so game titles with non-ASCII chars don't crash
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_DIR    = os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "gamedb")

# Manual corrections for specific title IDs the upstream sources get wrong or omit
# entirely. Applied every time the corresponding CSV is regenerated, so they survive
# future automatic re-fetches even after the upstream source changes.
MANUAL_3DS_OVERRIDES = {
    # Missing from all 5 hax0kartik/3dsdb regions (US/GB/JP/KR/TW) — likely a regional
    # SKU variant (e.g. non-GB EUR language pack) not covered by any of those lists.
    "00040000000f8000": "Team Ogre Attacks!",
    "000400000019e600": "Shantae and the Pirate's Curse",
}
MANUAL_SWITCH_OVERRIDES = {
    # titledb's official eShop metadata names these as bundle SKUs rather than the
    # plain retail title.
    "0100a3d008c5c000": "Pokémon Scarlet",
    "01008f6008c5e000": "Pokémon Violet",
}

SOURCES = {
    "wii":          "https://www.gametdb.com/wiitdb.zip",
    "gcn":          "https://www.gametdb.com/gcntdb.zip",
    # Fallback if GameTDB blocks the request — libretro Redump DAT
    "gcn_fallback": (
        "https://raw.githubusercontent.com/libretro/libretro-database/master/"
        "metadat/redump/Nintendo%20-%20GameCube.dat"
    ),
    "wii_fallback": (
        "https://raw.githubusercontent.com/libretro/libretro-database/master/"
        "metadat/redump/Nintendo%20-%20Wii.dat"
    ),
    "wiiu":         "https://www.gametdb.com/wiiutdb.zip",
    # Full 16-char title ID → name (matches Cemu save directory naming)
    "wiiu_titleids": "https://phunlabs.github.io/json",
    "3ds":          "https://www.gametdb.com/3dstdb.zip",
    # Full 16-char title IDs — matches Azahar/Citra save directory naming (00040000xxxxxxxx)
    "3ds_titleids_us": "https://raw.githubusercontent.com/hax0kartik/3dsdb/master/jsons/list_US.json",
    "3ds_titleids_gb": "https://raw.githubusercontent.com/hax0kartik/3dsdb/master/jsons/list_GB.json",
    "3ds_titleids_jp": "https://raw.githubusercontent.com/hax0kartik/3dsdb/master/jsons/list_JP.json",
    "3ds_titleids_kr": "https://raw.githubusercontent.com/hax0kartik/3dsdb/master/jsons/list_KR.json",
    "3ds_titleids_tw": "https://raw.githubusercontent.com/hax0kartik/3dsdb/master/jsons/list_TW.json",
    "switch":       "https://raw.githubusercontent.com/blawar/titledb/master/US.en.json",
    "ps2":          "https://redump.org/datfile/ps2/",
    "ps2_libretro": (
        "https://raw.githubusercontent.com/libretro/libretro-database/master/"
        "metadat/redump/Sony%20-%20PlayStation%202.dat"
    ),
    "ps3":          "http://www.gametdb.com/ps3tdb.txt",
    # Vita3K compatibility API — 3000+ curated title IDs, continuously updated
    "vita_compat":  "https://vita3k-api.pedro.moe/list/commercial",
    "vita_cart":    (
        "https://raw.githubusercontent.com/libretro/libretro-database/master/"
        "metadat/no-intro/Sony%20-%20PlayStation%20Vita.dat"
    ),
    "vita_psn":     (
        "https://raw.githubusercontent.com/libretro/libretro-database/master/"
        "metadat/no-intro/Sony%20-%20PlayStation%20Vita%20(PSN).dat"
    ),
}

# If Redump requires login, download the PS2 DAT manually and place it here
REDUMP_PS2_LOCAL    = os.path.join(SCRIPT_DIR, "ps2.dat")
PS2_DAT_MAX_AGE_DAYS = 30   # warn if local DAT is older than this

# Tracks issues that need the user's attention — causes exit code 1
_action_needed: list[str] = []


def warn(msg: str) -> None:
    """Print a warning that Gradle will surface prominently."""
    print(f"GAMEDB WARNING: {msg}", file=sys.stderr)
    _action_needed.append(msg)


def _load_etag_cache() -> dict:
    try:
        with open(ETAG_CACHE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}

def _save_etag_cache(cache: dict) -> None:
    with open(ETAG_CACHE_FILE, "w", encoding="utf-8") as f:
        json.dump(cache, f, indent=2)

_etag_cache: dict = _load_etag_cache()

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept":          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.5",
    "Referer":         "https://www.gametdb.com/",
}

def fetch(url: str) -> bytes:
    """Download url, skipping if ETag/Last-Modified says nothing has changed.
    Returns the response bytes, or raises an exception on error.
    Raises a StaleError (ValueError subclass) if the remote is unchanged."""
    cached = _etag_cache.get(url, {})

    # HEAD check first
    try:
        head_req = urllib.request.Request(url, headers=HEADERS, method="HEAD")
        with urllib.request.urlopen(head_req, timeout=15) as r:
            etag     = r.headers.get("ETag", "")
            modified = r.headers.get("Last-Modified", "")
            validator = etag or modified
            if validator and validator == cached.get("validator"):
                print(f"  Skipping {url} (unchanged)")
                raise _UnchangedError()
    except _UnchangedError:
        raise
    except Exception as e:
        # HEAD failed or not supported — fall through to full GET
        if not isinstance(e, _UnchangedError):
            print(f"  HEAD check failed ({e}), proceeding with GET")

    print(f"  Fetching {url}")
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=60) as r:
        data     = r.read()
        etag     = r.headers.get("ETag", "")
        modified = r.headers.get("Last-Modified", "")
        validator = etag or modified
        if validator:
            _etag_cache[url] = {"validator": validator}
            _save_etag_cache(_etag_cache)
        return data

class _UnchangedError(Exception):
    pass


def existing_entry_count(csv_name: str) -> int:
    path = os.path.join(OUT_DIR, csv_name)
    if not os.path.isfile(path):
        return 0
    with open(path, encoding="utf-8") as f:
        return sum(1 for line in f if line.strip() and not line.startswith("#"))


def parse_gametdb_zip(data: bytes, lang: str = "EN") -> dict[str, str]:
    titles: dict[str, str] = {}
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        xml_name = next(n for n in zf.namelist() if n.endswith(".xml"))
        root = ET.fromstring(zf.read(xml_name))
    for game in root.findall("game"):
        gid = game.findtext("id", "").strip()
        if not gid:
            continue
        title = ""
        for locale in game.findall("locale"):
            if locale.get("lang") == lang:
                title = locale.findtext("title", "").strip()
                break
        if not title:
            loc = game.find("locale")
            if loc is not None:
                title = loc.findtext("title", "").strip()
        if gid and title:
            titles[gid] = title
    return titles


def parse_redump_dat(data: bytes) -> dict[str, str]:
    titles: dict[str, str] = {}
    root = ET.fromstring(data)
    for game in root.findall("game"):
        name   = game.get("name", "").strip()
        serial = game.findtext("serial", "").strip()
        if serial and name:
            for s in serial.split(","):
                s = s.strip()
                if s:
                    titles[s.upper()] = name
    return titles


def write_csv(csv_name: str, titles: dict[str, str], comment: str) -> None:
    old_count = existing_entry_count(csv_name)
    path = os.path.join(OUT_DIR, csv_name)
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(f"# {comment}\n")
        for gid, title in sorted(titles.items()):
            f.write(f"{gid},{title}\n")
    new_count = len(titles)
    added = new_count - old_count
    added_str = f" (+{added} new)" if added > 0 else (" (no change)" if added == 0 else f" ({added})")
    print(f"  Wrote {new_count} entries{added_str} → {csv_name}")


def fetch_gcwii() -> None:
    print("GameCube + Wii:")
    titles: dict[str, str] = {}
    sources_used: list[str] = []

    for platform, key, fallback_key in [
        ("GCN", "gcn", "gcn_fallback"),
        ("Wii", "wii", "wii_fallback"),
    ]:
        try:
            data = fetch(SOURCES[key])
            parsed = parse_gametdb_zip(data)
            print(f"  {platform} (GameTDB): {len(parsed)} entries")
            titles.update(parsed)
            sources_used.append(f"GameTDB/{platform}")
        except _UnchangedError:
            print(f"  {platform}: unchanged, skipping.")
            sources_used.append(f"GameTDB/{platform} (cached)")
        except Exception as e:
            print(f"  {platform} GameTDB failed ({e}), trying fallback...")
            try:
                data = fetch(SOURCES[fallback_key])
                parsed = parse_redump_gcwii_dat(data)
                print(f"  {platform} (Redump fallback): {len(parsed)} entries")
                titles.update(parsed)
                sources_used.append(f"niemasd/{platform}")
            except _UnchangedError:
                print(f"  {platform} fallback: unchanged, skipping.")
            except Exception as e2:
                print(f"  WARNING: {platform} fallback also failed: {e2}", file=sys.stderr)

    comment = "GameCube/Wii — " + ", ".join(sources_used) if sources_used else "GameCube/Wii"
    write_csv("gcwii.csv", titles, comment)


def parse_wiiu_titleids(data: bytes) -> dict[str, str]:
    """
    Parses the phunlabs.github.io/json WiiU title key database.
    Format: [{"titleID": "0005000010162800", "name": "Splatoon", ...}, ...]
    Only keeps retail title IDs (prefix 00050000).
    """
    entries = json.loads(data)
    titles: dict[str, str] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        tid  = str(entry.get("titleID", entry.get("titleId", ""))).lower().strip()
        name = " ".join(str(entry.get("name", "")).split()).strip()  # collapse newlines/whitespace
        if len(tid) == 16 and tid.startswith("00050000") and name:
            titles[tid] = name
    return titles


def fetch_wiiu() -> None:
    print("Wii U:")
    titles: dict[str, str] = {}
    primary_ok = False  # True if primary source succeeded or was unchanged (don't fall back)

    # Primary: full 16-char title IDs — required for Cemu save directory lookups
    try:
        data = fetch(SOURCES["wiiu_titleids"])
        parsed = parse_wiiu_titleids(data)
        print(f"  WiiU title IDs (phunlabs): {len(parsed)} entries")
        titles.update(parsed)
        primary_ok = True
    except _UnchangedError:
        # Source unchanged — existing CSV is still valid, don't overwrite with GameTDB
        print("  WiiU title IDs unchanged, skipping.")
        primary_ok = True
    except Exception as e:
        print(f"  WiiU title ID fetch failed ({e}), falling back to GameTDB short codes")

    # Only fall back to GameTDB if the primary source actually failed (not just unchanged).
    # GameTDB uses short codes (AMKP01) that don't match Cemu's full title IDs, so only
    # use it as a last resort when we have nothing else.
    if not primary_ok:
        try:
            gametdb = parse_gametdb_zip(fetch(SOURCES["wiiu"]))
            print(f"  WiiU GameTDB (short codes — won't match Cemu): {len(gametdb)} entries")
            titles.update(gametdb)
        except _UnchangedError:
            print("  WiiU GameTDB unchanged, skipping.")
        except Exception as e:
            print(f"  WARNING: WiiU GameTDB also failed: {e}", file=sys.stderr)

    if titles:
        write_csv("wiiu.csv", titles, "Wii U — full title IDs (phunlabs.github.io)")


def parse_3ds_titleids(data: bytes) -> dict[str, str]:
    """
    Parses hax0kartik/3dsdb regional JSON files.
    Format: [{"TitleID": "00040000001AC000", "Name": "Mario Kart 7", ...}, ...]
    Only keeps base game title IDs (prefix 00040000).
    """
    entries = json.loads(data)
    titles: dict[str, str] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        tid  = str(entry.get("TitleID", "")).lower().strip()
        raw  = str(entry.get("Name", ""))
        name = re.sub(r"<[^>]+>", " ", raw)  # strip HTML tags like <br>
        name = " ".join(name.split()).strip()
        if len(tid) == 16 and tid.startswith("00040000") and name:
            titles[tid] = name
    return titles


def fetch_3ds() -> None:
    print("3DS:")
    titles: dict[str, str] = {}
    primary_ok = False

    for region_key in ("3ds_titleids_us", "3ds_titleids_gb", "3ds_titleids_jp",
                       "3ds_titleids_kr", "3ds_titleids_tw"):
        try:
            parsed = parse_3ds_titleids(fetch(SOURCES[region_key]))
            print(f"  3DS title IDs ({region_key}): {len(parsed)} entries")
            titles.update(parsed)
            primary_ok = True
        except _UnchangedError:
            print(f"  {region_key}: unchanged, skipping.")
            primary_ok = True
        except Exception as e:
            print(f"  {region_key} failed ({e})")

    if not primary_ok:
        try:
            gametdb = {gid.lower(): title for gid, title in parse_gametdb_zip(fetch(SOURCES["3ds"])).items()}
            print(f"  3DS GameTDB (short codes — won't match Azahar): {len(gametdb)} entries")
            titles.update(gametdb)
        except _UnchangedError:
            print("  3DS GameTDB unchanged, skipping.")
        except Exception as e:
            print(f"  WARNING: 3DS GameTDB also failed: {e}", file=sys.stderr)

    if titles:
        titles.update(MANUAL_3DS_OVERRIDES)
        write_csv("3ds.csv", titles, "3DS — full title IDs (hax0kartik/3dsdb)")


def fetch_switch() -> None:
    print("Switch:")
    try:
        db = json.loads(fetch(SOURCES["switch"]))
        titles: dict[str, str] = {}
        for e in db.values():
            if not isinstance(e, dict):
                continue
            name   = " ".join(str(e.get("name", "")).split()).strip()
            real_id = str(e.get("id", "")).strip()
            # Use the "id" field — the full 16-char Nintendo title ID used by Eden/Yuzu
            # save directories. The JSON key is nsuId (14-char eShop ID), not useful here.
            if name and len(real_id) == 16:
                titles[real_id.lower()] = name
        titles.update(MANUAL_SWITCH_OVERRIDES)
        write_csv("switch.csv", titles, "Switch — blawar/titledb (full title IDs)")
    except _UnchangedError:
        print("  Switch database unchanged, skipping.")


def fetch_ps2() -> None:
    print("PS2:")
    dat_data: bytes | None = None

    if os.path.isfile(REDUMP_PS2_LOCAL):
        age_days = (time.time() - os.path.getmtime(REDUMP_PS2_LOCAL)) / 86400
        print(f"  Using local DAT (age: {age_days:.0f} days)")
        if age_days > PS2_DAT_MAX_AGE_DAYS:
            warn(
                f"scripts/ps2.dat is {age_days:.0f} days old. "
                f"Download a fresh copy from redump.org/datfile/ps2/ and replace scripts/ps2.dat."
            )
        with open(REDUMP_PS2_LOCAL, "rb") as f:
            dat_data = f.read()
    else:
        try:
            dat_data = fetch(SOURCES["ps2"])
        except _UnchangedError:
            print("  PS2 database unchanged, skipping.")
            return
        except Exception as e:
            print(f"  Redump unreachable ({e}), trying libretro-database fallback...")
            try:
                raw = fetch(SOURCES["ps2_libretro"])
                titles = parse_ps2_clrmame_dat(raw.decode("utf-8", errors="replace"))
                print(f"  libretro-database PS2: {len(titles)} entries")
                write_csv("ps2.csv", titles, "PS2 — libretro-database (ClrMamePro)")
                return
            except _UnchangedError:
                print("  libretro PS2 DAT unchanged, skipping.")
                return
            except Exception as e2:
                if existing_entry_count("ps2.csv") > 0:
                    print(f"  libretro also failed ({e2}), using existing ps2.csv.")
                else:
                    warn(
                        f"Could not fetch PS2 database ({e2}). "
                        f"For full coverage: download the DAT from redump.org/datfile/ps2/ "
                        f"and save it to scripts/ps2.dat."
                    )
                return

    write_csv("ps2.csv", parse_redump_dat(dat_data), "PS2 — Redump")


def parse_redump_gcwii_dat(data: bytes) -> dict[str, str]:
    """
    Redump ClrMamePro DAT for GCN/Wii.
    Serial format:  DL-DOL-GALE-USA  (GCN)  /  RVL-RMCE-USA  (Wii)
    We extract the 4-char game code and append '01' → GALE01 / RMCE01.
    This matches what Dolphin shows as the game ID.
    Beta/demo entries without a serial are skipped.
    """
    SERIAL_RE = re.compile(r'(?:DL-DOL|RVL)-([A-Z0-9]{4})-[A-Z]+')
    text      = data.decode("utf-8", errors="replace")
    blocks    = re.split(r'(?m)^\s*game\s*\(', text)
    name_re   = re.compile(r'\bname\s+"([^"]+)"')
    titles: dict[str, str] = {}
    for block in blocks[1:]:
        name_m   = name_re.search(block)
        serial_m = SERIAL_RE.search(block)
        if name_m and serial_m:
            # Strip region/demo suffixes for a cleaner title
            name = re.sub(r'\s*\([^)]*\)\s*$', '', name_m.group(1)).strip()
            name = name_m.group(1).strip()  # keep full name for accuracy
            tid  = serial_m.group(1) + "01"
            if tid not in titles:
                titles[tid] = name
    return titles


def parse_ps3_gametdb(text: str) -> dict[str, str]:
    """
    GameTDB ps3tdb.txt format (one entry per line):
        BCUS98174 = Uncharted: Drake's Fortune
    """
    pattern = re.compile(r"^([A-Z]{4}\d{5})\s*=\s*(.+)$")
    titles: dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = pattern.match(line)
        if m:
            tid, name = m.group(1), m.group(2).strip()
            if tid and name:
                titles[tid] = name
    return titles


def parse_ps2_clrmame_dat(text: str) -> dict[str, str]:
    """ClrMamePro DAT from libretro-database for PS2. Serial format: SLPM-65002"""
    name_re   = re.compile(r'\bname\s+"([^"]+)"')
    serial_re = re.compile(r'\bserial\s+"([A-Z]{2,4}-\d{5})"')
    blocks    = re.split(r'(?m)^\s*game\s*\(', text)
    titles: dict[str, str] = {}
    for block in blocks[1:]:
        name_m   = name_re.search(block)
        serial_m = serial_re.search(block)
        if name_m and serial_m:
            name   = name_m.group(1).strip()
            serial = serial_m.group(1).upper()
            if serial not in titles:
                titles[serial] = name
    return titles


def parse_vita_dat(text: str) -> dict[str, str]:
    """
    ClrMamePro DAT format used by libretro-database.
    Extracts game name and serial from each game block.
    Serials look like PCSE-00001; we strip the dash → PCSE00001.

    Handles both single-line "game (" and split-line formats.
    Uses a regex to find all game blocks, then parses fields within each.
    """
    name_re   = re.compile(r'\bname\s+"([^"]+)"')
    serial_re = re.compile(r'\bserial\s+"([A-Z]{4}-\d{5})"')
    # Split on "game (" boundaries (handles leading whitespace too)
    blocks    = re.split(r'(?m)^\s*game\s*\(', text)
    titles: dict[str, str] = {}
    for block in blocks[1:]:  # skip header before first game block
        name_m   = name_re.search(block)
        serial_m = serial_re.search(block)
        if name_m and serial_m:
            name = name_m.group(1).strip()
            tid  = serial_m.group(1).replace("-", "")
            if tid not in titles or len(name) < len(titles[tid]):
                titles[tid] = name
    return titles


def fetch_ps3() -> None:
    print("PS3:")
    try:
        raw  = fetch(SOURCES["ps3"])
    except _UnchangedError:
        print("  PS3 database unchanged, skipping.")
        return
    text = ""
    for enc in ("utf-8-sig", "utf-8", "latin-1"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    titles = parse_ps3_gametdb(text)
    write_csv("ps3.csv", titles, "PS3 — GameTDB")


VITA_ID_RE = re.compile(r'^PC[A-Z]{2}\d{5}$')


def parse_vita3k_compat(data: bytes) -> dict[str, str]:
    """
    Vita3K compatibility API: {"date": ..., "list": [{"name": "...", "titleId": "PCSE00293", ...}]}
    """
    raw = json.loads(data)
    entries = raw.get("list", raw) if isinstance(raw, dict) else raw
    titles: dict[str, str] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        tid  = str(entry.get("titleId") or entry.get("id") or "").strip()
        name = " ".join(str(entry.get("name") or "").split()).strip()
        if VITA_ID_RE.match(tid) and name:
            titles[tid] = name
    return titles


def _load_existing_csv(csv_name: str) -> dict[str, str]:
    """Load existing CSV into a dict so unchanged remote sources don't lose their entries."""
    path = os.path.join(OUT_DIR, csv_name)
    titles: dict[str, str] = {}
    if not os.path.isfile(path):
        return titles
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                parts = line.split(",", 1)
                if len(parts) == 2:
                    titles[parts[0]] = parts[1]
    return titles


def fetch_vita() -> None:
    print("PS Vita:")
    # Seed from the existing CSV so unchanged sources don't lose their entries.
    titles: dict[str, str] = _load_existing_csv("vita.csv")
    changed = False

    # 1. Vita3K compatibility API — 3000+ curated entries with exact title IDs
    try:
        data   = fetch(SOURCES["vita_compat"])
        parsed = parse_vita3k_compat(data)
        print(f"  Vita3K compat API: {len(parsed)} entries")
        titles.update(parsed)
        changed = True
    except _UnchangedError:
        print("  Vita3K compat API: unchanged, skipping.")
    except Exception as e:
        print(f"  Vita3K compat API failed ({e})")

    # 2. libretro No-Intro DATs — additional physical / PSN retail entries
    for key, label in [("vita_cart", "No-Intro retail"), ("vita_psn", "No-Intro PSN")]:
        try:
            raw    = fetch(SOURCES[key])
            text   = raw.decode("utf-8", errors="replace")
            parsed = parse_vita_dat(text)
            print(f"  {label}: {len(parsed)} entries")
            titles.update(parsed)
            changed = True
        except _UnchangedError:
            print(f"  {label}: unchanged, skipping.")
        except Exception as e:
            print(f"  {label} failed ({e})")

    if changed:
        write_csv("vita.csv", titles, "PS Vita — Vita3K compat API / libretro No-Intro")
    else:
        print("  PS Vita database unchanged, skipping.")


def _is_fetch_due() -> bool:
    """Returns True if it's been more than FETCH_INTERVAL_DAYS since the last fetch."""
    if "--force" in sys.argv:
        return True
    try:
        last = float(open(LAST_FETCH_FILE).read().strip())
        age_days = (time.time() - last) / 86400
        if age_days < FETCH_INTERVAL_DAYS:
            print(f"Game databases are up to date (last fetch {age_days:.1f} days ago). Skipping.")
            return False
    except (FileNotFoundError, ValueError):
        pass
    return True

def _record_fetch() -> None:
    with open(LAST_FETCH_FILE, "w") as f:
        f.write(str(time.time()))


def main() -> None:
    if not _is_fetch_due():
        sys.exit(0)
    print("Fetching game databases...")
    fetch_errors: list[str] = []

    for fn in [fetch_gcwii, fetch_wiiu, fetch_3ds, fetch_switch, fetch_ps2, fetch_ps3, fetch_vita]:
        try:
            fn()
        except Exception as e:
            name = fn.__name__.replace("fetch_", "")
            print(f"  WARNING: failed to fetch {name}: {e}", file=sys.stderr)
            fetch_errors.append(name)

    print()
    if fetch_errors:
        print(f"Skipped (network error): {', '.join(fetch_errors)} — bundled CSVs unchanged.")
    if fetch_errors:
        print(f"Skipped (network error): {', '.join(fetch_errors)} — bundled CSVs unchanged.")
    else:
        # Only record timestamp if all fetches completed — failures retry next build
        _record_fetch()
    if _action_needed:
        print(f"{len(_action_needed)} item(s) need your attention — see GAMEDB WARNING lines above.")
        sys.exit(1)
    else:
        print("All databases updated.")
        sys.exit(0)


if __name__ == "__main__":
    main()
