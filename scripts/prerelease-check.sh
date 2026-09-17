#!/usr/bin/env bash
# Pre-release checks for JoeyOS. Run after assembleRelease, before tagging:
#   scripts/prerelease-check.sh app/build/outputs/apk/release/app-release.apk
#
# Catches the classes of bug that have shipped and crashed on the Thor:
#  1. An Android API used in code whose manifest permission is missing (1.0.32: the status bar
#     read the network without ACCESS_NETWORK_STATE and crashed the home screen on launch).
#  2. Java-21 collection calls that crash on Android 13/14 (1.0.14: SortedSet.reversed()).
#  3. A build signed with the wrong key, or missing bundled data.
# It can't launch the app (no device here), so it's static + APK inspection, not a runtime test.

set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/app/src/main/java"
manifest="$root/app/src/main/AndroidManifest.xml"
apk="${1:-$root/app/build/outputs/apk/release/app-release.apk}"
fails=0
note() { echo "  - $1"; }
fail() { echo "FAIL: $1"; fails=$((fails+1)); }

# ── 1. Android APIs vs the permissions they need ──────────────────────────────
# Each row: PERMISSION<TAB>regex of API usage that requires it. Add rows as the app grows.
# Matches a pattern in real code, ignoring // line comments and /* */ or KDoc lines (so a
# doc-comment mentioning an API name isn't mistaken for using it).
code_hits() {
  local pattern="$1"
  grep -rl --include=*.kt -E "$pattern" "$src" 2>/dev/null | while read -r f; do
    if sed -e 's://.*$::' "$f" | grep -vE '^[[:space:]]*[*]' | grep -Eq "$pattern"; then
      echo "$f"
    fi
  done
}
check_perm() {
  local perm="$1" pattern="$2"
  local hits; hits=$(code_hits "$pattern")
  if [ -n "$hits" ] && ! grep -q "android.permission.$perm" "$manifest"; then
    fail "code uses [$pattern] which needs $perm, but the manifest doesn't declare it"
    echo "$hits" | sed "s|$root/||" | while read -r f; do note "$f"; done
  fi
}
echo "Permissions:"
check_perm ACCESS_NETWORK_STATE 'ConnectivityManager|getNetworkCapabilities|activeNetwork|registerDefaultNetworkCallback'
check_perm ACCESS_WIFI_STATE    'WifiManager'
check_perm BLUETOOTH_CONNECT    'BluetoothAdapter\.|bluetoothAdapter\.'
check_perm VIBRATE              'VibrationEffect|getSystemService\(Vibrator'
check_perm CAMERA               'CameraManager|android\.hardware\.camera2'
check_perm RECORD_AUDIO         'AudioRecord\(|MediaRecorder\('
[ "$fails" -eq 0 ] && echo "  ok"

# ── 2. Java-21 collection trap (crashes on Android 13/14) ─────────────────────
echo "Android 13/14 collection trap:"
classes="$root/app/build/intermediates/built_in_kotlinc/release"
javap="/c/Program Files/Android/Android Studio/jbr/bin/javap"
if [ -d "$classes" ] && [ -x "$javap" ]; then
  n=$(find "$classes" -name "*.class" | tr '\n' '\0' | xargs -0 "$javap" -c -p 2>/dev/null \
        | grep -cE "java/util/[A-Za-z]+\.(reversed|removeFirst|removeLast|getFirst|getLast|addFirst|addLast):")
  if [ "$n" -ne 0 ]; then fail "$n Java-21 collection call(s) in compiled classes (use sortedDescending(), first(), etc.)"
  else echo "  ok (0)"; fi
else
  note "skipped (compile the release variant first)"
fi

# ── 3. The APK itself ─────────────────────────────────────────────────────────
echo "APK:"
if [ -f "$apk" ]; then
  sdk=$(grep -m1 sdk.dir "$root/local.properties" 2>/dev/null | cut -d= -f2 | tr -d '\r' | sed 's/\\\\/\//g; s/\\:/:/')
  bt=$(ls -d "$sdk"/build-tools/* 2>/dev/null | tail -1)
  digest=$("$bt/apksigner.bat" verify --print-certs "$apk" 2>/dev/null | grep -oiE "[0-9a-f]{64}" | head -1)
  expected="60f5b57525814bd1a9df2195732bb15a01bafe054fe32cab637ba186565b254d"
  [ "$digest" = "$expected" ] || fail "signing cert digest is '$digest', expected the JoeyOS key"
  libs=$(unzip -l "$apk" | grep -cE "lib/arm64-v8a/lib(azahar|chdman|dolphintool)")
  [ "$libs" -eq 3 ] || fail "expected 3 bundled arm64 tools, found $libs"
  lines=$(unzip -p "$apk" assets/gamedb/3ds.csv 2>/dev/null | wc -l)
  [ "$lines" -ge 3900 ] || fail "3ds.csv has $lines lines, expected ~3924 (a bad gamedb fetch?)"
  [ "$fails" -eq 0 ] && echo "  ok (signed, $libs tools, 3ds.csv $lines lines)"
else
  note "no APK at $apk (build it first)"
fi

echo
if [ "$fails" -eq 0 ]; then echo "All pre-release checks passed."; else echo "$fails check(s) FAILED — do not release."; exit 1; fi
