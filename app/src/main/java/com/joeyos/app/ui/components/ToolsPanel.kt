package com.joeyos.app.ui.components

import com.joeyos.app.R
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.AppLog
import com.joeyos.app.data.ALL_SYSTEMS
import com.joeyos.app.data.BiosCheck
import com.joeyos.app.data.BiosRequirements
import com.joeyos.app.data.BiosStatus
import com.joeyos.app.data.BiosSystemResult
import com.joeyos.app.data.InstalledApp
import com.joeyos.app.data.ChdConversion
import com.joeyos.app.data.RvzConversion
import com.joeyos.app.data.ThreeDsCompression
import com.joeyos.app.data.CompressJob
import com.joeyos.app.data.CompressOne
import com.joeyos.app.data.CompressStatus
import com.joeyos.app.data.CompressUndo
import com.joeyos.app.data.RomCompression
import com.joeyos.app.data.M3uGame
import com.joeyos.app.data.M3uPlaylist
import com.joeyos.app.data.M3uProgress
import com.joeyos.app.data.M3uStatus
import com.joeyos.app.data.M3uUndoAction
import com.joeyos.app.data.RomFinder
import com.joeyos.app.data.RomPatcher
import com.joeyos.app.data.RaHack
import com.joeyos.app.data.RaPatches
import com.joeyos.app.data.TreeUriPaths
import com.joeyos.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tools, ported from Chameleon one at a time: utilities that do something once and report
 * back, kept apart from settings (which are values that stay).
 *
 * A hub lists the tools; A opens one inside the tab, B returns to the hub (this BackHandler is
 * registered after the Settings page's, so it's asked first), and focus goes back to the row
 * that opened it.
 */
private enum class ToolScreen { Hub, Bios, Patch, M3u, Compress, RaHacks }

@Composable
fun ToolsPanel(
    installedApps: List<InstalledApp>,
    biosFolder: String,
    onBiosFolderChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    firstFocus: FocusRequester? = null,
    onCheckUpdates: () -> Unit = {},
    onShareLog: () -> Unit = {}
) {
    var screen by rememberSaveable { mutableStateOf(ToolScreen.Hub) }
    var lastTool by rememberSaveable { mutableStateOf(ToolScreen.Bios) }
    val hubRows = remember { ToolScreen.entries.associateWith { FocusRequester() } }
    val toolFirst = remember { FocusRequester() }

    BackHandler(enabled = screen != ToolScreen.Hub) { screen = ToolScreen.Hub }

    // Land focus when the screen changes: the tool's first item on open, the row that opened
    // it on the way back.
    LaunchedEffect(screen) {
        withFrameNanos { }
        runCatching {
            if (screen == ToolScreen.Hub) hubRows.getValue(lastTool).requestFocus()
            else { lastTool = screen; toolFirst.requestFocus() }
        }
    }

    when (screen) {
        ToolScreen.Hub -> LazyColumn(
            state = listState,
            modifier = modifier.padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
        ) {
            item {
                ToolRow(
                    label = "Check BIOS files",
                    detail = "Tells you which consoles have the BIOS they need and which are " +
                        "missing one, checked against your BIOS folder.",
                    onClick = { screen = ToolScreen.Bios },
                    modifier = Modifier
                        .focusRequester(hubRows.getValue(ToolScreen.Bios))
                        .then(if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                )
            }
            item {
                ToolRow(
                    label = "Patch a ROM",
                    detail = "Applies a patch to a game, for romhacks and fan translations " +
                        "(IPS, UPS, BPS, PPF, APS, xdelta). Writes a new patched file and never " +
                        "changes the original.",
                    onClick = { screen = ToolScreen.Patch },
                    modifier = Modifier.focusRequester(hubRows.getValue(ToolScreen.Patch))
                )
            }
            item {
                ToolRow(
                    label = "Generate .m3u playlists",
                    detail = "Turns multi-disc games into one playlist each, moving the discs " +
                        "out of sight so each game shows once in your emulator.",
                    onClick = { screen = ToolScreen.M3u },
                    modifier = Modifier.focusRequester(hubRows.getValue(ToolScreen.M3u))
                )
            }
            item {
                ToolRow(
                    label = "Compress ROMs",
                    detail = "Saves space: zips cartridge ROMs and converts discs to CHD, GameCube/Wii to RVZ " +
                        "and 3DS to ZCCI, the formats their emulators read directly. Can be undone.",
                    onClick = { screen = ToolScreen.Compress },
                    modifier = Modifier.focusRequester(hubRows.getValue(ToolScreen.Compress))
                )
            }
            item {
                ToolRow(
                    label = "RetroAchievements romhacks",
                    detail = "Finds romhacks and translations for the games you own, and applies one to make " +
                        "a version RetroAchievements recognises, achievements and all.",
                    onClick = { screen = ToolScreen.RaHacks },
                    modifier = Modifier.focusRequester(hubRows.getValue(ToolScreen.RaHacks))
                )
            }
            // ── System: kept last, below the tools ──
            item {
                SectionLabel("System", Modifier.padding(top = 6.dp))
            }
            item {
                val first = remember { FocusRequester() }
                Row(modifier = Modifier.fillMaxWidth().focusRow(first), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    JoeyButton("Check for updates", onCheckUpdates, Modifier.weight(1f).focusRequester(first))
                    JoeyButton("Share log", onShareLog, Modifier.weight(1f))
                }
            }
            item {
                Text("The log is also saved as a file: Internal storage › JoeyOS › joeyos.log",
                    fontSize = 9.sp, fontFamily = JoeyFont, color = TextFaint)
            }
        }
        ToolScreen.Patch -> PatchScreen(firstFocus = toolFirst, modifier = modifier)
        ToolScreen.M3u -> M3uScreen(firstFocus = toolFirst, modifier = modifier)
        ToolScreen.Compress -> CompressScreen(firstFocus = toolFirst, modifier = modifier)
        ToolScreen.RaHacks -> RaHacksScreen(firstFocus = toolFirst, modifier = modifier)
        ToolScreen.Bios -> BiosCheckScreen(
            installedApps = installedApps,
            biosFolder = biosFolder,
            onBiosFolderChange = onBiosFolderChange,
            firstFocus = toolFirst,
            modifier = modifier
        )
    }
}

/** A full-width tool action: a title and a line of detail, amber-ringed when focused. */
@Composable
internal fun ToolRow(label: String, detail: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CardRow(onClick = onClick, modifier = modifier) { focused -> CardText(label, detail, focused) }
}


/**
 * The BIOS check. Reads a folder the user keeps — a launcher can't see BIOS inside another
 * emulator's private storage on Android — so it looks where a BIOS folder usually is, or where
 * the user points it, and lists only consoles they have an emulator for.
 */
@Composable
private fun BiosCheckScreen(
    installedApps: List<InstalledApp>,
    biosFolder: String,
    onBiosFolderChange: (String) -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = TreeUriPaths.toPath(uri)
        if (path == null) {
            Toast.makeText(context, "That folder can't be used. Pick one on internal storage or an SD card.",
                Toast.LENGTH_LONG).show()
        } else onBiosFolderChange(path)
    }

    // Found automatically: a bios folder beside the ROM folders, one at the top of each storage,
    // and RetroArch's system folder (public storage, so readable, unlike an emulator's own).
    val autoFolders by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            val roots = RomFinder.storageRoots()
            val beside = roots.flatMap { root ->
                root.listFiles()?.filter { it.isDirectory && it.name.equals("roms", true) }.orEmpty()
                    .flatMap { roms -> roms.listFiles()?.filter { it.isDirectory && it.name.equals("bios", true) }.orEmpty() }
            }
            val top = roots.flatMap { root ->
                root.listFiles()?.filter { it.isDirectory && it.name.equals("bios", true) }.orEmpty()
            }
            val retroArch = File(Environment.getExternalStorageDirectory(), "RetroArch/system")
                .takeIf { it.isDirectory }
            (beside + top + listOfNotNull(retroArch)).map { it.absolutePath }.distinct()
        }
    }
    val folders = if (biosFolder.isNotBlank()) listOf(biosFolder) else autoFolders

    // Only consoles with an emulator installed; everything if none match (e.g. apps still loading).
    val installedSystems = remember(installedApps) {
        ALL_SYSTEMS.filter { sys -> sys.knownPackages.any { k -> installedApps.any { it.packageName.startsWith(k) } } }
            .map { it.id }.toSet()
    }
    var refresh by remember { mutableIntStateOf(0) }
    val results by produceState<List<BiosSystemResult>?>(null, folders, refresh, installedSystems) {
        value = null
        val r = BiosCheck.run(
            folders.map(::File),
            BiosRequirements.systems,
            limitTo = installedSystems.takeIf { ids -> BiosRequirements.systems.any { it.shortname in ids } }
        )
        val missing = r.filter { !it.satisfied }.map { it.system }
        AppLog.i("Tools", "BIOS check in ${folders.joinToString()}: ${r.size} consoles, " +
            if (missing.isEmpty()) "none missing" else "missing: ${missing.joinToString()}")
        value = r
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
    ) {
        item {
            SectionLabel("CHECK BIOS FILES")
        }
        item {
            ToolRow(
                label = "BIOS folder",
                detail = when {
                    biosFolder.isNotBlank() -> biosFolder
                    folders.isNotEmpty() -> "Found automatically: " + folders.joinToString("  •  ")
                    else -> "None found. Press A to choose the folder you keep BIOS in. JoeyOS can " +
                        "only read a folder like this: an emulator's own private storage is hidden " +
                        "from other apps on Android."
                },
                onClick = { runCatching { picker.launch(null) } },
                modifier = Modifier.focusRequester(firstFocus)
            )
        }
        if (biosFolder.isNotBlank()) {
            item {
                ToolRow(
                    label = "Use the folders found automatically",
                    detail = "Go back to checking ROMs/bios, a top-level bios folder and RetroArch's system folder.",
                    onClick = { onBiosFolderChange("") }
                )
            }
        }
        if (folders.isNotEmpty()) {
            item {
                ToolRow(label = "Re-check", detail = "Run the check again after adding files.",
                    onClick = { refresh++ })
            }
            when (val r = results) {
                null -> item { StatusLine("Checking…", TextFaint) }
                else -> {
                    if (r.isEmpty()) {
                        item {
                            StatusLine("None of your consoles need a BIOS, or the folder couldn't be read.", TextFaint)
                        }
                    } else {
                        val missing = r.count { !it.satisfied }
                        item {
                            StatusLine(
                                if (missing == 0) "Every console has what it needs."
                                else "$missing console${if (missing == 1) "" else "s"} missing a required BIOS.",
                                if (missing == 0) VerifiedColor else MissingColor,
                                bold = true
                            )
                        }
                        items(r.sortedBy { it.satisfied }, key = { it.system }) { BiosResultRow(it) }
                    }
                }
            }
        }
    }
}

/** One console's result. A focus stop of its own, so the D-pad can walk down the list. */
@Composable
private fun BiosResultRow(result: BiosSystemResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .readOnlyFocus()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            JoeyIcon(if (result.satisfied) R.drawable.ic_check else R.drawable.ic_close,
                if (result.satisfied) VerifiedColor else MissingColor, 16.dp, Modifier.padding(end = 6.dp))
            Text(result.system, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
        result.files.forEach { file ->
            val (label, color) = when (file.status) {
                BiosStatus.Verified     -> "verified" to VerifiedColor
                BiosStatus.Present      -> "present" to TextDim
                BiosStatus.Unreadable   -> "present, couldn't verify" to WarnColor
                BiosStatus.Unrecognised -> "present, unrecognised version" to WarnColor
                BiosStatus.Missing      ->
                    (if (file.required) "missing" else "missing (optional)") to
                        (if (file.required) MissingColor else TextFaint)
            }
            Text("${file.name}  •  $label", fontSize = 10.sp, fontFamily = JoeyFont, color = color)
        }
    }
}


/**
 * Applies an IPS/UPS/BPS/PPF/APS/xdelta patch to a base ROM (ported from Chameleon; the format
 * code is RomPatcher). The original is never changed. With all-files access JoeyOS writes the
 * result straight beside the original as "<name> (patched).<ext>" — one picker fewer than
 * Chameleon's flow, which matters on a controller — and only asks where to save when the ROM
 * came from somewhere with no file path (cloud storage, another app).
 */
@Composable
private fun PatchScreen(firstFocus: FocusRequester, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var patchUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var patchName by rememberSaveable { mutableStateOf<String?>(null) }
    var romUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var romName by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    val kind = remember(patchName) { patchName?.let { RomPatcher.kindOf(File(it)) } }

    fun displayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Reads both files, patches, and writes the result via [write]. Reports back in [message]. */
    fun runPatch(write: (ByteArray) -> String) {
        val p = patchUri ?: return
        val r = romUri ?: return
        scope.launch {
            busy = true
            message = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val patchBytes = context.contentResolver.openInputStream(p)!!.use { it.readBytes() }
                    val romBytes = context.contentResolver.openInputStream(r)!!.use { it.readBytes() }
                    write(RomPatcher.applyBytes(patchName ?: "", patchBytes, romBytes))
                }
            }
            busy = false
            result.onSuccess { where ->
                ok = true
                message = "Patched ROM written: $where"
                AppLog.i("Tools", "Patch: applied $patchName to $romName -> $where")
            }.onFailure {
                AppLog.w("Tools", "Patch: $patchName on $romName failed", it)
                ok = false
                message = (it as? RomPatcher.PatchException)?.message
                    ?: if (it is OutOfMemoryError) "This game is too large to patch on this device."
                    else "Couldn't apply the patch."
            }
        }
    }

    val patchPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        patchUri = uri; patchName = displayName(uri); message = null
    }
    val romPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        romUri = uri; romName = displayName(uri); message = null
    }
    fun suggestedName(base: String): String {
        val dot = base.lastIndexOf('.')
        return if (dot > 0) base.substring(0, dot) + " (patched)" + base.substring(dot) else "$base (patched)"
    }

    val ready = patchUri != null && romUri != null && kind != null && !busy
    var saving by remember { mutableStateOf(false) }
    // The best compressed form for the patched game, read from its extension and ROM folder.
    val patchFormat = remember(romName, romUri) {
        romName?.let { CompressOne.formatFor(context, it, null, romUri?.let(TreeUriPaths::documentToFile)?.parentFile) }
    }
    if (saving) {
        SaveResultDialog(
            title = "Save the patched game",
            suggestedName = suggestedName(romName ?: "rom"),
            // Only a ROM with a real path has a folder to save next to; otherwise Downloads.
            besideFolder = romUri?.let(TreeUriPaths::documentToFile)?.parentFile,
            prefKey = "patch_save_to",
            compressFormat = patchFormat,
            onSave = { target, _, compress ->
                saving = false
                runPatch { bytes ->
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                    val fmt = patchFormat
                    if (compress && fmt != null)
                        kotlinx.coroutines.runBlocking { CompressOne.compress(context, target, fmt, null) }?.absolutePath
                            ?: target.absolutePath
                    else target.absolutePath
                }
            },
            onCancel = { saving = false }
        )
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
    ) {
        item { SectionLabel("PATCH A ROM") }
        item {
            Text(
                "Choose a patch and the game it's for. UPS and BPS patches carry checksums, so a " +
                    "wrong base ROM is caught before anything is written. The original game is never changed.",
                fontSize = 10.sp, fontFamily = JoeyFont, color = TextFaint
            )
        }
        item {
            ToolRow(
                label = patchName ?: "Choose a patch file",
                detail = if (patchName != null && kind == null)
                    "This isn't a patch JoeyOS can apply. Use an .ips, .ups, .bps, .ppf, .aps or .xdelta file."
                else "An .ips, .ups, .bps, .ppf, .aps or .xdelta file.",
                onClick = { runCatching { patchPicker.launch(arrayOf("*/*")) } },
                modifier = Modifier.focusRequester(firstFocus)
            )
        }
        item {
            ToolRow(
                label = romName ?: "Choose the base ROM",
                detail = "The original, unmodified game this patch is for.",
                onClick = { runCatching { romPicker.launch(arrayOf("*/*")) } }
            )
        }
        item {
            ToolRow(
                label = if (busy) "Working…" else "Apply patch",
                detail = when {
                    busy  -> "Patching…"
                    ready -> "Next, name it and choose: next to the original, or Downloads."
                    else  -> "Choose a patch and a base ROM first."
                },
                onClick = { if (ready) saving = true }
            )
        }
        if (busy) {
            item {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(), color = Amber)
            }
        }
        message?.let {
            item { StatusLine(it, if (ok) VerifiedColor else WarnColor, bold = true) }
        }
    }
}


/** A tick-box row: amber-ringed when focused, a check mark when on. One focus target. */
@Composable
internal fun ToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    CardRow(onClick = { onToggle(!checked) }, modifier = modifier) { focused ->
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) Amber else Color.Transparent)
                .border(1.5.dp, if (checked) Amber else TextFaint, RoundedCornerShape(5.dp)),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            if (checked) JoeyIcon(R.drawable.ic_check, Color.Black, 16.dp)
        }
        CardText(label, detail, focused)
    }
}

private fun plural(n: Int) = if (n == 1) "" else "s"

private data class M3uConsolePlan(
    val label: String,
    val toCreate: List<M3uGame>,
    val conflicts: List<M3uGame>,
    val doneCount: Int,
) {
    val hasWork get() = toCreate.isNotEmpty() || conflicts.isNotEmpty()
    val games get() = toCreate + conflicts
    val status get() = buildList {
        if (toCreate.isNotEmpty()) add("${toCreate.size} to create")
        if (conflicts.isNotEmpty()) add("${conflicts.size} to decide")
        if (doneCount > 0) add("$doneCount already done")
    }.joinToString(", ").ifEmpty { "No multi-disc games found." }
}

/**
 * Generate .m3u playlists (ported from Chameleon; the file work is M3uPlaylist). Previews
 * everything first, asks before overwriting anything, moves files (never deletes them), and
 * can undo the whole run. Chameleon's "rescan the library / scrape" steps are dropped: JoeyOS
 * has no library of its own, only the reminder to refresh the emulator's game list.
 */
@Composable
private fun M3uScreen(firstFocus: FocusRequester, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var applying by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }
    var undoActions by remember { mutableStateOf<List<M3uUndoAction>>(emptyList()) }
    var changedAny by remember { mutableStateOf(false) }
    val overwrite = remember { mutableStateMapOf<String, Boolean>() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var progress by remember { mutableStateOf<M3uProgress?>(null) }
    val stopToken = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val cancelFocus = remember { FocusRequester() }

    val plans by produceState<List<M3uConsolePlan>?>(null, refresh) {
        value = null
        value = withContext(Dispatchers.IO) {
            M3uPlaylist.romFolders(RomFinder.storageRoots()).map { (system, folders) ->
                val games = folders.flatMap { M3uPlaylist.plan(it, system.label) }
                M3uConsolePlan(
                    label = system.label,
                    toCreate = games.filter { it.status == M3uStatus.Ready },
                    conflicts = games.filter { it.status == M3uStatus.Conflict },
                    doneCount = folders.sumOf { M3uPlaylist.doneCount(it) },
                )
            }.sortedBy { it.label }
        }
    }
    // Consoles with work start ticked, once their plan is known; a manual choice then sticks.
    LaunchedEffect(plans) { plans?.forEach { if (it.label !in selected) selected[it.label] = it.hasWork } }
    // While a run is going, the only control is Cancel: put focus on it so the D-pad has a target.
    LaunchedEffect(applying) {
        if (applying) { withFrameNanos { }; runCatching { cancelFocus.requestFocus() } }
    }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.keepFocus(firstFocus, listState, plans, applying).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
    ) {
        item { SectionLabel("GENERATE .M3U PLAYLISTS") }

        if (applying) {
            item {
                val p = progress
                val fraction = p?.takeIf { it.filesTotal > 0 }?.let { it.filesDone.toFloat() / it.filesTotal }
                if (fraction != null) androidx.compose.material3.LinearProgressIndicator(
                    progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = Amber)
                else androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Amber)
            }
            item {
                StatusLine(progress?.let {
                    if (it.current.isNotBlank()) "Moving ${it.current}  (${it.filesDone + 1} of ${it.filesTotal})"
                    else "Finishing…"
                } ?: "Starting…", TextPrimary)
            }
            item {
                ToolRow("Cancel", "Stops after the game in progress. Anything already done stays and can be undone.",
                    onClick = { stopToken.set(true) }, modifier = Modifier.focusRequester(cancelFocus))
            }
            return@LazyColumn
        }

        item {
            Text(
                "What this does: for each multi-disc game on the consoles you tick, it moves the disc " +
                    "files into a hidden .hidden folder and writes one .m3u playlist beside them, so your " +
                    "emulator shows a single entry per game. Files are moved, never deleted, and every move " +
                    "is checked before the original is removed. You can undo the whole run afterwards. " +
                    "Nothing happens until you press Create playlists.",
                fontSize = 10.sp, fontFamily = JoeyFont, color = TextFaint
            )
        }

        when (val list = plans) {
            null -> item { StatusLine("Scanning your ROMs folders…", TextFaint) }
            else -> {
                if (list.isEmpty()) {
                    item {
                        StatusLine("No disc consoles found in your ROMs folders. This works on PS1, " +
                            "Dreamcast, Saturn, Sega CD, PC Engine CD, Neo Geo CD, 3DO and PC-FX, the systems " +
                            "whose emulators read a playlist. PS2's don't, so its discs are left alone.", TextFaint)
                    }
                    // Keep something focusable on the page.
                    item { ToolRow("Scan again", "Look through your ROMs folders again.",
                        onClick = { refresh++ }, modifier = Modifier.focusRequester(firstFocus)) }
                    return@LazyColumn
                }
                item { SectionLabel("CONSOLES") }
                if (list.size >= 2) {
                    item {
                        val allOn = list.all { selected[it.label] == true }
                        ToolRow(if (allOn) "Select none" else "Select all",
                            if (allOn) "Untick every console below." else "Tick every console below.",
                            onClick = { list.forEach { selected[it.label] = !allOn } },
                            modifier = Modifier.focusRequester(firstFocus))
                    }
                }
                list.forEachIndexed { i, plan ->
                    item(key = "console_${plan.label}") {
                        ToggleRow(plan.label, plan.status, selected[plan.label] == true,
                            onToggle = { selected[plan.label] = it },
                            modifier = if (i == 0 && list.size < 2) Modifier.focusRequester(firstFocus) else Modifier)
                    }
                }

                val chosen = list.filter { selected[it.label] == true }
                val chosenConflicts = chosen.flatMap { it.conflicts }
                if (chosenConflicts.isNotEmpty()) {
                    item {
                        StatusLine("${chosenConflicts.size} game${plural(chosenConflicts.size)} on the ticked consoles " +
                            "already ha${if (chosenConflicts.size == 1) "s" else "ve"} something in the way. " +
                            "Choose which to overwrite:", MissingColor, bold = true)
                    }
                    chosenConflicts.forEach { game ->
                        item(key = "conflict_${game.m3u.absolutePath}") {
                            ToggleRow(
                                label = "Overwrite \u201C${game.baseName}\u201D",
                                detail = "${game.discs.size} discs  •  ${game.system}  •  " +
                                    game.conflicts.joinToString("; ") +
                                    ". On replaces what's there (that can't be undone). Off leaves this game untouched.",
                                checked = overwrite[game.m3u.absolutePath] == true,
                                onToggle = { overwrite[game.m3u.absolutePath] = it }
                            )
                        }
                    }
                }

                val toRun = chosen.flatMap { it.games }
                if (toRun.isNotEmpty()) {
                    item {
                        ToolRow("Create playlists",
                            "For the ticked consoles: creates the ready ones, plus any conflicts you chose to overwrite.",
                            onClick = {
                                summary = null; undoActions = emptyList(); changedAny = false
                                stopToken.set(false); applying = true
                                scope.launch {
                                    try {
                                        val run = M3uPlaylist.applyAll(
                                            toRun,
                                            overwrite = { overwrite[it.m3u.absolutePath] == true },
                                            shouldStop = { stopToken.get() },
                                            onProgress = { progress = it },
                                        )
                                        val made = run.results.count { it.created }
                                        val skipped = run.results.size - made
                                        summary = buildString {
                                            append("Created $made playlist${plural(made)}")
                                            if (skipped > 0) append(", left $skipped alone")
                                            append(".")
                                            if (stopToken.get()) append(" Cancelled the rest.")
                                        }
                                        undoActions = run.undo
                                        changedAny = made > 0
                                        AppLog.i("Tools", "m3u: $summary " + run.results.filter { !it.created }
                                            .joinToString("; ") { "${it.game}: ${it.message}" })
                                    } finally {
                                        overwrite.clear(); progress = null; applying = false; refresh++
                                    }
                                }
                            })
                    }
                } else if (list.none { it.hasWork }) {
                    item { StatusLine("Nothing to do: every multi-disc game already has its playlist.", VerifiedColor) }
                }

                summary?.let { item { StatusLine(it, VerifiedColor, bold = true) } }

                if (undoActions.isNotEmpty()) {
                    item {
                        ToolRow("Undo last run",
                            "Move the discs back out of .hidden and remove the playlists just created. " +
                                "Games you overwrote can't be restored.",
                            onClick = {
                                val actions = undoActions
                                undoActions = emptyList(); changedAny = false
                                scope.launch {
                                    val n = M3uPlaylist.undo(actions)
                                    summary = "Undone $n game${plural(n)}."
                                    AppLog.i("Tools", "m3u: undo put back $n game${plural(n)}")
                                    refresh++
                                }
                            })
                    }
                }
                if (changedAny) {
                    item {
                        StatusLine("Important: open your emulator and refresh or re-scan its game list. " +
                            "RetroArch and others remember the old disc paths, and a new playlist won't " +
                            "launch until the emulator has picked it up.", WarnColor, bold = true)
                    }
                }
            }
        }
    }
}


/** Something already in the way of one file's output, for the overwrite choices. */
private data class CompressConflict(val targetPath: String, val targetName: String, val sourceName: String, val system: String)

/** The output format a console compresses to, and the bundled tool it needs (null = none). */
private enum class CompressFormat(val label: String, val tool: String?) {
    Zip("ZIP", null), Chd("CHD", "chdman"), Rvz("RVZ", "dolphin-tool"), Zcci("ZCCI", "Azahar")
}

/**
 * One console in Compress ROMs. Each console has exactly one format, so exactly one of the job
 * lists is used: cartridges zipped, CD/DVD consoles to CHD, GameCube/Wii to RVZ, 3DS to ZCCI.
 */
private data class CompressConsolePlan(
    val label: String,
    val format: CompressFormat,
    val zipJobs: List<CompressJob> = emptyList(),
    val chdJobs: List<ChdConversion.ChdJob> = emptyList(),
    val rvzJobs: List<RvzConversion.RvzJob> = emptyList(),
    val dsJobs: List<ThreeDsCompression.Job> = emptyList(),
    val doneCount: Int,
    val skippedDsiware: Int = 0,
) {
    /** 3DS games that are still encrypted: compressing them would make an unplayable ZCCI. */
    val encrypted get() = dsJobs.count { it.status == ThreeDsCompression.Status.Encrypted }
    val readyCount get() = zipJobs.count { it.status == CompressStatus.Ready } +
        chdJobs.count { it.status == ChdConversion.ChdStatus.Ready } +
        rvzJobs.count { it.status == RvzConversion.RvzStatus.Ready } +
        dsJobs.count { it.status == ThreeDsCompression.Status.Ready }
    val conflicts get() =
        zipJobs.filter { it.status == CompressStatus.Conflict }
            .map { CompressConflict(it.target.absolutePath, it.target.name, it.source.name, label) } +
        chdJobs.filter { it.status == ChdConversion.ChdStatus.Conflict }
            .map { CompressConflict(it.target.absolutePath, it.target.name, it.source.name, label) } +
        rvzJobs.filter { it.status == RvzConversion.RvzStatus.Conflict }
            .map { CompressConflict(it.target.absolutePath, it.target.name, it.source.name, label) } +
        dsJobs.filter { it.status == ThreeDsCompression.Status.Conflict }
            .map { CompressConflict(it.target.absolutePath, it.target.name, it.source.name, label) }
    val hasWork get() = readyCount > 0 || conflicts.isNotEmpty()
    val status get() = buildList {
        val verb = if (format == CompressFormat.Zip || format == CompressFormat.Zcci) "to compress" else "to convert"
        if (readyCount > 0) add("$readyCount $verb")
        if (conflicts.isNotEmpty()) add("${conflicts.size} to decide")
        if (doneCount > 0) add("$doneCount already ${if (format == CompressFormat.Zip) "archived" else format.label}")
        // Deliberately excluded; said so it doesn't look like the tool missed them.
        if (skippedDsiware > 0) add("$skippedDsiware DSiWare left alone")
        if (encrypted > 0) add("$encrypted encrypted, left alone (decrypt them first)")
    }.joinToString(", ").ifEmpty { if (format == CompressFormat.Zip) "No loose ROMs found." else "No disc images found." }
}

/** What's running right now, for the progress bar and line. */
private data class CompressRunState(val done: Int, val total: Int, val current: String, val percent: Int = 0)

/**
 * Compress ROMs (ported from Chameleon, with its separate "Convert discs to CHD" tool merged in):
 * cartridge consoles are zipped (RomCompression), disc consoles converted to CHD with the bundled
 * chdman (ChdConversion). Every output is checked before anything is removed, keeping the
 * originals is an option, and a whole run can be undone. Chameleon's library rescan/scrape steps
 * are dropped (JoeyOS has no library); the reminder to refresh the emulator stays.
 */
@Composable
private fun CompressScreen(firstFocus: FocusRequester, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libDir = context.applicationInfo.nativeLibraryDir
    val chdman = remember { ChdConversion.binary(libDir) }
    val dolphinTool = remember { RvzConversion.binary(libDir) }
    val azahar = remember { ThreeDsCompression.binary(libDir) }
    fun available(f: CompressFormat) = when (f) {
        CompressFormat.Zip  -> true
        CompressFormat.Chd  -> chdman != null
        CompressFormat.Rvz  -> dolphinTool != null
        CompressFormat.Zcci -> azahar != null
    }
    var keepBoth by remember { mutableStateOf(false) }
    // Azahar has no verify step, so 3DS originals are kept unless the user asks otherwise.
    var remove3dsOriginals by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var applying by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }
    var zipUndo by remember { mutableStateOf<List<CompressUndo>>(emptyList()) }
    var chdUndo by remember { mutableStateOf<List<ChdConversion.ChdUndo>>(emptyList()) }
    var rvzUndo by remember { mutableStateOf<List<RvzConversion.RvzUndo>>(emptyList()) }
    var dsUndo by remember { mutableStateOf<List<ThreeDsCompression.Undo>>(emptyList()) }
    var changedAny by remember { mutableStateOf(false) }
    var showLicence by remember { mutableStateOf<String?>(null) }
    val overwrite = remember { mutableStateMapOf<String, Boolean>() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var progress by remember { mutableStateOf<CompressRunState?>(null) }
    val stopToken = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val cancelFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val missing = CompressFormat.entries.filter { !available(it) }.mapNotNull { it.tool }
        if (missing.isNotEmpty()) AppLog.w("Tools", "Compress: not available on this device " +
            "(${android.os.Build.SUPPORTED_ABIS.joinToString()}): ${missing.joinToString()}")
    }

    val plans by produceState<List<CompressConsolePlan>?>(null, refresh) {
        value = null
        value = withContext(Dispatchers.IO) {
            val roots = RomFinder.storageRoots()
            val carts = RomCompression.romFolders(roots).map { (system, folders) ->
                CompressConsolePlan(
                    label = system.label,
                    format = CompressFormat.Zip,
                    zipJobs = folders.flatMap { RomCompression.plan(it, system) },
                    doneCount = folders.sumOf { RomCompression.compressedCount(it) },
                    skippedDsiware = folders.sumOf { RomCompression.dsiExclusiveCount(it) },
                )
            }
            val discs = ChdConversion.romFolders(roots).map { (system, folders) ->
                CompressConsolePlan(
                    label = system.label,
                    format = CompressFormat.Chd,
                    chdJobs = folders.flatMap { ChdConversion.plan(it, system) },
                    doneCount = folders.sumOf { ChdConversion.doneCount(it) },
                )
            }
            val dolphin = RvzConversion.romFolders(roots).map { (system, folders) ->
                CompressConsolePlan(
                    label = system.label,
                    format = CompressFormat.Rvz,
                    rvzJobs = folders.flatMap { RvzConversion.plan(it, system) },
                    doneCount = folders.sumOf { RvzConversion.doneCount(it) },
                )
            }
            val threeDs = ThreeDsCompression.romFolders(roots).takeIf { it.isNotEmpty() }?.let { folders ->
                CompressConsolePlan(
                    label = "Nintendo 3DS",
                    format = CompressFormat.Zcci,
                    dsJobs = folders.flatMap { ThreeDsCompression.plan(it) },
                    doneCount = folders.sumOf { ThreeDsCompression.doneCount(it) },
                )
            }
            (carts + discs + dolphin + listOfNotNull(threeDs)).sortedBy { it.label }
        }
    }
    // Consoles with work start ticked (disc ones only when chdman is here to do it).
    LaunchedEffect(plans) {
        plans?.forEach { if (it.label !in selected) selected[it.label] = it.hasWork && available(it.format) }
    }
    LaunchedEffect(applying) {
        if (applying) { withFrameNanos { }; runCatching { cancelFocus.requestFocus() } }
    }

    showLicence?.let { asset ->
        val text = remember(asset) {
            runCatching { context.assets.open("licences/$asset").bufferedReader().use { it.readText() } }
                .getOrDefault("Licence text unavailable.")
        }
        JoeyPopup(title = "Licence", hint = "↑↓ read  •  B close", wide = true, onDismiss = { showLicence = null }) {
            // A focusable, scrollable body so the D-pad can read all of it.
            Text(text, fontSize = 9.sp, fontFamily = JoeyFont, color = TextDim,
                modifier = Modifier.weight(1f, fill = false)
                    .readOnlyFocus()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()))
            JoeyButton("Close", { showLicence = null }, Modifier.fillMaxWidth())
        }
    }

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.keepFocus(firstFocus, listState, plans, applying).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
    ) {
        item { SectionLabel("COMPRESS ROMS") }

        if (applying) {
            item {
                val p = progress
                val fraction = p?.takeIf { it.total > 0 }?.let { (it.done + it.percent / 100f) / it.total }
                if (fraction != null) androidx.compose.material3.LinearProgressIndicator(
                    progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = Amber)
                else androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Amber)
            }
            item {
                StatusLine(progress?.let {
                    if (it.current.isNotBlank())
                        "${it.current}  (${it.done + 1} of ${it.total})" + if (it.percent > 0) ", ${it.percent}%" else ""
                    else "Finishing…"
                } ?: "Starting…", TextPrimary)
            }
            item {
                ToolRow("Cancel", "Stops after the file in progress. Anything already done stays and can be undone.",
                    onClick = { stopToken.set(true) }, modifier = Modifier.focusRequester(cancelFocus))
            }
            return@LazyColumn
        }

        item {
            Text(
                "What this does: on the consoles you tick, each game is packed into the format its emulator " +
                    "reads directly: cartridge ROMs into .zip, CD and DVD images (cue, gdi, iso) into .chd, " +
                    "GameCube and Wii discs into .rvz, and decrypted 3DS games into .zcci. Unless you keep " +
                    "both below, each original is removed once its new file is checked. The whole run can be " +
                    "undone. DSiWare, DSi-only DS games and encrypted 3DS games are left alone. Nothing " +
                    "happens until you press Compress.",
                fontSize = 10.sp, fontFamily = JoeyFont, color = TextFaint
            )
        }
        item {
            ToggleRow(
                label = "Keep the original files too",
                detail = "Leaves each original in place beside its new file. Off removes the original after " +
                    "the new file is verified, which is what saves the space.",
                checked = keepBoth,
                onToggle = { keepBoth = it },
                modifier = Modifier.focusRequester(firstFocus)
            )
        }
        val unavailable = CompressFormat.entries.filter { !available(it) }
        if (unavailable.isNotEmpty()) {
            item {
                StatusLine(unavailable.joinToString(", ") { it.label } + " conversion isn't available on this " +
                    "device, so those consoles can't be selected. The others still work.", WarnColor, bold = true)
            }
        }

        when (val list = plans) {
            null -> item { StatusLine("Scanning your ROMs folders…", TextFaint) }
            else -> {
                if (list.isEmpty()) {
                    item {
                        StatusLine("No consoles to compress found in your ROMs folders. Cartridge consoles " +
                            "(NES, SNES, N64, Game Boy, GBC, GBA, DS, Genesis, Master System, Game Gear, Neo Geo " +
                            "Pocket) are zipped; PS1, PS2, PSP, Saturn, Sega CD and Dreamcast go to CHD; GameCube " +
                            "and Wii to RVZ; 3DS to ZCCI. Arcade zips are romsets and are never repacked.", TextFaint)
                    }
                    return@LazyColumn
                }
                item { SectionLabel("CONSOLES") }
                val selectable = list.filter { available(it.format) }
                if (selectable.size >= 2) {
                    item {
                        val allOn = selectable.all { selected[it.label] == true }
                        ToolRow(if (allOn) "Select none" else "Select all",
                            if (allOn) "Untick every console below." else "Tick every console below.",
                            onClick = { selectable.forEach { selected[it.label] = !allOn } })
                    }
                }
                list.forEach { plan ->
                    item(key = "console_${plan.format}_${plan.label}") {
                        val usable = available(plan.format)
                        ToggleRow("${plan.label}  ·  ${plan.format.label}",
                            if (usable) plan.status else "Needs ${plan.format.tool}, which isn't available on this device.",
                            usable && selected[plan.label] == true,
                            onToggle = { if (usable) selected[plan.label] = it })
                    }
                }

                val chosen = list.filter { selected[it.label] == true && available(it.format) }
                if (chosen.any { it.format == CompressFormat.Zcci } && !keepBoth) {
                    item {
                        ToggleRow(
                            label = "Also remove the original 3DS files",
                            detail = "Off keeps each .3ds/.cci beside its new .zcci. Azahar's tool has no way to " +
                                "verify a ZCCI after writing it, so the originals are kept unless you choose this.",
                            checked = remove3dsOriginals,
                            onToggle = { remove3dsOriginals = it }
                        )
                    }
                }
                val chosenConflicts = chosen.flatMap { it.conflicts }
                if (chosenConflicts.isNotEmpty()) {
                    item {
                        StatusLine("${chosenConflicts.size} file${plural(chosenConflicts.size)} on the ticked consoles " +
                            "already ha${if (chosenConflicts.size == 1) "s" else "ve"} its compressed version. " +
                            "Choose which to overwrite:", MissingColor, bold = true)
                    }
                    chosenConflicts.forEach { c ->
                        item(key = "conflict_${c.targetPath}") {
                            ToggleRow(
                                label = "Overwrite \u201C${c.targetName}\u201D",
                                detail = "${c.sourceName}  •  ${c.system}. On replaces the file already there. " +
                                    "Off leaves this one alone.",
                                checked = overwrite[c.targetPath] == true,
                                onToggle = { overwrite[c.targetPath] = it }
                            )
                        }
                    }
                }

                val zipRun = chosen.flatMap { it.zipJobs }
                val chdRun = chosen.flatMap { it.chdJobs }
                val rvzRun = chosen.flatMap { it.rvzJobs }
                // Encrypted 3DS games are never sent to the tool.
                val dsRun = chosen.flatMap { it.dsJobs }.filter { it.status != ThreeDsCompression.Status.Encrypted }
                val total = zipRun.size + chdRun.size + rvzRun.size + dsRun.size
                if (total > 0) {
                    item {
                        ToolRow("Compress",
                            if (keepBoth) "Writes the compressed version beside each file, keeping both."
                            else "Writes the compressed version and removes each original once it's verified.",
                            onClick = {
                                summary = null; zipUndo = emptyList(); chdUndo = emptyList()
                                rvzUndo = emptyList(); dsUndo = emptyList(); changedAny = false
                                stopToken.set(false); applying = true
                                AppLog.i("Tools", "Compress: starting ${zipRun.size} to zip, ${chdRun.size} to CHD, " +
                                    "${rvzRun.size} to RVZ, ${dsRun.size} to ZCCI on " +
                                    chosen.joinToString { "${it.label} (${it.format})" } +
                                    if (keepBoth) ", keeping originals" else "")
                                scope.launch {
                                    var zipped = 0; var converted = 0; var rvzDone = 0; var dsDone = 0; var skipped = 0
                                    try {
                                        if (zipRun.isNotEmpty()) {
                                            val run = RomCompression.compressAll(
                                                zipRun,
                                                removeOriginal = !keepBoth,
                                                overwrite = { overwrite[it.target.absolutePath] == true },
                                                shouldStop = { stopToken.get() },
                                                onProgress = { progress = CompressRunState(it.filesDone, total, "Compressing ${it.current}".takeIf { _ -> it.current.isNotBlank() } ?: "") },
                                            )
                                            zipped = run.results.count { it.compressed }
                                            skipped += run.results.size - zipped
                                            zipUndo = run.undo
                                            run.results.filter { !it.compressed }.forEach {
                                                AppLog.i("Tools", "Compress: left ${it.name}: ${it.message}")
                                            }
                                        }
                                        if (chdRun.isNotEmpty() && chdman != null && !stopToken.get()) {
                                            val offset = zipRun.size
                                            val run = ChdConversion.convertAll(
                                                chdman,
                                                chdRun,
                                                removeSource = !keepBoth,
                                                overwrite = { overwrite[it.target.absolutePath] == true },
                                                shouldStop = { stopToken.get() },
                                                onProgress = { progress = CompressRunState(offset + it.filesDone, total,
                                                    if (it.current.isNotBlank()) "Converting ${it.current}" else "", it.percent) },
                                            )
                                            converted = run.results.count { it.converted }
                                            skipped += run.results.size - converted
                                            chdUndo = run.undo
                                            run.results.filter { !it.converted }.forEach {
                                                AppLog.i("Tools", "Compress: left ${it.name}: ${it.message}")
                                            }
                                        }
                                        if (rvzRun.isNotEmpty() && dolphinTool != null && !stopToken.get()) {
                                            val offset = zipRun.size + chdRun.size
                                            val run = RvzConversion.convertAll(
                                                dolphinTool,
                                                rvzRun,
                                                removeSource = !keepBoth,
                                                overwrite = { overwrite[it.target.absolutePath] == true },
                                                shouldStop = { stopToken.get() },
                                                onProgress = { progress = CompressRunState(offset + it.filesDone, total,
                                                    if (it.current.isNotBlank()) "Converting ${it.current}" else "", it.percent) },
                                            )
                                            rvzDone = run.results.count { it.converted }
                                            skipped += run.results.size - rvzDone
                                            rvzUndo = run.undo
                                            run.results.filter { !it.converted }.forEach {
                                                AppLog.i("Tools", "Compress: left ${it.name}: ${it.message}")
                                            }
                                        }
                                        if (dsRun.isNotEmpty() && azahar != null && !stopToken.get()) {
                                            val offset = zipRun.size + chdRun.size + rvzRun.size
                                            val run = ThreeDsCompression.compressAll(
                                                azahar,
                                                dsRun,
                                                removeSource = !keepBoth && remove3dsOriginals,
                                                overwrite = { overwrite[it.target.absolutePath] == true },
                                                shouldStop = { stopToken.get() },
                                                onProgress = { progress = CompressRunState(offset + it.filesDone, total,
                                                    if (it.current.isNotBlank()) "Compressing ${it.current}" else "") },
                                            )
                                            dsDone = run.results.count { it.compressed }
                                            skipped += run.results.size - dsDone
                                            dsUndo = run.undo
                                            run.results.filter { !it.compressed }.forEach {
                                                AppLog.i("Tools", "Compress: left ${it.name}: ${it.message}")
                                            }
                                        }
                                        summary = buildString {
                                            val parts = buildList {
                                                if (zipRun.isNotEmpty()) add("zipped $zipped ROM${plural(zipped)}")
                                                if (chdRun.isNotEmpty()) add("converted $converted disc${plural(converted)} to CHD")
                                                if (rvzRun.isNotEmpty()) add("converted $rvzDone disc${plural(rvzDone)} to RVZ")
                                                if (dsRun.isNotEmpty()) add("compressed $dsDone 3DS game${plural(dsDone)}")
                                            }
                                            append(parts.joinToString(", ").replaceFirstChar { it.uppercase() })
                                            if (skipped > 0) append(", left $skipped alone")
                                            append(".")
                                            if (stopToken.get()) append(" Cancelled the rest.")
                                        }
                                        changedAny = zipped + converted + rvzDone + dsDone > 0
                                        AppLog.i("Tools", "Compress: $summary")
                                    } catch (e: Exception) {
                                        AppLog.e("Tools", "Compress: run failed", e)
                                        summary = "Something went wrong. Anything already done can be undone."
                                    } finally {
                                        overwrite.clear(); progress = null; applying = false; refresh++
                                    }
                                }
                            })
                    }
                } else if (list.none { it.hasWork }) {
                    item { StatusLine("Nothing to do: everything on these consoles is already compressed.", VerifiedColor) }
                }

                summary?.let { item { StatusLine(it, VerifiedColor, bold = true) } }

                if (zipUndo.isNotEmpty() || chdUndo.isNotEmpty() || rvzUndo.isNotEmpty() || dsUndo.isNotEmpty()) {
                    item {
                        ToolRow("Undo last run",
                            "Put the originals back and remove the files just made. Where you kept both, this only " +
                                "removes the new files. A removed disc is rebuilt from its new file: a CHD's iso comes back " +
                                "as cue and bin, and an RVZ comes back as an iso.",
                            onClick = {
                                val zips = zipUndo; val chds = chdUndo; val rvzs = rvzUndo; val dss = dsUndo
                                zipUndo = emptyList(); chdUndo = emptyList(); rvzUndo = emptyList(); dsUndo = emptyList()
                                changedAny = false
                                scope.launch {
                                    val n = RomCompression.undo(zips) +
                                        (if (chdman != null) ChdConversion.undo(chdman, chds) else 0) +
                                        (if (dolphinTool != null) RvzConversion.undo(dolphinTool, rvzs) else 0) +
                                        (if (azahar != null) ThreeDsCompression.undo(azahar, dss) else 0)
                                    summary = "Undone $n file${plural(n)}."
                                    AppLog.i("Tools", "Compress: undo restored $n file${plural(n)}")
                                    refresh++
                                }
                            })
                    }
                }
                if (changedAny) {
                    item {
                        StatusLine("Important: open your emulator and refresh or re-scan its game list, so it finds " +
                            "the games at their new names. Games already in Recently Played point at the old file " +
                            "until you play them again from the emulator.", WarnColor, bold = true)
                    }
                }
                item { SectionLabel("BUNDLED TOOLS") }
                item {
                    ToolRow("About chdman", "CHD conversion, from the MAME project. GPL-2.0: licence and source offer.",
                        onClick = { showLicence = "gpl-2.0-chdman.txt" })
                }
                item {
                    ToolRow("About dolphin-tool", "RVZ conversion, from the Dolphin project. GPL-2.0: licence and source offer.",
                        onClick = { showLicence = "gpl-2.0-dolphintool.txt" })
                }
                item {
                    ToolRow("About Azahar", "3DS compression, from the Azahar project. GPL-2.0: licence and source offer.",
                        onClick = { showLicence = "gpl-2.0-azahar.txt" })
                }
            }
        }
    }
}


/** Display names for the consoles the romhack index covers. */
private val RaConsoleNames = mapOf(
    "nes" to "NES", "fds" to "Famicom Disk System", "snes" to "SNES", "n64" to "Nintendo 64",
    "gb" to "Game Boy", "gbc" to "Game Boy Color", "gba" to "Game Boy Advance", "nds" to "Nintendo DS",
    "genesis" to "Genesis / Mega Drive", "master" to "Master System", "gamegear" to "Game Gear",
    "sega32x" to "32X", "segacd" to "Sega CD", "saturn" to "Saturn", "dreamcast" to "Dreamcast",
    "tg16" to "PC Engine / TurboGrafx-16", "pcfx" to "PC-FX", "neogeocd" to "Neo Geo CD",
    "ngp" to "Neo Geo Pocket", "msx" to "MSX", "3do" to "3DO", "gc" to "GameCube", "wii" to "Wii",
    "ps2" to "PlayStation 2", "psp" to "PSP", "psx" to "PlayStation",
)

/** RetroAchievements' console ids for the romhack consoles, to look up each hack's RA title. */
private val RaConsoleIds = mapOf(
    "nes" to listOf(7), "fds" to listOf(81), "snes" to listOf(3), "n64" to listOf(2, 78),
    "gb" to listOf(4), "gbc" to listOf(6), "gba" to listOf(5), "nds" to listOf(18),
    "genesis" to listOf(1), "master" to listOf(11), "gamegear" to listOf(15), "sega32x" to listOf(10),
    "segacd" to listOf(9), "saturn" to listOf(39), "dreamcast" to listOf(40), "tg16" to listOf(8, 76),
    "pcfx" to listOf(49), "neogeocd" to listOf(56), "ngp" to listOf(14), "msx" to listOf(29),
    "3do" to listOf(43), "gc" to listOf(16), "wii" to listOf(19), "ps2" to listOf(21),
    "psp" to listOf(41), "psx" to listOf(12),
)

/** How a hack is shown: a proper title, and its version, author and kind underneath. */
private data class HackLabel(val title: String, val detail: String)

private val RaTitleTags = Regex("~[^~]+~\\s*")
private val VersionLike = Regex("^v?\\d+([._]\\d+)*[a-z]?$|^v\\d.*", RegexOption.IGNORE_CASE)
private val NotAnAuthor = setOf("usa", "europe", "japan", "world", "en", "eng", "english", "complete", "beta",
    "demo", "alt menus", "full game", "classic")

/**
 * A readable name for a hack. The index's names are the patch files' own ("FE8 - Storge (v1.22)
 * (knabepricer)", "old/…", or a bare "34181-FE8-DreamOfFiveDE"), so: RetroAchievements' own title
 * for the hack when we have it (the archive is filed under the hack's RA game id), else the patch
 * name tidied up; the version and author move to the detail line. Translations keep the patch
 * name, since RA files them under the base game's title.
 */
private fun hackLabel(hack: RaHack, raTitles: Map<Int, String>): HackLabel {
    val file = hack.path.substringAfterLast('/')
    val raId = Regex("^(\\d+)-").find(file)?.groupValues?.get(1)?.toIntOrNull()
    var raw = hack.name.substringAfterLast('/').trim()
    val groups = mutableListOf<String>()
    while (true) {
        val m = Regex("\\s*\\(([^()]*)\\)\\s*$").find(raw) ?: break
        if (m.range.first == 0) break
        groups.add(0, m.groupValues[1].trim()); raw = raw.substring(0, m.range.first).trim()
    }
    val version = groups.firstOrNull { VersionLike.matches(it) }?.let { if (it.startsWith("v", true)) it else "v$it" }
    val author = groups.lastOrNull { !VersionLike.matches(it) && it.lowercase() !in NotAnAuthor }
    if (Regex("^\\d+-").containsMatchIn(raw)) {
        raw = raw.replace(Regex("^\\d+-[A-Za-z0-9]+-"), "")
            .replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").replace('_', ' ')
    }
    raw = raw.replace(Regex("^[A-Z0-9]{2,5}\\s-\\s"), "")
    val raTitle = raId?.let { raTitles[it] }?.takeIf { hack.type != "Translation" }
        ?.replace(RaTitleTags, "")?.trim()?.takeIf { it.isNotEmpty() }
    val kind = when (hack.type) { "Hacks" -> "Hack"; "Fixes" -> "Fix"; "Subsets" -> "Subset"; else -> hack.type }
    return HackLabel(raTitle ?: raw.ifBlank { hack.name }, listOfNotNull(version, author, kind).joinToString("  ·  "))
}

/** A game the user owns that has one or more RetroAchievements romhacks. */
private data class RaGameMatch(val rom: File, val title: String, val hacks: List<RaHack>)

/** Past this, a game isn't checked: patching loads the whole file into memory. */
private const val RaMaxRomBytes = 1024L * 1024 * 1024

/**
 * RetroAchievements romhacks (ported from Chameleon; the lookup is RaPatches, the patching
 * RomPatcher). Three levels, each a list: consoles, your games that have hacks, a game's hacks.
 * B steps back one level (these BackHandlers are registered after the Tools hub's, so they're
 * asked first) and focus returns to the row you came from.
 */
@Composable
private fun RaHacksScreen(firstFocus: FocusRequester, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { RaPatches.cacheDir = context.cacheDir; Unit }
    val raRepo = remember { com.joeyos.app.data.RetroAchievementsRepository(context) }

    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var openGame by remember { mutableStateOf<RaGameMatch?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var checked by remember { mutableIntStateOf(0) }
    var indexMissing by remember { mutableStateOf(false) }
    var retries by remember { mutableIntStateOf(0) }
    val consoleRows = remember { mutableMapOf<String, FocusRequester>() }
    val gameRows = remember { mutableMapOf<String, FocusRequester>() }
    val levelFirst = remember { FocusRequester() }
    var lastConsole by remember { mutableStateOf<String?>(null) }
    var lastGame by remember { mutableStateOf<String?>(null) }
    var pendingHack by remember { mutableStateOf<RaHack?>(null) }

    // RetroAchievements' own titles for this console's hacks (one request, kept a week).
    val raTitles by produceState(emptyMap<Int, String>(), chosen) {
        value = chosen?.let { RaConsoleIds[it] }?.let { raRepo.fetchGameTitles(it) } ?: emptyMap()
    }
    val hackLabels = remember(openGame, raTitles) {
        openGame?.hacks.orEmpty().map { it to hackLabel(it, raTitles) }.sortedBy { it.second.title.lowercase() }
    }

    // Nothing is downloaded or written until the user names the hack and picks where it goes.
    pendingHack?.let { hack ->
        val game = openGame
        val console = chosen
        if (game != null && console != null) {
            val zipped = game.rom.extension.equals("zip", ignoreCase = true)
            val hackFormat = remember(game) {
                CompressOne.formatFor(context, RaPatches.baseRomName(game.rom), console, game.rom.parentFile)
            }
            SaveResultDialog(
                title = "Save the romhack",
                suggestedName = remember(hack) { raSuggestedName(game, hackLabel(hack, raTitles).title) },
                besideFolder = game.rom.parentFile,
                prefKey = "romhack_save_to",
                compressFormat = hackFormat,
                // Compressed by default when the original already is (a zipped or CHD library).
                compressDefault = zipped || game.rom.extension.lowercase() in setOf("chd", "rvz", "zcci", "7z"),
                onSave = { target, _, compress ->
                    pendingHack = null
                    busy = true; message = null
                    scope.launch {
                        val (worked, text) = createRaHack(context, console, game, hack, target,
                            compress = hackFormat.takeIf { compress })
                        ok = worked; message = text; busy = false; openGame = null
                    }
                },
                onCancel = { pendingHack = null }
            )
        }
    }

    BackHandler(enabled = openGame != null && !busy) { openGame = null }
    BackHandler(enabled = openGame == null && chosen != null && !busy) { chosen = null; message = null }

    val folders by produceState<Map<String, List<File>>?>(null) {
        value = withContext(Dispatchers.IO) { RaPatches.romFolders(RomFinder.storageRoots()) }
    }

    // The games with hacks: checksum each ROM in the console's folders and look it up.
    val matches by produceState<List<RaGameMatch>?>(null, chosen, folders, retries) {
        val console = chosen ?: run { value = null; return@produceState }
        val dirs = folders?.get(console).orEmpty()
        value = null
        checked = 0
        indexMissing = false
        value = withContext(Dispatchers.IO) {
            if (!RaPatches.indexAvailable(console)) {
                AppLog.w("Tools", "Romhacks: couldn't load the romhack list for $console")
                indexMissing = true
                return@withContext emptyList()
            }
            val roms = dirs.flatMap { it.listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty().toList() }
            val skipped = roms.count { it.length() > RaMaxRomBytes }
            val found = roms.filter { it.length() <= RaMaxRomBytes }.mapNotNull { rom ->
                val crc = RaPatches.crcOf(rom)
                checked++
                crc ?: return@mapNotNull null
                val hacks = RaPatches.hacksFor(console, crc)
                if (hacks.isEmpty()) null else RaGameMatch(rom, rom.nameWithoutExtension, hacks)
            }.sortedBy { it.title.lowercase() }
            AppLog.i("Tools", "Romhacks: ${RaConsoleNames[console] ?: console}: checked ${roms.size - skipped} " +
                "game(s), ${found.size} with hacks" + if (skipped > 0) ", skipped $skipped over 1 GB" else "")
            found
        }
    }

    // Focus: the first row when a level opens, the row you came from when you step back. The row
    // is scrolled into view first: the list keeps its scroll position across levels, and a row
    // that's off screen doesn't exist yet in a lazy list, so it can't take focus. Found on device:
    // opening Fire Emblem, far down the GBA list, opened its 76 hacks scrolled part-way, the first
    // hack wasn't there to focus, and nothing was focused, so the controls did nothing.
    val listState = rememberLazyListState()
    LaunchedEffect(chosen, openGame, matches != null, folders != null, busy) {
        if (busy) return@LaunchedEffect
        // Rows above the level's list: the title, a result message, and the level's own heading.
        val header = 2 + (if (message != null) 1 else 0)
        val (index, target) = when {
            openGame != null -> header to levelFirst
            chosen != null && matches != null -> {
                val i = matches.orEmpty().indexOfFirst { it.rom.absolutePath == lastGame }
                if (i >= 0) (header + i) to gameRows.getValue(lastGame!!) else header to levelFirst
            }
            chosen == null && folders != null -> {
                val keys = folders.orEmpty().keys.sortedBy { RaConsoleNames[it] ?: it }
                val i = keys.indexOf(lastConsole)
                if (i >= 0) (header + i) to consoleRows.getValue(lastConsole!!) else header to firstFocus
            }
            else -> return@LaunchedEffect
        }
        runCatching { listState.scrollToItem((index - 1).coerceAtLeast(0)) }
        withFrameNanos { }
        if (runCatching { target.requestFocus() }.isFailure) {
            // Never leave the page with nothing focused: fall back to the top of the list.
            runCatching { listState.scrollToItem(0) }
            withFrameNanos { }
            runCatching { (if (openGame != null || chosen != null) levelFirst else firstFocus).requestFocus() }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
    ) {
        item { SectionLabel("RETROACHIEVEMENTS ROMHACKS") }

        if (busy) {
            item { StatusLine("Downloading and applying the patch…", TextPrimary) }
            item { androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Amber) }
            return@LazyColumn
        }
        message?.let { item { StatusLine(it, if (ok) VerifiedColor else WarnColor, bold = true) } }

        val console = chosen
        val game = openGame
        when {
            // ── Level 1: consoles ──
            console == null -> {
                item {
                    Text("Pick a console. JoeyOS checks the games you own against the RetroAchievements patch " +
                        "set and lists the ones with a hack or translation. Each is applied to a copy: your " +
                        "original game is never changed.",
                        fontSize = 10.sp, fontFamily = JoeyFont, color = TextFaint)
                }
                val list = folders
                when {
                    list == null -> item { StatusLine("Looking for your ROMs folders…", TextFaint) }
                    list.isEmpty() -> item {
                        StatusLine("None of your ROMs folders are for a console the patch set covers.", TextFaint)
                    }
                    else -> list.keys.sortedBy { RaConsoleNames[it] ?: it }.forEachIndexed { i, key ->
                        item(key = "console_$key") {
                            ToolRow(RaConsoleNames[key] ?: key, list.getValue(key).joinToString { it.absolutePath },
                                onClick = { lastConsole = key; lastGame = null; chosen = key; message = null },
                                modifier = Modifier
                                    .focusRequester(consoleRows.getOrPut(key) { FocusRequester() })
                                    .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier))
                        }
                    }
                }
            }
            // ── Level 2: your games that have hacks ──
            game == null -> {
                item { SectionLabel((RaConsoleNames[console] ?: console)) }
                val list = matches
                when {
                    list == null -> {
                        item { StatusLine("Checking your games… ($checked so far)", TextFaint) }
                        item { androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Amber) }
                    }
                    list.isEmpty() && indexMissing -> item {
                        ToolRow("Couldn't load the romhack list",
                            "Check your connection and try again. Press A to retry, or B to go back.",
                            onClick = { chosen?.let(RaPatches::retry); retries++ },
                            modifier = Modifier.focusRequester(levelFirst))
                    }
                    list.isEmpty() -> item {
                        ToolRow("No hacks for your games yet",
                            "None of the games you own for this console have a hack in the set. Press B to go back.",
                            onClick = { chosen = null }, modifier = Modifier.focusRequester(levelFirst))
                    }
                    else -> list.forEachIndexed { i, match ->
                        item(key = "game_${match.rom.absolutePath}") {
                            ToolRow(match.title, "${match.hacks.size} available",
                                onClick = { lastGame = match.rom.absolutePath; openGame = match; message = null },
                                modifier = Modifier
                                    .focusRequester(gameRows.getOrPut(match.rom.absolutePath) { FocusRequester() })
                                    .then(if (i == 0) Modifier.focusRequester(levelFirst) else Modifier))
                        }
                    }
                }
            }
            // ── Level 3: the game's hacks ──
            else -> {
                item { SectionLabel(game.title) }
                hackLabels.forEachIndexed { i, (hack, label) ->
                    item(key = "hack_${hack.path}") {
                        ToolRow(label.title, label.detail + "  •  A to save it",
                            onClick = { pendingHack = hack },
                            modifier = if (i == 0) Modifier.focusRequester(levelFirst) else Modifier)
                    }
                }
            }
        }
    }
}

/** The suggested file name for a romhack: the base game's name plus the hack's, keeping its extension. */
private fun raSuggestedName(match: RaGameMatch, hackTitle: String): String {
    val inner = RaPatches.baseRomName(match.rom)
    val ext = inner.substringAfterLast('.', "")
    val safe = hackTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    return inner.substringBeforeLast('.') + " ($safe)" + if (ext.isNotEmpty()) ".$ext" else ""
}

/**
 * Applies [hack] to [match]'s game and writes the result to [target], named and placed by the
 * user in the save popup. The original is never touched. With [zip] (saved next to a zipped
 * original) the result is zipped like its neighbours. Returns whether it worked and a line to show.
 */
private suspend fun createRaHack(
    context: android.content.Context, console: String, match: RaGameMatch, hack: RaHack, target: File,
    compress: CompressOne.Format?
): Pair<Boolean, String> =
    withContext(Dispatchers.IO) {
        fun fail(text: String, t: Throwable? = null): Pair<Boolean, String> {
            AppLog.w("Tools", "Romhacks: '${hack.name}' on ${match.rom.name} failed: $text", t)
            return false to text
        }
        val (_, baseBytes) = runCatching { RaPatches.baseRom(match.rom) }.getOrNull()
            ?: return@withContext fail("Couldn't read ${match.rom.name}.")
        val patch = RaPatches.downloadPatch(hack)
            ?: return@withContext fail("Couldn't download the patch. Check your connection.")
        val patched = try {
            RomPatcher.applyBytes("patch.${hack.format}", patch, baseBytes)
        } catch (e: RomPatcher.PatchException) {
            return@withContext fail(e.message ?: "The patch didn't apply.")
        } catch (e: OutOfMemoryError) {
            return@withContext fail("This game is too large to patch on this device.")
        }

        val out = target
        val folder = out.parentFile ?: return@withContext fail("No folder to write to.")
        if (out.exists()) return@withContext fail("${out.name} is already there.")
        runCatching { folder.mkdirs(); out.writeBytes(patched) }
            .getOrElse { return@withContext fail("Couldn't write the hack: ${it.message}", it) }

        // Zipped when saved next to a zipped original, so that folder stays in one form.
        var finalName = out.name
        if (compress != null) {
            val packed = CompressOne.compress(context, out, compress, console)
            if (packed != null) finalName = packed.name
            else return@withContext true to "Created ${out.name} in ${folder.absolutePath}, but couldn't compress it " +
                "to ${compress.label}; it's saved uncompressed."
        }
        AppLog.i("Tools", "Romhacks: applied '${hack.name}' to ${match.rom.name} -> ${folder.absolutePath}/$finalName")
        true to "Created $finalName in ${folder.absolutePath}. In a ROMs folder, refresh your emulator's game " +
            "list to see it; play it in a RetroAchievements-enabled emulator to earn its achievements."
    }

/**
 * "Save the result", for the tools that create a new game (Patch a ROM, RetroAchievements
 * romhacks). Shown before anything is downloaded or written: the name is editable, it goes next
 * to the original or into Downloads on internal storage, and, for a cartridge game, it can be
 * saved as a .zip. An existing file is never overwritten; the user is asked to change the name.
 * Focus opens on Save, so A with nothing changed takes the suggestion.
 *
 * Nothing is remembered unless the user ticks "Remember these choices" (unticked by default, at
 * the user's request): then that tool ([prefKey]) starts with the same place and compress choice
 * next time. Otherwise it starts next to the original, compressed only if [compressDefault].
 *
 * [besideFolder] is null when the original has no folder JoeyOS can write to (it came from cloud
 * storage, say), leaving Downloads as the only choice. [compressFormat] is the best compressed
 * form for this game (CompressOne), or null when there is none, which hides the switch.
 */
@Composable
private fun SaveResultDialog(
    title: String,
    suggestedName: String,
    besideFolder: File?,
    prefKey: String,
    onSave: (target: File, beside: Boolean, compress: Boolean) -> Unit,
    onCancel: () -> Unit,
    compressFormat: CompressOne.Format? = null,
    compressDefault: Boolean = false
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tools", android.content.Context.MODE_PRIVATE) }
    val remembered = remember { prefs.getBoolean("${prefKey}_remember", false) }
    var rememberChoice by remember { mutableStateOf(remembered) }
    var beside by remember { mutableStateOf(!remembered || prefs.getString(prefKey, "beside") != "downloads") }
    var compress by remember {
        mutableStateOf(compressFormat != null &&
            if (remembered) prefs.getBoolean("${prefKey}_compress", compressDefault) else compressDefault)
    }
    var name by remember { mutableStateOf(suggestedName) }
    val ext = suggestedName.substringAfterLast('.', "")

    // The name as written: characters a file name can't hold replaced, the extension kept.
    val fileName = remember(name) {
        var clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (ext.isNotEmpty() && !clean.endsWith(".$ext", ignoreCase = true)) clean += ".$ext"
        clean
    }
    val toBeside = beside && besideFolder != null
    val folder = if (toBeside) besideFolder!! else
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val target = File(folder, fileName)
    val shownName = if (compress && compressFormat != null) target.nameWithoutExtension + "." + compressFormat.extension else fileName
    val problem = when {
        name.isBlank() -> "Enter a name."
        target.exists() || File(folder, shownName).exists() -> "A file with that name is already there. Change the name."
        else -> null
    }
    val saveFocus = remember { FocusRequester() }
    val placeFirst = remember { FocusRequester() }

    JoeyPopup(title = title, onDismiss = onCancel, initialFocus = saveFocus) {
        SectionLabel("Name")
        ControllerTextField(value = name, onValueChange = { name = it }, placeholder = "File name")

        SectionLabel("Save to")
        Row(Modifier.fillMaxWidth().focusRow(placeFirst), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (besideFolder != null) {
                OptionChip("Next to the original", toBeside, { beside = true },
                    Modifier.weight(1f).focusRequester(placeFirst), fontSize = 12.sp)
            }
            OptionChip("Downloads", !toBeside, { beside = false },
                Modifier.weight(1f).then(if (besideFolder == null) Modifier.focusRequester(placeFirst) else Modifier),
                fontSize = 12.sp)
        }
        compressFormat?.let { fmt ->
            ToggleRow("Save compressed", "As ${fmt.label}, the format its emulators read directly. Takes less space.",
                compress, { compress = it })
        }
        Text(
            "${folder.absolutePath}/$shownName" + if (besideFolder == null)
                "\nThe original isn't in a folder JoeyOS can save to, so it goes in Downloads." else "",
            fontSize = 10.sp, fontFamily = JoeyFont, color = TextDim
        )
        ToggleRow("Remember these choices", "Start with this place" + (if (compressFormat != null) " and compress choice" else "") +
            " next time.", rememberChoice, { rememberChoice = it })
        problem?.let { StatusLine(it, MissingColor, bold = true) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JoeyButton("Save", {
                if (problem != null) return@JoeyButton
                prefs.edit().apply {
                    if (rememberChoice) {
                        putBoolean("${prefKey}_remember", true)
                        putString(prefKey, if (toBeside) "beside" else "downloads")
                        putBoolean("${prefKey}_compress", compress)
                    } else {
                        remove("${prefKey}_remember"); remove(prefKey); remove("${prefKey}_compress")
                    }
                }.apply()
                AppLog.i("Tools", "Save: ${target.absolutePath}" + if (compress) " (compressed, ${compressFormat?.label})" else "")
                onSave(target, toBeside, compress && compressFormat != null)
            }, Modifier.weight(1f).focusRequester(saveFocus))
            JoeyButton("Cancel", onCancel, Modifier.weight(1f))
        }
    }
}
