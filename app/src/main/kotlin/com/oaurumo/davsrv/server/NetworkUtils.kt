package com.oaurumo.davsrv.server

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** Best-effort LAN IPv4 (Wi-Fi/Ethernet preferred). */
    fun localIpv4(): String? {
        val candidates = ArrayList<Pair<Int, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nic in interfaces) {
                if (!nic.isUp || nic.isLoopback) continue
                val name = nic.name ?: ""
                val priority = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("ap") || name.startsWith("swlan") -> 2
                    name.startsWith("rmnet") || name.startsWith("ccmni") -> 9
                    else -> 5
                }
                for (address in nic.inetAddresses) {
                    if (address is Inet4Address && address.isSiteLocalAddress && !address.isLoopbackAddress) {
                        val host = address.hostAddress ?: continue
                        if (!host.startsWith("169.254.")) candidates.add(priority to host)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return candidates.minByOrNull { it.first }?.second
    }
}
