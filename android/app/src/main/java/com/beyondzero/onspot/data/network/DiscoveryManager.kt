package com.beyondzero.onspot.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetAddress

class DiscoveryManager(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_onspot._tcp."
    private val settingsManager = com.beyondzero.onspot.data.settings.SettingsManager(context)
    private val serviceName: String
        get() = "OnSpot_${settingsManager.deviceName}"
    
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    data class DiscoveredDevice(
        val name: String,
        val host: InetAddress,
        val port: Int,
        val avatarIndex: Int = 1
    )

    private val lanScanner = LanScanner(context)
    private var tcpPort = -1
    private var active = false

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // NsdManager.resolveService() throws if called again while a previous resolve for this
    // manager is still pending — a well-known, undocumented Android limitation. When two
    // devices announce close together (very common right after joining a Wi-Fi/hotspot), the
    // second resolveService() call used to throw, get silently caught, and that device would
    // just never appear — it had no way to be retried until it happened to re-announce. This
    // queues resolves and runs them one at a time instead of dropping the overlapping ones.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun enqueueResolve(service: NsdServiceInfo) {
        synchronized(resolveQueue) { resolveQueue.addLast(service) }
        resolveNext()
    }

    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            if (resolving || resolveQueue.isEmpty()) return
            resolving = true
            resolveQueue.removeFirst()
        }
        try {
            nsdManager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e("DiscoveryManager", "Resolve failed: $errorCode")
                    resolving = false
                    resolveNext()
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    Log.d("DiscoveryManager", "Resolve Succeeded. $serviceInfo")
                    val avatarIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        serviceInfo.attributes["avatarIndex"]?.decodeToString()?.toIntOrNull() ?: 1
                    } else 1

                    val device = DiscoveredDevice(
                        name = serviceInfo.serviceName,
                        host = serviceInfo.host,
                        port = serviceInfo.port,
                        avatarIndex = avatarIdx
                    )
                    _discoveredDevices.update { list ->
                        val existing = list.find { it.name == device.name }
                        if (existing != null && existing.host is java.net.Inet4Address && device.host !is java.net.Inet4Address) {
                            return@update list
                        }
                        // The LAN scan hears every change straight from the device, while a
                        // resolve can return a cached TXT record with the old picture.
                        if (existing != null && lanScanner.knows(device.name.removePrefix("OnSpot_"))) {
                            return@update list
                        }
                        val mutableList = list.toMutableList()
                        mutableList.removeAll { it.name == device.name }
                        mutableList.add(device)
                        mutableList
                    }
                    resolving = false
                    resolveNext()
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
            resolving = false
            resolveNext()
        }
    }

    /** Wi-Fi mode adds a subnet sweep on top of mDNS; hotspot mode is mDNS only. */
    private fun startLanIfNeeded() {
        if (!active || tcpPort <= 0) return
        if (settingsManager.connectionMode != com.beyondzero.onspot.data.settings.SettingsManager.MODE_WIFI) {
            lanScanner.stop()
            return
        }
        lanScanner.start(
            name = settingsManager.deviceName,
            avatarIndex = settingsManager.avatarIndex,
            tcpPort = tcpPort,
            onPeer = { peer -> onLanPeer(peer) },
            onGone = { peer -> onLanPeerGone(peer) }
        )
    }

    private fun onLanPeer(peer: LanScanner.Peer) {
        val name = "OnSpot_${peer.name}"
        if (name == serviceName) return
        val host = try { InetAddress.getByName(peer.host) } catch (e: Exception) { return }
        val device = DiscoveredDevice(name, host, peer.port, peer.avatarIndex)
        _discoveredDevices.update { list ->
            if (list.contains(device)) list
            else list.filterNot { it.name == name } + device
        }
    }

    private fun onLanPeerGone(peer: LanScanner.Peer) {
        val name = "OnSpot_${peer.name}"
        _discoveredDevices.update { list -> list.filterNot { it.name == name } }
    }

    fun clearDevices() {
        _discoveredDevices.value = emptyList()
    }

    /**
     * Probes again right away instead of waiting for the next scheduled scan. In Wi-Fi mode
     * that is a fresh LAN sweep; in hotspot mode there is no LAN scanner at all, and NSD has no
     * "query now" API, so the only way to force a fresh mDNS query burst is to restart the
     * discovery listener — without this, hotspot mode never actually sped up from these fixes.
     */
    fun rescan() {
        lanScanner.rescan()
        if (discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) { e.printStackTrace() }
            discoveryListener = null
            startDiscovery()
        }
    }

    fun registerService(port: Int) {
        tcpPort = port
        if (registrationListener != null) {
            startLanIfNeeded()
            return // Prevent multiple registrations
        }
        
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = this@DiscoveryManager.serviceName
            serviceType = this@DiscoveryManager.serviceType
            setPort(port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute("avatarIndex", settingsManager.avatarIndex.toString())
            }
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d("DiscoveryManager", "Service registered: ${NsdServiceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("DiscoveryManager", "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        startLanIfNeeded()
    }

    fun startDiscovery() {
        if (discoveryListener != null) return // Prevent multiple discoveries
        active = true
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("DiscoveryManager", "Service discovery started")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("DiscoveryManager", "Service discovery success: $service")
                if (service.serviceType == serviceType) {
                    if (service.serviceName == serviceName) {
                        Log.d("DiscoveryManager", "Same machine: $serviceName")
                    } else {
                        enqueueResolve(service)
                    }
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e("DiscoveryManager", "service lost: $service")
                if (lanScanner.knows(service.serviceName.removePrefix("OnSpot_"))) return
                _discoveredDevices.update { list ->
                    list.filterNot { it.name == service.serviceName }
                }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsdManager.stopServiceDiscovery(this)
            }
        }
        
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        active = false
        synchronized(resolveQueue) { resolveQueue.clear() }
        resolving = false
        lanScanner.stop()
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
            registrationListener = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
            discoveryListener = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
