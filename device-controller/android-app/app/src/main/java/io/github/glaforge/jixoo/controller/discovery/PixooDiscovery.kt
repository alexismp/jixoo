package io.github.glaforge.jixoo.controller.discovery

import io.github.glaforge.jixoo.controller.network.PixooHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

data class DiscoveredPixooDevice(
    val ipAddress: String,
    val macAddress: String = "",
    val deviceName: String = "Pixoo 64",
    val deviceId: Int = 0
)

/**
 * Multi-strategy local network discovery matching `pixoo-cli discover` (`PixooDevice.java`)
 * and `discover_device()` in `simple-control.sh`:
 * 1. Multi-interface UDP broadcast on ports 5000, 7000, 3333
 * 2. ARP table inspection (`/proc/net/arp`) + HTTP probe
 * 3. Local IPv4 /24 subnet HTTP sweep fallback
 */
object PixooDiscovery {

    suspend fun discoverDevices(
        port: Int = 80,
        timeoutMs: Int = 3000
    ): List<DiscoveredPixooDevice> = withContext(Dispatchers.IO) {
        val discovered = linkedMapOf<String, DiscoveredPixooDevice>()

        // Strategy 1: UDP Broadcast
        val udpDevices = discoverUdp(minOf(1000, timeoutMs))
        for (d in udpDevices) {
            discovered[d.ipAddress] = d
        }
        if (discovered.isNotEmpty()) {
            return@withContext discovered.values.toList()
        }

        // Strategy 2: ARP Table + HTTP Probe
        val arpCandidates = readArpCandidates()
        if (arpCandidates.isNotEmpty()) {
            val probed = probeCandidateIps(arpCandidates, port)
            for (d in probed) {
                discovered[d.ipAddress] = d
            }
        }
        if (discovered.isNotEmpty()) {
            return@withContext discovered.values.toList()
        }

        // Strategy 3: Local Subnet HTTP Sweep
        val subnetIps = getLocalSubnetIps()
        if (subnetIps.isNotEmpty()) {
            val candidateMap = subnetIps.associateWith { "" }
            val swept = probeCandidateIps(candidateMap, port)
            for (d in swept) {
                discovered[d.ipAddress] = d
            }
        }

        discovered.values.toList()
    }

    private fun discoverUdp(timeoutMs: Int): List<DiscoveredPixooDevice> {
        val found = mutableListOf<DiscoveredPixooDevice>()
        val ports = intArrayOf(5000, 7000, 3333)
        val broadcastAddresses = getBroadcastAddresses()
        val perPortTimeout = maxOf(100, timeoutMs / ports.size)
        val sendData = "DISCOVER".toByteArray(StandardCharsets.UTF_8)

        for (udpPort in ports) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = perPortTimeout

                    for (bAddr in broadcastAddresses) {
                        runCatching {
                            val packet = DatagramPacket(sendData, sendData.size, bAddr, udpPort)
                            socket.send(packet)
                        }
                    }

                    val buf = ByteArray(2048)
                    val endTime = System.currentTimeMillis() + perPortTimeout
                    while (System.currentTimeMillis() < endTime) {
                        try {
                            val receivePacket = DatagramPacket(buf, buf.size)
                            socket.receive(receivePacket)
                            val responseStr = String(
                                receivePacket.data,
                                0,
                                receivePacket.length,
                                StandardCharsets.UTF_8
                            ).trim()
                            val senderIp = receivePacket.address.hostAddress ?: continue
                            val device = parseDiscoveryResponse(senderIp, responseStr)
                            if (found.none { it.ipAddress == senderIp }) {
                                found.add(device)
                            }
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return found
    }

    private fun parseDiscoveryResponse(ip: String, jsonResponse: String): DiscoveredPixooDevice {
        return try {
            if (jsonResponse.startsWith("{")) {
                val root = JSONObject(jsonResponse)
                val mac = root.optString("DeviceMac", "")
                val name = root.optString("DeviceName", "Pixoo 64")
                val id = root.optInt("DeviceId", 0)
                DiscoveredPixooDevice(ip, mac, name, id)
            } else {
                DiscoveredPixooDevice(ip)
            }
        } catch (_: Exception) {
            DiscoveredPixooDevice(ip)
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = linkedSetOf<InetAddress>()
        runCatching {
            addresses.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return addresses.toList()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isUp && !ni.isLoopback) {
                    for (ia in ni.interfaceAddresses) {
                        ia.broadcast?.let { addresses.add(it) }
                    }
                }
            }
        }
        return addresses.toList()
    }

    private fun readArpCandidates(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        runCatching {
            val arpFile = File("/proc/net/arp")
            if (arpFile.canRead()) {
                arpFile.useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 4) {
                            val ip = parts[0]
                            val mac = parts[3]
                            if (mac != "00:00:00:00:00:00" && !ip.endsWith(".255") && !ip.endsWith(".0")) {
                                map[ip] = mac
                            }
                        }
                    }
                }
            }
        }
        return map
    }

    private fun getLocalSubnetIps(): List<String> {
        val ips = mutableListOf<String>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ips
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isUp && !ni.isLoopback) {
                    for (ia in ni.interfaceAddresses) {
                        val addr = ia.address
                        if (addr is Inet4Address) {
                            val hostAddress = addr.hostAddress ?: continue
                            val lastDot = hostAddress.lastIndexOf('.')
                            if (lastDot > 0) {
                                val prefix = hostAddress.substring(0, lastDot + 1)
                                for (i in 1..254) {
                                    val candidate = "$prefix$i"
                                    if (candidate != hostAddress) {
                                        ips.add(candidate)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return ips.distinct()
    }

    private suspend fun probeCandidateIps(
        candidates: Map<String, String>,
        port: Int
    ): List<DiscoveredPixooDevice> = coroutineScope {
        val semaphore = Semaphore(64)

        candidates.entries.map { (ip, mac) ->
            async {
                semaphore.withPermit {
                    if (PixooHttpClient.checkDevice(ip, port, timeoutMs = 450)) {
                        return@withPermit DiscoveredPixooDevice(
                            ipAddress = ip,
                            macAddress = mac,
                            deviceName = "Pixoo 64",
                            deviceId = 0
                        )
                    }
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }
}
