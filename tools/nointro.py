#!/usr/bin/env python3
"""Turn a game title into its ROM checksums, per console.

The library matches games by CRC32: the same checksum No-Intro lists and a zip stores for
the file inside. So anything that wants to name a game the app can find - a curated set, a
club pick - has to speak in CRCs, and the bridge from a plain title to a CRC is a No-Intro
dat, which is exactly a name-to-CRC table.

libretro-database carries those dats, the same source `arcade-names.py` reads its arcade
mapping from. Each platform pack names its libretro system in `scraperSourceList` as a
`LIBRETRO:...` entry, and that string is the dat's filename, so a pack resolves to a dat
with no table of our own to keep.

One game has several dumps - USA, Europe, Japan, revisions - each a different CRC. A title
therefore resolves to a *set* of CRCs, so a set or a pick lights up for whichever copy the
person owns rather than only the one region we happened to name.

This is a library, not a script: `resolver_for(system)` gives back something that maps a
title to its CRCs. Fetched dats are cached under `tools/.cache` so a run that touches forty
platforms does not re-download on every invocation.

Ported from chameleon-data (Chameleon's data repo); JoeyOS's build_rapatches.py uses it.
"""
import json
import os
import re
import urllib.parse
import urllib.request

BASE = "https://raw.githubusercontent.com/libretro/libretro-database/master/"
CACHE = os.path.join(os.path.dirname(__file__), ".cache", "nointro")

# ClrMamePro `game (... )` blocks, each with a `rom (... crc XXXXXXXX ...)` inside. The No-Intro
# metadat uses tabs and `size`/`md5` neighbours, so the crc is matched wherever it sits.
GAME = re.compile(r"game\s*\(\s*(.*?)\n\s*\)", re.S)
NAME = re.compile(r'name\s+"([^"]+)"')
CRC = re.compile(r"\bcrc\s+([0-9A-Fa-f]{8})\b")

# The dat lives under different roots for cartridge vs disc systems; try the fullest first.
ROOTS = ["metadat/no-intro/", "metadat/redump/", "dat/"]

# Parenthetical and bracketed tags - "(USA)", "(Rev 1)", "[!]" - are what separate one dump
# of a game from another, so stripping them folds every region and revision to one base title.
TAGS = re.compile(r"[\(\[].*?[\)\]]")


def libretro_system(platform: dict) -> str | None:
    """The `LIBRETRO:` system name a pack declares, which is also the dat filename."""
    for source in platform.get("scraperSourceList") or []:
        if isinstance(source, str) and source.startswith("LIBRETRO:"):
            return source[len("LIBRETRO:") :]
    return None


def _no_ext(name: str) -> str:
    """Drop a trailing file extension, so a hash's `Game (USA).gba` meets the dat's `Game (USA)`."""
    stem, dot, ext = name.rpartition(".")
    return stem if dot and 1 <= len(ext) <= 4 and ext.isalnum() else name


def fold(title: str) -> str:
    """A title reduced to a comparison key: no tags, no punctuation, lower case.

    "Chrono Trigger (USA)", "Chrono Trigger", "chrono trigger!" all fold to the same thing,
    which is what lets a hand-typed title meet a dat's formal name.
    """
    base = TAGS.sub("", title)
    base = base.rsplit(".", 1)[0] if "." in base[-5:] else base  # drop a file extension
    base = re.sub(r"[^a-z0-9]+", " ", base.lower())
    # "The Legend of Zelda" / "Legend of Zelda, The" both lose the article either way.
    base = re.sub(r"\bthe\b", " ", base)
    return " ".join(base.split()).strip()


def _fetch(system: str) -> str | None:
    for root in ROOTS:
        url = BASE + urllib.parse.quote(f"{root}{system}.dat")
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                return response.read().decode("utf-8", "replace")
        except Exception:
            continue
    return None


def _load(system: str) -> str | None:
    os.makedirs(CACHE, exist_ok=True)
    cached = os.path.join(CACHE, system + ".dat")
    if os.path.isfile(cached):
        with open(cached, encoding="utf-8") as f:
            return f.read()
    text = _fetch(system)
    if text is not None:
        with open(cached, "w", encoding="utf-8") as f:
            f.write(text)
    return text


class Resolver:
    """A folded-title -> set-of-CRCs map for one platform, plus the canonical name of each."""

    def __init__(self, text: str):
        self.by_title: dict[str, set[str]] = {}
        self.name_of: dict[str, str] = {}  # folded title -> a display name, USA preferred
        self.by_name: dict[str, str] = {}  # exact dat name (lower, no ext) -> crc
        for block in GAME.findall(text):
            name = NAME.search(block)
            crc = CRC.search(block)
            if not name or not crc:
                continue
            full = name.group(1)
            self.by_name[_no_ext(full).lower()] = crc.group(1).lower()
            key = fold(full)
            if not key:
                continue
            self.by_title.setdefault(key, set()).add(crc.group(1).lower())
            # Prefer a USA name as the label, else keep the first seen.
            if key not in self.name_of or "(USA)" in full:
                self.name_of[key] = TAGS.sub("", full).strip()

    def crcs(self, title: str) -> set[str]:
        """Every dump's CRC for [title], across regions and revisions. Empty when unknown.

        Folds tags away, so a hand-typed title meets every region and revision. This is what
        a by-title set wants; it is the wrong tool for a specific dump, since it cannot tell
        one revision from another.
        """
        return self.by_title.get(fold(title), set())

    def crc_exact(self, full_name: str) -> str | None:
        """The CRC of the one dump named exactly [full_name] (a No-Intro filename, extension
        optional), or None. Used where the exact dump matters - a RetroAchievements hash names
        a precise ROM, and folding it would wrongly sweep in every other region."""
        return self.by_name.get(_no_ext(full_name).lower())


def resolver_for(system: str) -> Resolver | None:
    """A [Resolver] for a libretro system name (the dat's filename), or None when unreachable."""
    text = _load(system)
    return Resolver(text) if text else None
