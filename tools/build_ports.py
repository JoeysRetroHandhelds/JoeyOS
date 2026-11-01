#!/usr/bin/env python3
"""Build the Android ports list the app reads from this repo's `data` branch.

Based on the Quiver Launcher catalog (quiverlauncher.com), so players' apps only ever talk to
us and only this daily build talks to Quiver. Each entry keeps the project's own repository
and its newest APK: the files themselves stay with the projects. A failed run stops, leaving
the last good list published.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

API = "https://api.quiverlauncher.com/api/v1"
UA = {"User-Agent": "JoeyOS ports list builder (daily)"}
PAUSE = 0.5  # seconds between requests, to stay gentle on Quiver


def get(path):
    time.sleep(PAUSE)
    req = urllib.request.Request(API + path, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)


def pages(path):
    cursor = None
    while True:
        joiner = "&" if "?" in path else "?"
        page = get(path + (f"{joiner}cursor={urllib.parse.quote(cursor)}" if cursor else ""))
        yield from page["items"]
        cursor = page.get("nextCursor") or page.get("cursor")
        if not cursor:
            return


def apk_of(release):
    """The first arm64 APK in a release, else its first APK, as (name, url)."""
    apks = []
    for asset in release.get("assets") or []:
        if asset.get("os") == "android" and asset.get("format") == "apk" and asset.get("url"):
            sha = asset.get("checksum") or ""
            apks.append((asset.get("filename", ""), asset["url"], sha.removeprefix("sha256:") if sha.startswith("sha256:") else None))
    return next((a for a in apks if "arm64" in a[0].lower()), apks[0] if apks else (None, None, None))


def releases_url(source):
    repository, provider = source.get("repository"), source.get("provider")
    if not repository:
        return None
    if provider == "gitlab":
        return f"https://gitlab.com/{repository}/-/releases"
    return f"https://github.com/{repository}/releases/latest"


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join("build", "ports", "android.json")
    repos = {r["slug"]: r for r in pages("/release-status?limit=100")}
    # The diff: an app's releases are only asked for when its version moved since the last list.
    known = {}
    if os.path.isfile(out):
        with open(out, encoding="utf-8") as f:
            last = json.load(f)
        known = {p["slug"]: p for p in last.get("ports", [])}
    ports, asked = [], 0
    for app in pages("/apps?limit=100&os=android"):
        if "android" not in [str(s).lower() for s in app.get("supportedOS") or []]:
            continue
        slug, version = app["slug"], app.get("lastReleaseVersion")
        if slug in known and known[slug].get("version") == version and "sha256" in known[slug]:
            name, url, sha = known[slug].get("apkName"), known[slug].get("apkUrl"), known[slug]["sha256"]
        else:
            asked += 1
            releases = get(f"/apps/{urllib.parse.quote(slug)}/releases?limit=1")["items"]
            name, url, sha = apk_of(releases[0]) if releases else (None, None, None)
        source = repos.get(app["slug"], {})
        ports.append({
            "slug": app["slug"],
            "name": app["name"],
            "type": app.get("projectType"),
            "consoles": app.get("consoles") or [],
            "games": [g.get("title") for g in app.get("games") or [] if g.get("title")],
            "description": app.get("description") or "",
            "artwork": app.get("artwork"),
            "version": app.get("lastReleaseVersion"),
            "released": app.get("lastReleaseAt"),
            "repository": source.get("repository"),
            "provider": source.get("provider"),
            # No APK: the app opens the project's releases page instead of installing.
            "releasesUrl": releases_url(source),
            "apkName": name,
            "apkUrl": url,
            "sha256": sha,
            "verified": bool(app.get("verified")),
            "recommended": app.get("recommended", 0),
            "issues": app.get("reportIssues", 0),
            "broken": app.get("reportBroken", 0),
        })
    if not ports:
        raise SystemExit("No Android ports found: refusing to publish an empty list.")
    ports.sort(key=lambda p: p["name"].lower())
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"source": "quiverlauncher.com", "ports": ports}, f, indent=1)
    print(f"wrote {out}: {len(ports)} ports, {asked} apps asked about")


if __name__ == "__main__":
    main()
