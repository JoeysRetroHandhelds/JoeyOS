#!/usr/bin/env python3
"""Patch DuckStation Android APK to prefer /storage/emulated/0/DuckStation for user data.

Based on the NetherSX2 root-storage patch concept (Trixarian/NetherSX2-patch PR #332),
adapted for com.github.stenzek.duckstation.

Changes made:
  1. AndroidManifest.xml     - adds MANAGE_EXTERNAL_STORAGE permission
  2. NativeLibrary.smali     - writes mApplicationContext first, then checks SDK/permission:
                               API <30 or isExternalStorageManager() -> ROOT_DIR,
                               otherwise falls back to getExternalFilesDir/getFilesDir
  3. MainActivity.smali      - calls maybePromptAllFilesAccess() as the very first
                               instruction in onCreate; if the prompt fires, calls
                               super.onCreate() and returns immediately so initializeOnce
                               never runs on that pass; onStart guard calls recreate()
                               once the permission is granted
  4. SetupWizardActivity.smali - same prompt injection as MainActivity (no onStart guard)

Unlike the NetherSX2 patch, no native .so binary patching is required because
libduckstation.so does not validate the data directory path against the package name.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

PKG = "com.github.stenzek.duckstation"
PKG_PATH = PKG.replace(".", "/")
ROOT_DIR = "/storage/emulated/0/DuckStation"


# ---------------------------------------------------------------------------
# Manifest
# ---------------------------------------------------------------------------

def patch_manifest(manifest: Path) -> None:
    text = manifest.read_text(encoding="utf-8")
    permission = '<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />'
    if "android.permission.MANAGE_EXTERNAL_STORAGE" not in text:
        text = text.replace("<application", f"    {permission}\n    <application", 1)
        manifest.write_text(text, encoding="utf-8")
        print("Manifest: added MANAGE_EXTERNAL_STORAGE permission")
    else:
        print("Manifest: MANAGE_EXTERNAL_STORAGE already present")


# ---------------------------------------------------------------------------
# NativeLibrary smali
# ---------------------------------------------------------------------------

def patch_native_library(smali_root: Path) -> None:
    """Redirect mDataDirectory to ROOT_DIR; write mApplicationContext first, then decide path."""
    path = smali_root / f"{PKG_PATH}/NativeLibrary.smali"
    if not path.exists():
        raise SystemExit(f"NativeLibrary.smali not found at {path}")

    text = path.read_text(encoding="utf-8")
    if ROOT_DIR in text:
        print("NativeLibrary: data dir already patched")
        return

    method_match = re.search(
        r"(?s)(\.method public static initializeOnce\(Landroid/content/Context;Z\)V.*?\.end method)",
        text,
    )
    if not method_match:
        raise SystemExit("Could not find NativeLibrary.initializeOnce(Context, Z)")
    body = method_match.group(1)

    # Bump .locals 6 -> 7 (we use v6 for null arg + File object in the root-dir branch)
    body = re.sub(r"(?m)^    \.locals 6$", "    .locals 7", body, count=1)

    # Match the storage-path block: getExternalFilesDir (fallback to getFilesDir),
    # then set mApplicationContext and mDataDirectory.
    # Handles any number of .line annotations between instructions (apktool style).
    LS = r"(?:(?:\s*\.line \d+)*\s+)"
    pattern = re.compile(
        r"(?s)"
        r"    const/4 v2, 0x0" + LS +
        r"    invoke-virtual \{p0, v2\}, Landroid/content/Context;->getExternalFilesDir\(Ljava/lang/String;\)Ljava/io/File;" + LS +
        r"    move-result-object v3" + LS +
        r"    if-nez v3, (:cond_[0-9a-f]+)" + LS +
        r"    invoke-virtual \{p0\}, Landroid/content/Context;->getFilesDir\(\)Ljava/io/File;" + LS +
        r"    move-result-object v3" + LS +
        r"    \1\n"
        r"    sput-object p0, Lcom/github/stenzek/duckstation/NativeLibrary;->mApplicationContext:Landroid/content/Context;" + LS +
        r"    invoke-virtual \{v3\}, Ljava/io/File;->getAbsolutePath\(\)Ljava/lang/String;" + LS +
        r"    move-result-object v3" + LS +
        r"    sput-object v3, Lcom/github/stenzek/duckstation/NativeLibrary;->mDataDirectory:Ljava/lang/String;"
    )

    # mApplicationContext is written FIRST (before SDK/permission check), matching the
    # reference patch exactly.  v6 is used for both the null arg to getExternalFilesDir
    # and the File object in the root-dir branch (requires .locals 7).
    replacement = (
        f"    sput-object p0, L{PKG_PATH}/NativeLibrary;->mApplicationContext:Landroid/content/Context;\n\n"
        f"    sget v3, Landroid/os/Build$VERSION;->SDK_INT:I\n\n"
        f"    const/16 v4, 0x1e\n\n"
        f"    if-lt v3, v4, :use_root_dir_ds\n\n"
        f"    invoke-static {{}}, Landroid/os/Environment;->isExternalStorageManager()Z\n\n"
        f"    move-result v3\n\n"
        f"    if-nez v3, :use_root_dir_ds\n\n"
        f"    const/4 v6, 0x0\n\n"
        f"    invoke-virtual {{p0, v6}}, Landroid/content/Context;->getExternalFilesDir(Ljava/lang/String;)Ljava/io/File;\n\n"
        f"    move-result-object v3\n\n"
        f"    if-nez v3, :use_fallback_file_ds\n\n"
        f"    invoke-virtual {{p0}}, Landroid/content/Context;->getFilesDir()Ljava/io/File;\n\n"
        f"    move-result-object v3\n\n"
        f"    :use_fallback_file_ds\n"
        f"    invoke-virtual {{v3}}, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;\n\n"
        f"    move-result-object v3\n\n"
        f"    goto :store_data_dir_ds\n\n"
        f"    :use_root_dir_ds\n"
        f"    const-string v3, \"{ROOT_DIR}\"\n\n"
        f"    new-instance v6, Ljava/io/File;\n\n"
        f"    invoke-direct {{v6, v3}}, Ljava/io/File;-><init>(Ljava/lang/String;)V\n\n"
        f"    invoke-virtual {{v6}}, Ljava/io/File;->mkdirs()Z\n\n"
        f"    :store_data_dir_ds\n"
        f"    sput-object v3, L{PKG_PATH}/NativeLibrary;->mDataDirectory:Ljava/lang/String;\n\n"
        f"    const/4 v2, 0x0"
    )

    body2, n = pattern.subn(replacement, body, count=1)
    if n != 1:
        raise SystemExit(
            "Could not patch NativeLibrary data-dir block — smali shape differs from expected.\n"
            "The APK version may differ from what this script was written for."
        )

    text = text[: method_match.start(1)] + body2 + text[method_match.end(1):]
    path.write_text(text, encoding="utf-8")
    print("NativeLibrary: data-dir block patched")


# ---------------------------------------------------------------------------
# Activity smali helpers
# ---------------------------------------------------------------------------

def _make_prompt_method(cls: str) -> str:
    """Return the maybePromptAllFilesAccess() smali method, parameterized by activity class."""
    return f"""
.method private maybePromptAllFilesAccess()Z
    .locals 6

    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I

    const/4 v5, 0x0

    const/16 v1, 0x1e

    if-lt v0, v1, :cond_0

    invoke-static {{}}, Landroid/os/Environment;->isExternalStorageManager()Z

    move-result v0

    if-nez v0, :cond_0

    const-string v0, "RootStorage"

    invoke-virtual {{p0, v0, v5}}, L{PKG_PATH}/{cls};->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;

    move-result-object v0

    const-string v2, "AllFilesPromptShown"

    invoke-interface {{v0, v2, v5}}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z

    move-result v3

    if-nez v3, :cond_0

    invoke-interface {{v0}}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object v0

    const/4 v3, 0x1

    invoke-interface {{v0, v2, v3}}, Landroid/content/SharedPreferences$Editor;->putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences$Editor;

    move-result-object v0

    const-string v4, "AllFilesPromptPending"

    invoke-interface {{v0, v4, v3}}, Landroid/content/SharedPreferences$Editor;->putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences$Editor;

    move-result-object v0

    const-string v4, "AllFilesPromptResumeCount"

    invoke-interface {{v0, v4, v5}}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;

    move-result-object v0

    invoke-interface {{v0}}, Landroid/content/SharedPreferences$Editor;->apply()V

    :try_start_0
    new-instance v0, Landroid/content/Intent;

    const-string v2, "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"

    invoke-direct {{v0, v2}}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V

    new-instance v2, Ljava/lang/StringBuilder;

    invoke-direct {{v2}}, Ljava/lang/StringBuilder;-><init>()V

    const-string v4, "package:"

    invoke-virtual {{v2, v4}}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {{p0}}, L{PKG_PATH}/{cls};->getPackageName()Ljava/lang/String;

    move-result-object v4

    invoke-virtual {{v2, v4}}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {{v2}}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v2

    invoke-static {{v2}}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;

    move-result-object v2

    invoke-virtual {{v0, v2}}, Landroid/content/Intent;->setData(Landroid/net/Uri;)Landroid/content/Intent;

    invoke-virtual {{p0, v0}}, L{PKG_PATH}/{cls};->startActivity(Landroid/content/Intent;)V
    :try_end_0
    .catch Landroid/content/ActivityNotFoundException; {{:try_start_0 .. :try_end_0}} :catch_0

    return v3

    :catch_0
    new-instance v0, Landroid/content/Intent;

    const-string v2, "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"

    invoke-direct {{v0, v2}}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V

    invoke-virtual {{p0, v0}}, L{PKG_PATH}/{cls};->startActivity(Landroid/content/Intent;)V

    return v3

    :cond_0
    return v5
.end method

"""


def _on_create_injection(cls: str, super_class: str) -> str:
    """Smali inserted at top of onCreate (after .locals).
    If the prompt fires: call super.onCreate() and return — initializeOnce never runs.
    If no prompt needed: fall through to normal onCreate flow.
    """
    return (
        "\n"
        f"    invoke-direct {{p0}}, L{PKG_PATH}/{cls};->maybePromptAllFilesAccess()Z\n"
        "\n"
        "    move-result v5\n"
        "\n"
        "    if-eqz v5, :cond_after_prompt_ds\n"
        "\n"
        f"    invoke-super {{p0, p1}}, {super_class};->onCreate(Landroid/os/Bundle;)V\n"
        "\n"
        "    return-void\n"
        "\n"
        "    :cond_after_prompt_ds\n"
    )


def _on_start_guard() -> str:
    """Smali injected after super.onStart — waits for permission, calls recreate() when granted."""
    return (
        "\n"
        '    const-string v0, "RootStorage"\n'
        "\n"
        "    const/4 v1, 0x0\n"
        "\n"
        f"    invoke-virtual {{p0, v0, v1}}, L{PKG_PATH}/MainActivity;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;\n"
        "\n"
        "    move-result-object v0\n"
        "\n"
        '    const-string v2, "AllFilesPromptPending"\n'
        "\n"
        "    invoke-interface {v0, v2, v1}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z\n"
        "\n"
        "    move-result v3\n"
        "\n"
        "    if-eqz v3, :after_all_files_prompt_start_ds\n"
        "\n"
        "    invoke-static {}, Landroid/os/Environment;->isExternalStorageManager()Z\n"
        "\n"
        "    move-result v3\n"
        "\n"
        "    if-nez v3, :recreate_after_all_files_prompt_ds\n"
        "\n"
        '    const-string v3, "AllFilesPromptResumeCount"\n'
        "\n"
        "    invoke-interface {v0, v3, v1}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I\n"
        "\n"
        "    move-result v4\n"
        "\n"
        "    add-int/lit8 v4, v4, 0x1\n"
        "\n"
        "    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;\n"
        "\n"
        "    move-result-object v5\n"
        "\n"
        "    invoke-interface {v5, v3, v4}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;\n"
        "\n"
        "    move-result-object v5\n"
        "\n"
        "    invoke-interface {v5}, Landroid/content/SharedPreferences$Editor;->apply()V\n"
        "\n"
        "    const/4 v5, 0x2\n"
        "\n"
        "    if-lt v4, v5, :wait_for_all_files_prompt_ds\n"
        "\n"
        "    :recreate_after_all_files_prompt_ds\n"
        "    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;\n"
        "\n"
        "    move-result-object v0\n"
        "\n"
        "    invoke-interface {v0, v2, v1}, Landroid/content/SharedPreferences$Editor;->putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences$Editor;\n"
        "\n"
        "    move-result-object v0\n"
        "\n"
        "    invoke-interface {v0}, Landroid/content/SharedPreferences$Editor;->apply()V\n"
        "\n"
        "    invoke-virtual {p0}, Landroid/app/Activity;->recreate()V\n"
        "\n"
        "    :wait_for_all_files_prompt_ds\n"
        "\n"
        "    return-void\n"
        "\n"
        "    :after_all_files_prompt_start_ds\n"
    )


def _insert_after(text: str, pattern: str, insertion: str) -> tuple[str, int]:
    """Find first match of pattern and insert insertion immediately after it."""
    m = re.search(pattern, text)
    if not m:
        return text, 0
    pos = m.end()
    return text[:pos] + insertion + text[pos:], 1


def inject_activity_prompt(smali_root: Path, cls: str, inject_on_start_guard: bool) -> None:
    """Inject maybePromptAllFilesAccess() into an Activity class.

    Inserts the private helper method, then patches onCreate to call it as the very
    first instruction.  If inject_on_start_guard is True, also patches onStart to
    call recreate() once the permission is granted (MainActivity only).
    """
    path = smali_root / f"{PKG_PATH}/{cls}.smali"
    if not path.exists():
        raise SystemExit(f"{cls}.smali not found at {path}")

    text = path.read_text(encoding="utf-8")
    if "maybePromptAllFilesAccess" in text:
        print(f"{cls}: prompt already injected")
        return

    # --- insert the private helper before onCreate ---
    insert_at = text.find(".method public final onCreate(")
    if insert_at < 0:
        raise SystemExit(f"Could not find {cls}.onCreate to insert method before")
    text = text[:insert_at] + _make_prompt_method(cls) + text[insert_at:]

    # --- patch onCreate: inject prompt as first instruction ---
    on_create = re.search(
        r"(?s)(\.method (?:public|protected)(?: final)? onCreate\(Landroid/os/Bundle;\)V.*?\.end method)",
        text,
    )
    if not on_create:
        raise SystemExit(f"Could not capture {cls}.onCreate body")
    body = on_create.group(1)

    # Extract super class from the existing super.onCreate call
    super_m = re.search(
        r"invoke-super \{p0, p1\}, (L[^;]+);->onCreate\(Landroid/os/Bundle;\)V",
        body,
    )
    if not super_m:
        raise SystemExit(f"Could not find super.onCreate call in {cls}.onCreate")
    super_class = super_m.group(1)

    # Bump .locals to at least 6 (v5 holds the prompt result)
    body = re.sub(r"(?m)^    \.locals [0-5]$", "    .locals 6", body, count=1)

    # Insert prompt block right after the .locals line
    body2, n = _insert_after(body, r"    \.locals \d+\n", _on_create_injection(cls, super_class))
    if n != 1:
        raise SystemExit(f"Could not inject prompt call at top of {cls}.onCreate")
    text = text[: on_create.start(1)] + body2 + text[on_create.end(1):]

    if inject_on_start_guard:
        # --- patch onStart: inject recreate() guard after super.onStart ---
        on_start = re.search(
            r"(?s)(\.method (?:public|protected)(?: final)? onStart\(\)V.*?\.end method)",
            text,
        )
        if not on_start:
            raise SystemExit(f"Could not capture {cls}.onStart body")
        start_body = on_start.group(1)

        # Bump .locals to at least 6 (guard needs v4 and v5)
        start_body = re.sub(r"(?m)^    \.locals [0-5]$", "    .locals 6", start_body, count=1)

        start_body2, n = _insert_after(
            start_body,
            r"invoke-super \{p0\}, L[^;]+;->onStart\(\)V\n",
            _on_start_guard(),
        )
        if n != 1:
            raise SystemExit(f"Could not inject {cls}.onStart all-files prompt guard")

        text = text[: on_start.start(1)] + start_body2 + text[on_start.end(1):]

    path.write_text(text, encoding="utf-8")
    guard_note = " and onStart" if inject_on_start_guard else ""
    print(f"{cls}: permission prompt injected into onCreate{guard_note}")


# ---------------------------------------------------------------------------
# Native library binary patch
# ---------------------------------------------------------------------------

# Each entry: (abi, offset, original_bytes, replacement_bytes)
# Two conditional branch → NOP patches per ABI: the package-name path validation
# check in Java_com_github_stenzek_duckstation_NativeLibrary_initialize.
_SO_PATCHES: list[tuple[str, int, bytes, bytes]] = [
    # arm64-v8a: B.EQ → NOP (ARM64 NOP = 0xD503201F)
    ("arm64-v8a",    0x1E3A18, b"\x60\x0D\x00\x54", b"\x1F\x20\x03\xD5"),
    ("arm64-v8a",    0x1E3B54, b"\x80\x03\x00\x54", b"\x1F\x20\x03\xD5"),
    # armeabi-v7a: ARM32 BEQ → NOP (ARM32 NOP = 0xE320F000)
    ("armeabi-v7a",  0x126449, b"\x04\x00\x0A",     b"\xF0\x20\xE3"),
    ("armeabi-v7a",  0x126588, b"\xB0\x03\x00\x0A", b"\x00\xF0\x20\xE3"),
    # x86_64: JE rel32 → 6×NOP, JE rel8 → 2×NOP
    ("x86_64",       0x1BFFCF, b"\x0F\x84\xF5\x01\x00\x00", b"\x90\x90\x90\x90\x90\x90"),
    ("x86_64",       0x1C0150, b"\x74\x78",           b"\x90\x90"),
]


def patch_native_so(decoded_dir: Path) -> None:
    """Binary-patch libduckstation.so to remove the package-name path check."""
    for abi, offset, orig, replacement in _SO_PATCHES:
        so_path = decoded_dir / "lib" / abi / "libduckstation.so"
        if not so_path.exists():
            print(f"libduckstation.so ({abi}): not present, skipping")
            continue
        data = bytearray(so_path.read_bytes())
        actual = bytes(data[offset: offset + len(orig)])
        if actual == replacement:
            print(f"libduckstation.so ({abi}) @0x{offset:X}: already patched")
            continue
        if actual != orig:
            raise SystemExit(
                f"libduckstation.so ({abi}) @0x{offset:X}: unexpected bytes "
                f"{actual.hex()} (expected {orig.hex()}). "
                "APK version mismatch — update _SO_PATCHES for this build."
            )
        data[offset: offset + len(orig)] = replacement
        so_path.write_bytes(bytes(data))
        print(f"libduckstation.so ({abi}) @0x{offset:X}: patched {orig.hex()} -> {replacement.hex()}")


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------

def find_smali_root(decoded: Path) -> Path:
    for candidate in sorted(decoded.glob("smali*")):
        if (candidate / f"{PKG_PATH}/NativeLibrary.smali").exists():
            return candidate
    raise SystemExit(f"Could not find smali root containing {PKG_PATH}/NativeLibrary.smali")


def patch_decoded_dir(decoded_dir: Path) -> None:
    patch_manifest(decoded_dir / "AndroidManifest.xml")
    smali_root = find_smali_root(decoded_dir)
    patch_native_library(smali_root)
    patch_native_so(decoded_dir)
    inject_activity_prompt(smali_root, "MainActivity", inject_on_start_guard=True)
    inject_activity_prompt(smali_root, "SetupWizardActivity", inject_on_start_guard=False)
    print(f"\nPatched decoded APK at {decoded_dir}")
    print(f"Preferred data root : {ROOT_DIR}")
    print("Fallback data root  : getExternalFilesDir(null) -> getFilesDir()")


def run(cmd: list[str]) -> None:
    print("+ " + " ".join(cmd), flush=True)
    subprocess.run(cmd, check=True)


def tool_cmd(tool: str, label: str) -> list[str]:
    path = Path(tool)
    if path.suffix == ".jar":
        if not path.exists():
            raise SystemExit(f"{label} jar not found: {path}")
        return ["java", "-jar", str(path.resolve())]
    if os.sep in tool or (os.altsep and os.altsep in tool):
        if not path.exists():
            raise SystemExit(f"{label} executable not found: {path}")
        return [str(path.resolve())]
    if shutil.which(tool) is None:
        raise SystemExit(f"{label} not found on PATH: {tool}")
    return [tool]


def align_apk(input_apk: Path, output_apk: Path, zipalign_tool: str | None = None) -> None:
    """Run zipalign on the APK. Uses the system zipalign tool if available."""
    if zipalign_tool and shutil.which(zipalign_tool) or (zipalign_tool and Path(zipalign_tool).exists()):
        cmd = [zipalign_tool, "-f", "-p", "4", str(input_apk), str(output_apk)]
        subprocess.run(cmd, check=True)
        return
    # Try common SDK locations
    sdk_root = Path(os.environ.get("ANDROID_SDK_ROOT", "")) or Path(os.environ.get("ANDROID_HOME", ""))
    for bt in sorted((sdk_root / "build-tools").glob("*"), reverse=True) if sdk_root.exists() else []:
        candidate = bt / ("zipalign.exe" if os.name == "nt" else "zipalign")
        if candidate.exists():
            subprocess.run([str(candidate), "-f", "-p", "4", str(input_apk), str(output_apk)], check=True)
            return
    raise SystemExit("zipalign not found — pass --zipalign or set ANDROID_SDK_ROOT")


def default_output_path(input_apk: Path) -> Path:
    return input_apk.with_name(f"{input_apk.stem}-root-storage.apk")


def patch_apk(args: argparse.Namespace) -> None:
    input_apk = args.input.resolve()
    output_apk = (args.output or default_output_path(input_apk)).resolve()
    apktool = tool_cmd(args.apktool, "apktool")
    apksigner = None if args.unsigned else tool_cmd(args.apksigner, "apksigner")
    keystore = args.keystore.resolve() if args.keystore else None

    if not input_apk.exists():
        raise SystemExit(f"Input APK not found: {input_apk}")
    if input_apk.is_dir():
        raise SystemExit("Input must be an APK file, not a decoded directory")
    if not args.unsigned:
        if keystore is None:
            raise SystemExit("Signing requires --keystore, or pass --unsigned to skip")
        if not keystore.exists():
            raise SystemExit(f"Keystore not found: {keystore}")

    work_dir = Path(tempfile.mkdtemp(prefix="duckstation-root-storage-")).resolve()
    decoded = work_dir / "decoded"
    unsigned = work_dir / "unsigned.apk"
    aligned = work_dir / "aligned.apk"

    try:
        run(apktool + ["d", "-f", "--frame-path", str(work_dir / "framework"),
                       "-o", str(decoded), str(input_apk)])
        patch_decoded_dir(decoded)
        run(apktool + ["b", "--use-aapt2", "--frame-path", str(work_dir / "framework"),
                       "-o", str(unsigned), str(decoded)])
        align_apk(unsigned, aligned, zipalign_tool=args.zipalign)
        if args.unsigned:
            shutil.copyfile(aligned, output_apk)
        else:
            run(apksigner + ["sign",
                             "--ks", str(keystore),
                             "--ks-pass", f"pass:{args.ks_pass}",
                             "--key-pass", f"pass:{args.key_pass}",
                             "--out", str(output_apk),
                             str(aligned)])
            run(apksigner + ["verify", "--verbose", str(output_apk)])
        print(f"\nPatched APK: {output_apk}")
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Patch a DuckStation APK to prefer /storage/emulated/0/DuckStation "
            "for live user data on Android 11+ when MANAGE_EXTERNAL_STORAGE is granted."
        )
    )
    parser.add_argument("input", type=Path, help="Input APK path")
    parser.add_argument("-o", "--output", type=Path, help="Output APK path (default: <input>-root-storage.apk)")
    parser.add_argument("--apktool", default="apktool",
                        help="Path to apktool jar or executable (default: apktool on PATH)")
    parser.add_argument("--apksigner", default="apksigner",
                        help="Path to apksigner jar or executable (default: apksigner on PATH)")
    parser.add_argument("--keystore", type=Path, default=None,
                        help="Signing keystore (.jks/.p12)")
    parser.add_argument("--ks-pass", default="android",
                        help="Keystore password (default: android)")
    parser.add_argument("--key-pass", default="android",
                        help="Key password (default: android)")
    parser.add_argument("--zipalign", default=None,
                        help="Path to zipalign executable (auto-detected from ANDROID_SDK_ROOT if omitted)")
    parser.add_argument("--unsigned", action="store_true",
                        help="Write an unsigned APK (skip signing)")
    return parser.parse_args()


def main() -> None:
    patch_apk(parse_args())


if __name__ == "__main__":
    main()
