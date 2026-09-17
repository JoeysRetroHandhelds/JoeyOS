package com.joeyos.app.ui.components

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joeyos.app.R
import com.joeyos.app.ui.theme.JoeyFont
import com.joeyos.app.ui.theme.TextFaint
import com.joeyos.app.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The top-right status: time, Wi-Fi, Bluetooth, battery. The glyphs are Google's Material Symbols
 * (Rounded) — the same set JoeyOS uses elsewhere — tinted and sized to sit as one unit beside the
 * time. Wi-Fi and Bluetooth show an on/off state; the battery picks the icon for its level, with
 * the charging variant (bolt built in) when plugged in, and the exact percentage beside it.
 *
 * It reads its own status on a timer and takes no focus: all to look at, none to press.
 */

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
            // Guarded whole: nothing in the status bar is worth crashing the home screen for.
            runCatching {
                value = SystemStatus(
                    time = timeFmt.format(Date()),
                    batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1,
                    charging = readCharging(context),
                    network = readNetwork(connectivity),
                    bluetoothOn = readBluetooth(context.contentResolver),
                )
            }
            delay(10_000)
        }
    }

    // One fixed scale so time, icons and number read as a unit (Chameleon kept these small and
    // fixed; the old clock scaled the time up alone, which looked oversized). A small lift on big
    // screens, capped so it never dominates the corner.
    val timeSp = (LocalConfiguration.current.screenHeightDp * 0.032f).coerceIn(15f, 19f).sp
    val glyph = (timeSp.value * 1.25f).dp
    val numberSp = (timeSp.value * 0.85f).sp

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Wi-Fi: the fan when connected, the slashed icon (muted) when offline.
        JoeyIcon(
            if (status.network == NetworkState.Offline) R.drawable.ic_wifi_off else R.drawable.ic_wifi,
            if (status.network == NetworkState.Offline) MutedColour else NormalColour, glyph,
        )
        // Bluetooth: always shown — the rune when on, the disabled (slashed) icon muted when off.
        JoeyIcon(
            if (status.bluetoothOn) R.drawable.ic_bluetooth else R.drawable.ic_bluetooth_off,
            if (status.bluetoothOn) NormalColour else MutedColour, glyph,
        )
        Battery(status.batteryPercent, status.charging, glyph, numberSp)
        Text(status.time, fontSize = timeSp, fontWeight = FontWeight.SemiBold, color = NormalColour)
    }
}

/**
 * The Material Symbols battery for the current level (its charging variant while plugged in, the
 * bolt built into the icon), plus the exact percentage beside it. Green while charging, red at or
 * below 20%.
 */
@Composable
private fun Battery(percent: Int, charging: Boolean, glyph: androidx.compose.ui.unit.Dp, numberSp: androidx.compose.ui.unit.TextUnit) {
    val clamped = percent.coerceIn(0, 100)
    val colour = when {
        charging -> ChargingColour
        clamped <= LowPercent -> LowColour
        else -> NormalColour
    }
    val icon = if (charging) chargingIcon(clamped) else levelIcon(clamped)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        // Upright, like the phone status bar's battery (Material Symbols draws it portrait).
        JoeyIcon(icon, colour, glyph)
        if (percent in 0..100) {
            Text("$clamped", fontSize = numberSp, fontFamily = JoeyFont, fontWeight = FontWeight.Bold, color = colour)
        }
    }
}

private fun levelIcon(p: Int): Int = when {
    p >= 95 -> R.drawable.ic_battery_full
    p >= 80 -> R.drawable.ic_battery_6
    p >= 65 -> R.drawable.ic_battery_5
    p >= 50 -> R.drawable.ic_battery_4
    p >= 35 -> R.drawable.ic_battery_3
    p >= 20 -> R.drawable.ic_battery_2
    p >= 10 -> R.drawable.ic_battery_1
    p > 5 -> R.drawable.ic_battery_0
    else -> R.drawable.ic_battery_alert
}

private fun chargingIcon(p: Int): Int = when {
    p >= 95 -> R.drawable.ic_battery_charging_full
    p >= 85 -> R.drawable.ic_battery_charging_90
    p >= 70 -> R.drawable.ic_battery_charging_80
    p >= 55 -> R.drawable.ic_battery_charging_60
    p >= 40 -> R.drawable.ic_battery_charging_50
    p >= 25 -> R.drawable.ic_battery_charging_30
    else -> R.drawable.ic_battery_charging_20
}

/**
 * Whether the battery is charging, from the sticky ACTION_BATTERY_CHANGED broadcast (no receiver
 * kept, just its last value). BatteryManager.isCharging read false while plugged in on the Thor,
 * so this uses the same STATUS the system status bar does.
 */
private fun readCharging(context: android.content.Context): Boolean = runCatching {
    val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
}.getOrDefault(false)

// Reading the network needs ACCESS_NETWORK_STATE (declared in the manifest); guarded so a missing
// permission can never take the home screen down.
private fun readNetwork(connectivity: ConnectivityManager?): NetworkState = runCatching {
    val caps = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return NetworkState.Offline
    when {
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkState.Offline
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.Wifi
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.Cellular
        else -> NetworkState.Offline
    }
}.getOrDefault(NetworkState.Offline)

/**
 * Whether Bluetooth is on, without a Bluetooth permission. BluetoothAdapter.isEnabled needs
 * BLUETOOTH_CONNECT, a runtime prompt; this global is what the system itself flips, needs nothing,
 * and answers the only question asked.
 */
private fun readBluetooth(resolver: android.content.ContentResolver?): Boolean =
    runCatching { android.provider.Settings.Global.getInt(resolver, "bluetooth_on", 0) == 1 }.getOrDefault(false)
