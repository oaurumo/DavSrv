package com.oaurumo.davsrv.server

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** One LAN interface candidate: NIC name + IPv4, ordered by preference. */
    data class Nic(val name: String, val ip: String, val priority: Int)

    /** All usable site-local IPv4s with their NIC, best interface first. */
    fun allIps(): List<Nic> {
        val candidates = ArrayList<Nic>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return candidates
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
                        if (!host.startsWith("169.254.")) candidates.add(Nic(name, host, priority))
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return candidates.sortedBy { it.priority }
    }

    /** Best-effort LAN IPv4 (Wi-Fi/Ethernet preferred). */
    fun localIpv4(): String? = allIps().firstOrNull()?.ip
}
