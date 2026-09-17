package com.joeyos.app.ui.components

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.ui.theme.Accent
import com.joeyos.app.ui.theme.JoeyFont
import com.joeyos.app.ui.theme.TextFaint
import com.joeyos.app.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The top-right status: time, Wi-Fi, Bluetooth, battery. Ported from Chameleon's StatusPill and
 * redrawn in JoeyOS's look (Inter, the sky-blue accent). Everything is drawn in Canvas rather than
 * from icon files, so the glyphs tint and scale exactly with the time beside them.
 *
 * It reads its own status on a timer, so nothing here takes focus — it's all to look at, none to
 * press, which also keeps the D-pad out of a corner it couldn't usefully leave.
 */

/** What the connectivity glyph shows. */
private enum class NetworkState { Wifi, Cellular, Offline }

private data class SystemStatus(
    val time: String,
    val batteryPercent: Int,
    val charging: Boolean,
    val network: NetworkState,
    val bluetoothOn: Boolean,
)

private val NormalColour = TextPrimary
private val MutedColour = TextFaint
private val ChargingColour = Color(0xFF97C459)
private val LowColour = Color(0xFFE24B4A)
private const val LowPercent = 20

@Composable
fun StatusCluster(use24h: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val timeFmt = remember(use24h) { SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()) }

    val status by produceState(
        initialValue = SystemStatus(timeFmt.format(Date()), -1, false, NetworkState.Offline, false),
        timeFmt
    ) {
        val battery = context.getSystemService(BatteryManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        while (true) {
            value = SystemStatus(
                time = timeFmt.format(Date()),
                batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1,
                // isCharging, not the plugged-in extra: a device can be attached to a source that
                // isn't charging it, and the question here is whether the number is going up.
                charging = battery?.isCharging == true,
                network = readNetwork(connectivity),
                bluetoothOn = readBluetooth(context.contentResolver),
            )
            delay(10_000)
        }
    }

    // Sizes track the screen, as the old clock did.
    val screenH = LocalConfiguration.current.screenHeightDp
    val timeSp = (screenH * 0.085f).coerceIn(18f, 36f).sp
    val glyph = (screenH * 0.05f).coerceIn(13f, 22f).dp
    val numberSp = (screenH * 0.036f).coerceIn(10f, 15f).sp

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NetworkGlyph(status.network, glyph)
        if (status.bluetoothOn) BluetoothGlyph(glyph)
        Battery(status.batteryPercent, status.charging, glyph, numberSp)
        Text(status.time, fontSize = timeSp, fontWeight = FontWeight.Bold, color = NormalColour)
    }
}

/**
 * Always drawn, including offline: a Wi-Fi glyph that vanishes with the connection says nothing at
 * the moment it has something to say. Offline is a slashed, muted fan.
 */
@Composable
private fun NetworkGlyph(state: NetworkState, dim: androidx.compose.ui.unit.Dp) {
    val colour = if (state == NetworkState.Offline) MutedColour else NormalColour
    Canvas(Modifier.size(dim)) {
        when (state) {
            NetworkState.Cellular -> drawCellular(colour)
            else -> {
                drawWifi(colour)
                if (state == NetworkState.Offline) drawSlash(colour)
            }
        }
    }
}

/** A dot and two arcs opening upward — the ordinary Wi-Fi fan. */
private fun DrawScope.drawWifi(colour: Color) {
    val w = size.width
    val cx = w / 2f
    val baseY = size.height * 0.82f
    val stroke = w * 0.09f
    drawCircle(colour, radius = w * 0.055f, center = Offset(cx, baseY))
    // Arcs centred on straight-up (270° in Compose's y-down clockwise angles).
    for (r in listOf(0.26f, 0.42f)) {
        val radius = w * r
        drawArc(
            color = colour, startAngle = 210f, sweepAngle = 120f, useCenter = false,
            topLeft = Offset(cx - radius, baseY - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** Three ascending bars, for a mobile-data connection. */
private fun DrawScope.drawCellular(colour: Color) {
    val w = size.width
    val barW = w * 0.2f
    val gap = w * 0.1f
    val bottom = size.height * 0.85f
    listOf(0.35f, 0.6f, 0.85f).forEachIndexed { i, h ->
        val x = i * (barW + gap) + w * 0.08f
        val top = bottom - size.height * h
        drawRoundRect(
            color = colour, topLeft = Offset(x, top), size = Size(barW, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.3f),
        )
    }
}

/** A corner-to-corner slash over the Wi-Fi fan when there's no connection. */
private fun DrawScope.drawSlash(colour: Color) {
    drawLine(
        colour, Offset(size.width * 0.15f, size.height * 0.15f),
        Offset(size.width * 0.85f, size.height * 0.85f),
        strokeWidth = size.width * 0.09f, cap = StrokeCap.Round,
    )
}

/** The Bluetooth rune, one continuous stroke. Shown only while the radio is on. */
@Composable
private fun BluetoothGlyph(dim: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(dim)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.32f)
            lineTo(w * 0.72f, h * 0.68f)
            lineTo(w * 0.50f, h * 0.85f)
            lineTo(w * 0.50f, h * 0.15f)
            lineTo(w * 0.72f, h * 0.32f)
            lineTo(w * 0.28f, h * 0.68f)
        }
        drawPath(path, NormalColour, style = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/**
 * The battery: outline, a cap, the level filled behind the number so the shape reads at a glance,
 * and a bolt while charging. Blue normally, green charging, red at or below 20%.
 */
@Composable
private fun Battery(percent: Int, charging: Boolean, barH: androidx.compose.ui.unit.Dp, numberSp: androidx.compose.ui.unit.TextUnit) {
    val clamped = percent.coerceIn(0, 100)
    val full = charging && clamped >= 100
    val colour = when {
        charging || full -> ChargingColour
        clamped <= LowPercent -> LowColour
        else -> Accent
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.size(width = barH * 2f, height = barH)) {
            val capWidth = size.width * 0.08f
            val bodyWidth = size.width - capWidth
            val corner = size.height * 0.28f
            drawRoundRect(
                color = colour, size = Size(bodyWidth, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner),
                style = Stroke(width = size.height * 0.1f),
            )
            drawRoundRect(
                color = colour, topLeft = Offset(bodyWidth + capWidth * 0.2f, size.height * 0.3f),
                size = Size(capWidth * 0.8f, size.height * 0.4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner * 0.5f),
            )
            val inset = size.height * 0.18f
            val fillWidth = (bodyWidth - inset * 2) * (clamped / 100f)
            if (fillWidth > 0f) drawRoundRect(
                color = colour, topLeft = Offset(inset, inset),
                size = Size(fillWidth, size.height - inset * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner * 0.6f),
            )
            if (charging && !full) drawBolt(NormalColour)
        }
        if (percent in 0..100) {
            Text("$clamped", fontSize = numberSp, fontFamily = JoeyFont, fontWeight = FontWeight.Bold, color = NormalColour)
        }
    }
}

/** The charging bolt, centred in the glyph. */
private fun DrawScope.drawBolt(colour: Color) {
    val h = size.height
    val x = size.width * 0.40f
    val path = Path().apply {
        moveTo(x + h * 0.14f, h * 0.14f)
        lineTo(x - h * 0.12f, h * 0.56f)
        lineTo(x + h * 0.06f, h * 0.56f)
        lineTo(x - h * 0.02f, h * 0.86f)
        lineTo(x + h * 0.26f, h * 0.44f)
        lineTo(x + h * 0.08f, h * 0.44f)
        close()
    }
    drawPath(path, colour)
}

private fun readNetwork(connectivity: ConnectivityManager?): NetworkState {
    val caps = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return NetworkState.Offline
    return when {
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkState.Offline
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.Wifi
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.Cellular
        else -> NetworkState.Offline
    }
}

/**
 * Whether Bluetooth is on, without a Bluetooth permission. BluetoothAdapter.isEnabled needs
 * BLUETOOTH_CONNECT, a runtime prompt; asking for access to someone's devices so a launcher can
 * draw a small icon isn't a trade worth offering. This global is what the system itself flips,
 * needs nothing, and answers the only question asked.
 */
private fun readBluetooth(resolver: android.content.ContentResolver?): Boolean =
    runCatching { android.provider.Settings.Global.getInt(resolver, "bluetooth_on", 0) == 1 }.getOrDefault(false)
