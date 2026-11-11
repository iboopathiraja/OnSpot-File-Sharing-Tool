package com.beyondzero.onspot.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "Wi-Fi mode" discovery. Finds every OnSpot device on the connected Wi-Fi, even when the
 * router blocks mDNS. Shares a tiny UDP protocol with the laptop app:
 *   {"app":"onspot","v":1,"t":"probe"|"announce"|"bye","id","name","avatar","port"}
 * We probe every host of the subnet; receivers answer with a unicast "announce".
 */
class LanScanner(private val context: Context) {
    data class Peer(val id: String, val name: String, val host: String, val port: Int, val avatarIndex: Int)

    private class Self(val name: String, val avatarIndex: Int, val tcpPort: Int)

    private val instanceId = UUID.randomUUID().toString()
    private val peers = ConcurrentHashMap<String, Pair<Peer, Long>>()
    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var self: Self? = null

    /** True if a peer with this (un-prefixed) device name is currently alive on the LAN. */
    fun knows(deviceName: String): Boolean = peers.values.any { it.first.name == deviceName }

    @Synchronized
    fun start(
        name: String,
        avatarIndex: Int,
        tcpPort: Int,
        onPeer: (Peer) -> Unit,
        onGone: (Peer) -> Unit
    ) {
        stop()
        self = Self(name, avatarIndex, tcpPort)

        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(LAN_PORT))
            }
        } catch (e: Exception) {
            // Port taken: we can still probe out and receive the unicast replies.
            try { DatagramSocket().apply { broadcast = true } } catch (e2: Exception) { return }
        }
        socket = s

        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("onspot-lan").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock unavailable: ${e.message}")
        }

        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc

        sc.launch {
            val buf = ByteArray(1024)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                } catch (e: Exception) {
                    break // socket closed by stop()
                }
                try {
                    handle(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address, packet.port, s, onPeer, onGone)
                } catch (e: Exception) {
                    Log.w(TAG, "bad packet: ${e.message}")
                }
            }
        }
        sc.launch {
            while (isActive) {
                scan(s)
                delay(SCAN_INTERVAL_MS)
            }
        }
        sc.launch {
            while (isActive) {
                delay(5_000)
                val now = System.currentTimeMillis()
                for ((id, entry) in peers) {
                    if (now - entry.second > EXPIRE_MS && peers.remove(id) != null) onGone(entry.first)
                }
            }
        }
    }

    /** Probe again right away (used by manual refresh). */
    fun rescan() {
        val s = socket ?: return
        scope?.launch { scan(s) }
    }

    @Synchronized
    fun stop() {
        val s = socket
        val sc = scope
        val known = peers.values.map { it.first }
        val me = self
        socket = null
        scope = null
        peers.clear()
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        multicastLock = null
        sc?.cancel()
        if (s != null) {
            // Say goodbye (off the main thread), then close.
            Thread {
                try {
                    if (me != null) {
                        val bye = packetBytes("bye", me)
                        known.forEach { p ->
                            try { s.send(DatagramPacket(bye, bye.size, InetAddress.getByName(p.host), LAN_PORT)) } catch (e: Exception) { }
                        }
                    }
                } finally {
                    try { s.close() } catch (e: Exception) { }
                }
            }.start()
        }
    }

    private fun packetBytes(type: String, me: Self): ByteArray =
        JSONObject()
            .put("app", "onspot").put("v", 1).put("t", type).put("id", instanceId)
            .put("name", me.name).put("avatar", me.avatarIndex).put("port", me.tcpPort)
            .toString().toByteArray(Charsets.UTF_8)

    private fun handle(
        text: String,
        from: InetAddress,
        fromPort: Int,
        s: DatagramSocket,
        onPeer: (Peer) -> Unit,
        onGone: (Peer) -> Unit
    ) {
        val m = JSONObject(text)
        if (m.optString("app") != "onspot") return
        val id = m.optString("id")
        if (id.isEmpty() || id == instanceId) return
        val type = m.optString("t")
        if (type == "bye") {
            peers.remove(id)?.let { onGone(it.first) }
            return
        }
        if (type != "probe" && type != "announce") return
        val port = m.optInt("port", -1)
        val name = m.optString("name").removePrefix("OnSpot_").take(60)
        if (port !in 1..65535 || name.isEmpty()) return

        val peer = Peer(id, name, from.hostAddress ?: return, port, m.optInt("avatar", 1))
        peers[id] = peer to System.currentTimeMillis()
        onPeer(peer)

        if (type == "probe") {
            val me = self ?: return
            val reply = packetBytes("announce", me)
            s.send(DatagramPacket(reply, reply.size, from, fromPort))
        }
    }

    /** The connected Wi-Fi network's IPv4 address and prefix length, if any. */
    private fun wifiAddress(): Pair<Inet4Address, Int>? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val link = cm.getLinkProperties(network) ?: continue
            for (la in link.linkAddresses) {
                val a = la.address
                if (a is Inet4Address && !a.isLoopbackAddress) return a to la.prefixLength
            }
        }
        return null
    }

    private fun scan(s: DatagramSocket) {
        val me = self ?: return
        val (address, prefixLen) = wifiAddress() ?: return
        var prefix = prefixLen
        if (prefix < 22) prefix = 24 // do not sweep huge networks
        if (prefix >= 31) return

        val ip = address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val net = ip and mask
        val broadcast = net or (mask.inv() and 0xFFFFFFFFL)
        val data = packetBytes("probe", me)

        fun send(target: Long) {
            val bytes = ByteArray(4) { i -> ((target shr (24 - 8 * i)) and 0xFF).toByte() }
            try {
                s.send(DatagramPacket(data, data.size, InetAddress.getByAddress(bytes), LAN_PORT))
            } catch (e: Exception) { }
        }

        var count = 0
        var host = net + 1
        while (host < broadcast) {
            if (host != ip) send(host)
            host++
            if (++count % 64 == 0) Thread.sleep(2) // do not flood the Wi-Fi
        }
        send(broadcast)
    }

    companion object {
        const val LAN_PORT = 45454
        private const val SCAN_INTERVAL_MS = 3_000L
        private const val EXPIRE_MS = 26_000L
        private const val TAG = "LanScanner"
    }
}
