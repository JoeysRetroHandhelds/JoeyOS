package com.joeyos.app.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.joeyos.app.data.InstalledApp
import com.joeyos.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppDrawer(
    installedApps: List<InstalledApp>,
    onLaunch: (String) -> Unit,
    onDismiss: () -> Unit,
    selectedIndex: Int = -1,
    onFilteredCountChange: (Int) -> Unit = {},
    onSelectedPackageChange: (String?) -> Unit = {},
    onColumnCountChange: (Int) -> Unit = {}
) {
    // Match GridCells.Adaptive(96.dp) with 12dp padding each side + 4dp spacing
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val cols = remember(screenWidthDp) { ((screenWidthDp - 24) / 100).coerceAtLeast(1) }
    LaunchedEffect(cols) { onColumnCountChange(cols) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, installedApps) {
        if (query.isBlank()) installedApps
        else installedApps.filter { it.label.contains(query, ignoreCase = true) }
    }
    LaunchedEffect(filtered.size) { onFilteredCountChange(filtered.size) }
    LaunchedEffect(selectedIndex, filtered.size) {
        onSelectedPackageChange(filtered.getOrNull(selectedIndex)?.packageName)
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0 && filtered.isNotEmpty())
            gridState.animateScrollToItem(selectedIndex.coerceIn(0, filtered.lastIndex))
    }

    var contextApp by remember { mutableStateOf<InstalledApp?>(null) }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SheetBg)
            .systemBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("All Apps", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.07f))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("✕", color = TextFaint, fontSize = 16.sp)
                    }
                }

                // Search bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔍 ", fontSize = 13.sp, color = TextFaint)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text("Search apps…", fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace, color = TextFaint)
                            inner()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // App grid
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    state   = gridState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val idx = filtered.indexOf(app)
                        AppGridItem(
                            app         = app,
                            isSelected  = idx == selectedIndex,
                            onClick     = { onLaunch(app.packageName) },
                            onLongClick = { contextApp = app }
                        )
                    }
                }
            }
        }

    // Long-press context menu
    contextApp?.let { app ->
        Dialog(
            onDismissRequest = { contextApp = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { contextApp = null },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF1A1A2E))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                        .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}
                ) {
                    // App name header
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = app.label,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    // App Info
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                contextApp = null
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", app.packageName, null)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text("App Info", fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                    }

                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppGridItem(app: InstalledApp, isSelected: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(app.packageName)
                val w = drawable.intrinsicWidth.coerceIn(1, 256)
                val h = drawable.intrinsicHeight.coerceIn(1, 256)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
                bmp.asImageBitmap()
            } catch (e: Exception) { null }
        }
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (isSelected) Modifier.background(Color.White.copy(alpha = 0.12f)) else Modifier)
            .border(1.dp, if (isSelected) Color.White.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon!!,
                contentDescription = app.label,
                modifier = Modifier.size(52.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Text(app.label.take(2).uppercase(), fontSize = 16.sp, color = TextDim,
                    fontFamily = FontFamily.Monospace)
            }
        }
        Text(
            app.label,
            fontSize = 10.sp,
            color = TextDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 13.sp
        )
    }
}
