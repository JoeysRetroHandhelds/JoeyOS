#!/usr/bin/env python3
"""Reports upstream changes to the native tools JoeyOS bundles.

chdman (MAME), dolphin-tool (Dolphin) and Azahar's 3DS compressor ship as prebuilt binaries in
app/src/main/jniLibs, each built from one exact upstream commit (tools/upstream-pins.json). This
checks each against its upstream: a release newer than the pinned build is shown for
information, and commits since the pin that touch the code the tool is built from ("paths") are
what flag a tool, since a release that changes none of that code needs nothing.
Rebuilding stays a deliberate step with the recipes in tools/.

Usage:  python tools/check_upstream.py [pins.json]
Needs a GitHub token in GITHUB_TOKEN or GH_TOKEN, or an authenticated `gh` CLI to borrow one from.
Prints a Markdown report. In GitHub Actions it also sets the step output flagged=true|false and
adds the report to the job summary. Any failure to check is an error (exit 1), never a quiet
"nothing to do".
"""
import hashlib
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone

API = "https://api.github.com"


def token() -> str:
    for var in ("GITHUB_TOKEN", "GH_TOKEN"):
        if os.environ.get(var):
            return os.environ[var]
    try:
        return subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        sys.exit("No GitHub token: set GITHUB_TOKEN/GH_TOKEN or log in with `gh auth login`.")


TOKEN = token()


def get(path: str, allow_404: bool = False):
    req = urllib.request.Request(API + path, headers={
        "Authorization": f"Bearer {TOKEN}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "JoeyOS-upstream-check",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        if allow_404 and e.code == 404:
            return None
        raise SystemExit(f"GitHub API {path} failed: HTTP {e.code} {e.reason}")


def main() -> int:
    pins_path = sys.argv[1] if len(sys.argv) > 1 else "tools/upstream-pins.json"
    with open(pins_path, encoding="utf-8") as f:
        pins = json.load(f)

    flagged = False
    seen: list[str] = []  # every flagged commit, for the fingerprint
    out = ["# Bundled tools: upstream check", "",
           f"Checked {datetime.now(timezone.utc):%Y-%m-%d} against the commits in `{pins_path}`."]

    for tool in pins:
        name, repo, pin = tool["name"], tool["repo"], tool["commit"]
        pin_date = get(f"/repos/{repo}/commits/{pin}")["commit"]["committer"]["date"]
        out += ["", f"## {name} ([{repo}](https://github.com/{repo})), pinned `{pin[:8]}` ({pin_date[:10]})", ""]
        tool_flagged = False

        if tool.get("releases"):
            latest = get(f"/repos/{repo}/releases/latest", allow_404=True)
            if latest:
                tag, published = latest["tag_name"], latest["published_at"]
                if published > pin_date:
                    # Informational: a release that changes none of this tool's code needs nothing.
                    out.append(f"- New release `{tag}` ({published[:10]}), newer than the pinned build. "
                               "Only matters if the commits below change this tool's code.")
                else:
                    out.append(f"- Latest release `{tag}` ({published[:10]}) is older than the pinned build.")

        # Commits since the pin that touch the code this tool is built from.
        commits = {}
        for path in tool["paths"]:
            query = urllib.parse.urlencode({"since": pin_date, "path": path, "per_page": 100})
            for c in get(f"/repos/{repo}/commits?{query}"):
                # Skip the pin itself and commits already looked at and judged not to need a
                # rebuild (listed by full or short sha under "reviewed" in the pins file).
                if c["sha"] == pin or any(c["sha"].startswith(r) for r in tool.get("reviewed", [])):
                    continue
                seen.append(c["sha"])
                commits[c["sha"]] = (c["commit"]["committer"]["date"][:10],
                                     c["commit"]["message"].split("\n")[0])
        if commits:
            out.append(f"- **{len(commits)} commit(s) since the pin touch this tool's code:**")
            for sha, (date, subject) in sorted(commits.items(), key=lambda kv: kv[1][0], reverse=True):
                out.append(f"  - [`{sha[:8]}`](https://github.com/{repo}/commit/{sha}) {date} {subject}")
            tool_flagged = True
        else:
            out.append("- No commits since the pin touch this tool's code.")

        if tool_flagged:
            flagged = True
            out += ["", f"To update: review the changes above; if worth it, rebuild with `{tool['recipe']}` at the "
                        f"new commit, replace `{tool['binary']}`, and update the commit in `{pins_path}` and in "
                        f"`{tool['licence']}` (its source offer names the exact commit)."]

    if not flagged:
        out += ["", "**Nothing to do:** every bundled tool is current for the code JoeyOS uses."]

    # A stable fingerprint of what was flagged, so the monthly job can tell a new finding from one
    # it has already reported (the date line changes every run; this doesn't).
    fingerprint = hashlib.sha1("\n".join(sorted(set(seen))).encode()).hexdigest()[:12] if flagged else "none"
    out += ["", f"<!-- upstream-fingerprint: {fingerprint} -->"]
    report = "\n".join(out) + "\n"
    sys.stdout.reconfigure(encoding="utf-8")
    print(report)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
            f.write(f"flagged={'true' if flagged else 'false'}\n")
            f.write(f"fingerprint={fingerprint}\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
