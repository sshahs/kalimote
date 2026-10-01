package dev.kalimote.atvremote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object WakeOnLan {
    /** Returns "aa:bb:cc:dd:ee:ff", or null if [mac] is not a MAC address. */
    fun normalize(mac: String?): String? {
        val hex = mac.orEmpty().filter { it.isLetterOrDigit() }.lowercase()
        if (hex.length != 12 || hex.any { it !in "0123456789abcdef" }) return null
        return hex.chunked(2).joinToString(":")
    }

    /** 6 x 0xFF followed by the MAC repeated 16 times. */
    fun magicPacket(mac: String): ByteArray {
        val norm = normalize(mac) ?: throw IllegalArgumentException("Invalid MAC address")
        val bytes = norm.split(":").map { it.toInt(16).toByte() }.toByteArray()
        return ByteArray(6) { 0xff.toByte() } + ByteArray(16 * 6) { bytes[it % 6] }
    }

    /** Sends the magic packet to every IPv4 broadcast address. Blocking. */
    fun wake(mac: String) {
        val packet = magicPacket(mac)
        val targets = LinkedHashSet<InetAddress>()
        targets += InetAddress.getByName("255.255.255.255")
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (ia in nic.interfaceAddresses) {
                    if (ia.address is Inet4Address) ia.broadcast?.let { targets += it }
                }
            }
        }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (address in targets) {
                for (port in intArrayOf(9, 7)) {
                    runCatching { socket.send(DatagramPacket(packet, packet.size, address, port)) }
                }
            }
        }
    }
}
