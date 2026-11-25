package org.droidpilot

import android.content.*
import android.net.*
import android.os.*
import java.net.NetworkInterface

fun networkDiagnostics(context:Context):String {
    val manager=context.getSystemService(ConnectivityManager::class.java)
    val networks=manager.allNetworks.joinToString("\n") { n ->
        val caps=manager.getNetworkCapabilities(n)
        val types=listOf(NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",NetworkCapabilities.TRANSPORT_CELLULAR to "Cellular",NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",NetworkCapabilities.TRANSPORT_VPN to "VPN")
            .filter { caps?.hasTransport(it.first)==true }.joinToString("+") { it.second }
        "$types ${manager.getLinkProperties(n)?.linkAddresses?.joinToString() ?: ""}"
    }
    val interfaces=runCatching { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.joinToString("\n") { "${it.name}: ${it.inetAddresses.toList().joinToString { a->a.hostAddress ?: "" }}" } }.getOrDefault("Interfaces unavailable")
    return "$networks\n$interfaces"
}
fun phoneDiagnostics(context:Context):String {
    val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1) ?: -1
    val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,100) ?: 100
    val plugged=battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0
    val power=context.getSystemService(PowerManager::class.java)
    val thermal=if(Build.VERSION.SDK_INT>=29) power.currentThermalStatus.toString() else "Unavailable (API 28)"
    return "Battery ${if(level>=0 && scale>0) "${level*100/scale}%" else "unavailable"} • ${if(plugged) "Charging" else "On battery"}\nThermal status: $thermal\nBattery optimization exemption: ${power.isIgnoringBatteryOptimizations(context.packageName)}"
}
