package com.beyondzero.onspot.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.beyondzero.onspot.R
import com.beyondzero.onspot.data.network.DiscoveryManager
import com.beyondzero.onspot.data.network.NetworkState
import com.beyondzero.onspot.data.network.NetworkStatusObserver
import com.beyondzero.onspot.data.settings.SettingsManager
import com.beyondzero.onspot.data.transfer.TransferManager
import com.beyondzero.onspot.domain.model.ClipboardState
import com.beyondzero.onspot.domain.model.Device
import com.beyondzero.onspot.domain.model.TransferRecord
import com.beyondzero.onspot.domain.model.TransferState
import com.beyondzero.onspot.domain.model.TransferType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns discovery, the transfer server, the send queue, history and clipboard sync for the whole
 * app process. A process-wide singleton so closing the UI never stops a transfer; the foreground
 * service keeps the process alive.
 */
@OptIn(FlowPreview::class)
class FlowshareCore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val discoveryManager = DiscoveryManager(appContext)
    private val transferManager = TransferManager(appContext)
    private val networkStatusObserver = NetworkStatusObserver(appContext)
    val settingsManager = SettingsManager(appContext)

    private val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    @Volatile private var lastReceivedClipboardText: String? = null

    val networkState: StateFlow<NetworkState> = networkStatusObserver.observeStatus()
        .stateIn(coreScope, SharingStarted.Eagerly, NetworkState(false, false, false, false))

    val isDeviceReady: StateFlow<Boolean> = networkState.map { it.isReady }
        .stateIn(coreScope, SharingStarted.Eagerly, false)

    private val _connectionMode = MutableStateFlow(settingsManager.connectionMode)
    val connectionMode: StateFlow<String> = _connectionMode

    private val _receiveNearbyOnly = MutableStateFlow(settingsManager.receiveFromNearbyOnly)
    val receiveNearbyOnly: StateFlow<Boolean> = _receiveNearbyOnly

    private val _identity = MutableStateFlow(settingsManager.deviceName to settingsManager.avatarIndex)
    /** This phone's own (name, avatarIndex). */
    val identity: StateFlow<Pair<String, Int>> = _identity

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering

    private val _recentTransfers = MutableStateFlow<List<TransferRecord>>(emptyList())
    val recentTransfers: StateFlow<List<TransferRecord>> = _recentTransfers

    /** Every device seen this session (offline ones stay listed, greyed out). id = IP. */
    private val _knownDevices = MutableStateFlow<Map<String, Device>>(emptyMap())
    private val _deviceLastActivity = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Clipboard handshake state per device id; absent = OFF. */
    private val _clipboard = MutableStateFlow<Map<String, ClipboardState>>(emptyMap())

    val devices: StateFlow<List<Device>> = combine(
        _knownDevices, discoveryManager.discoveredDevices, _deviceLastActivity, _clipboard
    ) { known, active, activity, clip ->
        val map = known.mapValues { it.value.copy(isConnected = false) }.toMutableMap()
        active.forEach {
            val id = it.host.hostAddress ?: return@forEach
            map[id] = Device(id, displayName(it.name), true, it.avatarIndex)
        }
        map.values.map { d ->
            d.copy(clipboard = clip[d.id] ?: ClipboardState.OFF, lastActivity = activity[d.id] ?: 0L)
        }.sortedWith(compareByDescending<Device> { it.isConnected }
            .thenByDescending { it.lastActivity }
            .thenBy { it.name.lowercase() })
    }.stateIn(coreScope, SharingStarted.Eagerly, emptyList())

    private val _pendingClipboardRequest = MutableStateFlow<Device?>(null)
    val pendingClipboardRequest: StateFlow<Device?> = _pendingClipboardRequest

    /** One-shot messages for the UI (snackbar). */
    private val _events = MutableStateFlow<Pair<Long, String>?>(null)
    val events: StateFlow<Pair<Long, String>?> = _events

    private val sendLocks = ConcurrentHashMap<String, Mutex>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val historyFile = File(appContext.filesDir, "history.json")

    init {
        loadHistory()
        coreScope.launch {
            discoveryManager.discoveredDevices.collect { active ->
                _knownDevices.update { known ->
                    val m = known.toMutableMap()
                    active.forEach {
                        val id = it.host.hostAddress ?: return@forEach
                        m[id] = Device(id, displayName(it.name), true, it.avatarIndex)
                    }
                    m
                }
            }
        }
        // History is written a second after it settles, not on every progress tick.
        coreScope.launch {
            _recentTransfers
                .map { list -> list.map { Triple(it.id, it.state, it.localUri) } to list }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .drop(1)
                .debounce(1000)
                .collect { (_, list) -> saveHistory(list) }
        }

        clipboardManager.addPrimaryClipChangedListener {
            try {
                val clip = clipboardManager.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()
                    if (!text.isNullOrEmpty() && text != lastReceivedClipboardText) syncClipboard(text)
                }
            } catch (_: Exception) { /* clipboard not readable in the background on Android 10+ */ }
        }
    }

    private fun displayName(serviceName: String) = serviceName.removePrefix("OnSpot_")

    private fun nameFor(deviceId: String): String =
        devices.value.find { it.id == deviceId }?.name
            ?: _knownDevices.value[deviceId]?.name
            ?: deviceId

    private fun toast(msg: String) = mainHandler.post { Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show() }
    private fun event(msg: String) { _events.value = System.nanoTime() to msg }

    // ---- receiving ----------------------------------------------------------------------

    private val listener = object : TransferManager.Listener {
        override fun shouldAccept(host: String): Boolean =
            !_receiveNearbyOnly.value || discoveryManager.discoveredDevices.value.any { it.host.hostAddress == host }

        override fun onIncomingStarted(id: String, host: String, fileName: String, size: Long) {
            touch(host)
            _recentTransfers.update {
                it + TransferRecord(
                    id = id, deviceId = host, deviceName = nameFor(host), fileName = fileName,
                    sizeInBytes = size, state = TransferState.TRANSFERRING, isIncoming = true,
                    startedAt = System.currentTimeMillis()
                )
            }
        }

        override fun onIncomingProgress(id: String, bytes: Long, size: Long) {
            updateRecord(id) { it.copy(bytes = bytes, progress = if (size > 0) bytes.toFloat() / size else 0f) }
        }

        override fun onIncomingCompleted(id: String, localUri: String?) {
            var rec: TransferRecord? = null
            updateRecord(id) {
                it.copy(state = TransferState.COMPLETED, progress = 1f, bytes = it.sizeInBytes, localUri = localUri)
                    .also { r -> rec = r }
            }
            rec?.let { notifyReceived(it) }
        }

        override fun onIncomingFailed(id: String, reason: String, cancelled: Boolean) {
            updateRecord(id) {
                it.copy(state = if (cancelled) TransferState.CANCELLED else TransferState.FAILED, error = reason)
            }
        }

        override fun onIncomingRejected(host: String, fileName: String, reason: String) {
            _recentTransfers.update {
                it + TransferRecord(
                    id = UUID.randomUUID().toString(), deviceId = host, deviceName = nameFor(host),
                    fileName = fileName, sizeInBytes = 0, state = TransferState.FAILED, isIncoming = true,
                    error = "Blocked: $reason"
                )
            }
        }

        override fun onText(host: String, type: TransferType, text: String) {
            mainHandler.post { handleText(host, type, text) }
        }
    }

    private fun handleText(deviceId: String, type: TransferType, text: String) {
        touch(deviceId)
        when (type) {
            TransferType.CLIPBOARD -> {
                if (_clipboard.value[deviceId] == ClipboardState.ON && text != lastReceivedClipboardText) {
                    lastReceivedClipboardText = text
                    try {
                        clipboardManager.setPrimaryClip(ClipData.newPlainText("OnSpot", text))
                        toast("Copied from ${nameFor(deviceId)}")
                    } catch (_: Exception) {}
                }
            }
            TransferType.CLIPBOARD_REQUEST -> {
                val device = devices.value.find { it.id == deviceId }
                    ?: Device(deviceId, nameFor(deviceId), true)
                _pendingClipboardRequest.value = device
            }
            TransferType.CLIPBOARD_RESPONSE -> {
                // Only a reply to a request *we* sent counts; an unsolicited "true" is ignored.
                if (_clipboard.value[deviceId] != ClipboardState.PENDING) return
                if (text == "true") {
                    setClip(deviceId, ClipboardState.ON)
                    event("Clipboard sync is on with ${nameFor(deviceId)}")
                } else {
                    setClip(deviceId, ClipboardState.OFF)
                    event("${nameFor(deviceId)} declined clipboard sync")
                }
            }
            TransferType.TEXT -> {
                val rec = TransferRecord(
                    id = UUID.randomUUID().toString(), deviceId = deviceId, deviceName = nameFor(deviceId),
                    fileName = "Message", sizeInBytes = text.length.toLong(), state = TransferState.COMPLETED,
                    isIncoming = true, progress = 1f, type = TransferType.TEXT, textContent = text
                )
                _recentTransfers.update { it + rec }
                notifyReceived(rec)
            }
            TransferType.FILE -> {}
        }
    }

    private fun touch(deviceId: String) {
        _deviceLastActivity.update { it + (deviceId to System.currentTimeMillis()) }
    }

    private fun setClip(deviceId: String, state: ClipboardState) {
        _clipboard.update { if (state == ClipboardState.OFF) it - deviceId else it + (deviceId to state) }
    }

    // ---- clipboard sync ---------------------------------------------------------------------

    private fun syncClipboard(text: String) {
        val targets = devices.value.filter { it.isConnected && it.shareClipboard }
        if (targets.isEmpty()) return
        coreScope.launch {
            for (device in targets) {
                val d = findDevice(device.id) ?: continue
                try {
                    transferManager.sendText(text, d.host, d.port, TransferType.CLIPBOARD)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { /* peer gone; next copy retries */ }
            }
        }
    }

    /** Turns clipboard sync on (asks the other device first) or off. */
    fun toggleClipboardShare(deviceId: String) {
        when (_clipboard.value[deviceId] ?: ClipboardState.OFF) {
            ClipboardState.ON, ClipboardState.PENDING -> setClip(deviceId, ClipboardState.OFF)
            ClipboardState.OFF -> requestClipboardShare(deviceId)
        }
    }

    fun requestClipboardShare(deviceId: String) {
        val device = findDevice(deviceId) ?: run { event("${nameFor(deviceId)} is offline"); return }
        setClip(deviceId, ClipboardState.PENDING)
        coreScope.launch {
            try {
                transferManager.sendText("request", device.host, device.port, TransferType.CLIPBOARD_REQUEST)
                event("Asked ${nameFor(deviceId)} to allow clipboard sync…")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                setClip(deviceId, ClipboardState.OFF)
                event("Couldn't reach ${nameFor(deviceId)}")
            }
        }
    }

    fun respondToClipboardRequest(deviceId: String, approved: Boolean) {
        _pendingClipboardRequest.value = null
        setClip(deviceId, if (approved) ClipboardState.ON else ClipboardState.OFF)
        val device = findDevice(deviceId) ?: return
        coreScope.launch {
            try {
                transferManager.sendText(approved.toString(), device.host, device.port, TransferType.CLIPBOARD_RESPONSE)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (approved) setClip(deviceId, ClipboardState.OFF)
                event("Couldn't reach ${nameFor(deviceId)}")
            }
        }
    }

    // ---- settings ---------------------------------------------------------------------------

    /** Switch between "wifi" (whole network) and "hotspot" (direct link) discovery. */
    fun setConnectionMode(mode: String) {
        if (mode == _connectionMode.value) return
        settingsManager.connectionMode = mode
        _connectionMode.value = settingsManager.connectionMode
        restartDiscovery()
    }

    fun setReceiveNearbyOnly(on: Boolean) {
        settingsManager.receiveFromNearbyOnly = on
        _receiveNearbyOnly.value = on
    }

    /** Saves name/avatar and re-announces so other devices see the change. */
    fun setIdentity(name: String, avatarIndex: Int) {
        val clean = name.trim().take(40).ifEmpty { Build.MODEL }
        // Compared with what is being announced (not the prefs): onboarding writes the prefs
        // directly, and this is how the new name reaches the network.
        if (_identity.value == (clean to avatarIndex)) return
        settingsManager.deviceName = clean
        settingsManager.avatarIndex = avatarIndex
        _identity.value = clean to avatarIndex
        restartDiscovery()
    }

    private fun restartDiscovery() {
        val wasOn = _isDiscovering.value
        stopDiscovery()
        discoveryManager.clearDevices()
        if (!wasOn) return
        // Unregistering the old mDNS record is asynchronous. Registering the same name again
        // straight away makes Android rename it ("Name (2)"), which other devices would show as
        // a second device, so give the goodbye a moment to go out first.
        coreScope.launch {
            delay(800)
            if (!_isDiscovering.value) startDiscovery()
        }
    }

    fun rescan() = discoveryManager.rescan()

    fun startDiscovery() {
        transferManager.startListening(listener)
        if (_isDiscovering.value) return
        discoveryManager.stop()
        _isDiscovering.value = true
        discoveryManager.startDiscovery()
        coreScope.launch {
            var waited = 0
            while (transferManager.listeningPort == -1 && waited < 100) { delay(100); waited++ }
            if (transferManager.listeningPort > 0 && _isDiscovering.value) {
                discoveryManager.registerService(transferManager.listeningPort)
            }
        }
    }

    fun stopDiscovery() {
        _isDiscovering.value = false
        discoveryManager.stop()
    }

    /** Full stop: no longer discoverable, and the receiving socket is closed too. */
    fun shutdown() {
        stopDiscovery()
        transferManager.stop()
    }

    // ---- sending -----------------------------------------------------------------------------

    private fun findDevice(deviceId: String) = discoveryManager.discoveredDevices.value.find { it.host.hostAddress == deviceId }

    private fun updateRecord(id: String, transform: (TransferRecord) -> TransferRecord) {
        _recentTransfers.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    /**
     * Sends files one at a time per device (a fair Mutex keeps calls in order across separate
     * "send" taps too), with automatic retry for transient failures.
     */
    fun sendFiles(uris: List<Uri>, deviceId: String) {
        if (uris.isEmpty()) return
        if (findDevice(deviceId) == null) { event("${nameFor(deviceId)} is offline"); return }
        val batchId = if (uris.size > 1) UUID.randomUUID().toString() else null
        val name = nameFor(deviceId)
        coreScope.launch {
            // Metadata queries hit content providers: keep them off the main thread.
            val recs = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val (fileName, size) = try { transferManager.getFileMetadata(uri) } catch (_: Exception) { null to -1L }
                    TransferRecord(
                        id = UUID.randomUUID().toString(), deviceId = deviceId, deviceName = name,
                        fileName = fileName ?: "file", sizeInBytes = maxOf(size, 0L),
                        state = TransferState.PENDING, isIncoming = false, type = TransferType.FILE,
                        batchId = batchId, sourceUri = uri.toString()
                    )
                }
            }
            _recentTransfers.update { it + recs }
            recs.forEach { enqueue(it.id, Uri.parse(it.sourceUri), deviceId) }
        }
    }

    fun sendFile(uri: Uri, deviceId: String) = sendFiles(listOf(uri), deviceId)

    private fun enqueue(transferId: String, uri: Uri, deviceId: String) {
        val lock = sendLocks.getOrPut(deviceId) { Mutex() }
        val job = coreScope.launch {
            try {
                lock.withLock { sendOneFileWithRetry(uri, deviceId, transferId) }
            } catch (e: CancellationException) {
                updateRecord(transferId) { it.copy(state = TransferState.CANCELLED, retryAttempt = null, error = null) }
            } finally {
                jobs.remove(transferId)
            }
        }
        jobs[transferId] = job
    }

    private suspend fun sendOneFileWithRetry(uri: Uri, deviceId: String, transferId: String) {
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            val device = findDevice(deviceId)
            if (device == null) {
                updateRecord(transferId) { it.copy(state = TransferState.FAILED, retryAttempt = null, error = "Device went offline") }
                return
            }
            updateRecord(transferId) {
                it.copy(state = TransferState.TRANSFERRING, startedAt = System.currentTimeMillis(), bytes = 0, progress = 0f, error = null)
            }
            try {
                transferManager.sendFile(uri, device.host, device.port, transferId) { bytes, size ->
                    updateRecord(transferId) {
                        it.copy(bytes = bytes, sizeInBytes = size, progress = if (size > 0) bytes.toFloat() / size else 1f)
                    }
                }
                updateRecord(transferId) { it.copy(progress = 1f, state = TransferState.COMPLETED, retryAttempt = null) }
                touch(deviceId)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                updateRecord(transferId) { it.copy(state = TransferState.FAILED, retryAttempt = null, error = "No longer allowed to read this file") }
                return
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive() // closed by Cancel: report it as cancelled
                if (attempt < maxAttempts) {
                    updateRecord(transferId) { it.copy(progress = 0f, bytes = 0, retryAttempt = attempt) }
                    delay(800L * attempt)
                } else {
                    updateRecord(transferId) { it.copy(state = TransferState.FAILED, retryAttempt = null, error = e.message ?: "Connection lost") }
                }
            }
        }
    }

    /** Re-sends a failed or cancelled file. */
    fun retryTransfer(transferId: String) {
        val rec = _recentTransfers.value.find { it.id == transferId } ?: return
        if (rec.isIncoming || rec.type != TransferType.FILE || rec.sourceUri == null) return
        if (rec.state != TransferState.FAILED && rec.state != TransferState.CANCELLED) return
        if (findDevice(rec.deviceId) == null) { event("${nameFor(rec.deviceId)} is offline"); return }
        updateRecord(transferId) { it.copy(state = TransferState.PENDING, progress = 0f, bytes = 0, retryAttempt = null, error = null) }
        enqueue(transferId, Uri.parse(rec.sourceUri), rec.deviceId)
    }

    fun cancelTransfer(transferId: String) {
        val rec = _recentTransfers.value.find { it.id == transferId } ?: return
        if (rec.isIncoming) transferManager.cancelIncoming(transferId)
        else {
            jobs[transferId]?.cancel()
            transferManager.cancelOutgoing(transferId)
        }
    }

    fun sendText(text: String, deviceId: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val device = findDevice(deviceId) ?: run { event("${nameFor(deviceId)} is offline"); return }
        val transferId = UUID.randomUUID().toString()
        _recentTransfers.update {
            it + TransferRecord(
                id = transferId, deviceId = deviceId, deviceName = nameFor(deviceId), fileName = "Message",
                sizeInBytes = clean.length.toLong(), state = TransferState.TRANSFERRING, isIncoming = false,
                type = TransferType.TEXT, textContent = clean
            )
        }
        coreScope.launch {
            try {
                transferManager.sendText(clean, device.host, device.port, TransferType.TEXT)
                updateRecord(transferId) { it.copy(state = TransferState.COMPLETED, progress = 1f) }
                touch(deviceId)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                updateRecord(transferId) { it.copy(state = TransferState.FAILED, error = "Couldn't reach ${nameFor(deviceId)}") }
            }
        }
    }

    /** Sends whatever text is on this phone's clipboard right now. */
    fun sendClipboardNow(deviceId: String) {
        val text = try { clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(appContext)?.toString() } catch (_: Exception) { null }
        if (text.isNullOrBlank()) { event("The clipboard is empty"); return }
        sendText(text, deviceId)
    }

    // ---- history --------------------------------------------------------------------------------

    fun clearHistory() {
        _recentTransfers.update { list -> list.filter { it.isActive } }
    }

    fun removeRecord(transferId: String) {
        _recentTransfers.update { list -> list.filterNot { it.id == transferId && !it.isActive } }
    }

    private fun loadHistory() {
        try {
            if (!historyFile.exists()) return
            val arr = JSONArray(historyFile.readText())
            val out = ArrayList<TransferRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                var state = runCatching { TransferState.valueOf(o.getString("state")) }.getOrDefault(TransferState.FAILED)
                var error = o.optString("error").ifEmpty { null }
                if (state == TransferState.PENDING || state == TransferState.TRANSFERRING) {
                    state = TransferState.FAILED; error = "Interrupted"
                }
                out += TransferRecord(
                    id = o.getString("id"), deviceId = o.optString("deviceId"), deviceName = o.optString("deviceName"),
                    fileName = o.optString("fileName", "file"), sizeInBytes = o.optLong("size"), state = state,
                    isIncoming = o.optBoolean("incoming"), progress = if (state == TransferState.COMPLETED) 1f else 0f,
                    timestamp = o.optLong("ts", System.currentTimeMillis()),
                    type = runCatching { TransferType.valueOf(o.getString("type")) }.getOrDefault(TransferType.FILE),
                    textContent = o.optString("text").ifEmpty { null }, batchId = o.optString("batchId").ifEmpty { null },
                    sourceUri = o.optString("sourceUri").ifEmpty { null }, localUri = o.optString("localUri").ifEmpty { null },
                    error = error
                )
            }
            _recentTransfers.value = out
        } catch (e: Exception) {
            // A corrupt file shouldn't stop the app: keep a copy aside and start fresh.
            try { historyFile.renameTo(File(historyFile.parentFile, "history.corrupt-${System.currentTimeMillis()}.json")) } catch (_: Exception) {}
        }
    }

    private suspend fun saveHistory(list: List<TransferRecord>) = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            list.takeLast(MAX_HISTORY).forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id); put("deviceId", t.deviceId); put("deviceName", t.deviceName)
                    put("fileName", t.fileName); put("size", t.sizeInBytes); put("state", t.state.name)
                    put("incoming", t.isIncoming); put("ts", t.timestamp); put("type", t.type.name)
                    t.textContent?.let { put("text", it) }; t.batchId?.let { put("batchId", it) }
                    t.sourceUri?.let { put("sourceUri", it) }; t.localUri?.let { put("localUri", it) }
                    t.error?.let { put("error", it) }
                })
            }
            val tmp = File(historyFile.parentFile, "history.json.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(historyFile)) { historyFile.delete(); tmp.renameTo(historyFile) }
        } catch (_: Exception) {}
    }

    // ---- opening received items ------------------------------------------------------------------

    fun openTransfer(transferId: String): Boolean {
        val rec = _recentTransfers.value.find { it.id == transferId } ?: return false
        val uri = rec.localUri?.let(Uri::parse) ?: return false.also { event("This file isn't available any more") }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, appContext.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent); true
        } catch (_: ActivityNotFoundException) {
            event("No app on this phone can open ${rec.fileName}"); false
        } catch (_: Exception) {
            event("Couldn't open ${rec.fileName}"); false
        }
    }

    fun shareTransfer(transferId: String) {
        val rec = _recentTransfers.value.find { it.id == transferId } ?: return
        val send = if (rec.type == TransferType.TEXT) {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, rec.textContent)
        } else {
            val uri = rec.localUri?.let(Uri::parse) ?: return
            Intent(Intent.ACTION_SEND).setType(appContext.contentResolver.getType(uri) ?: "*/*")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            appContext.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    fun copyText(text: String) {
        lastReceivedClipboardText = text // don't bounce it straight back to synced devices
        try { clipboardManager.setPrimaryClip(ClipData.newPlainText("OnSpot", text)) } catch (_: Exception) {}
    }

    private fun notifyReceived(rec: TransferRecord) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(RECEIVED_CHANNEL, "Received files", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val content: Intent = if (rec.type == TransferType.FILE && rec.localUri != null) {
                val uri = Uri.parse(rec.localUri)
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, appContext.contentResolver.getType(uri) ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                appContext.packageManager.getLaunchIntentForPackage(appContext.packageName) ?: return
            }
            val pi = PendingIntent.getActivity(
                appContext, rec.id.hashCode(), content,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val title = if (rec.type == TransferType.TEXT) "Message from ${rec.deviceName}" else "Received from ${rec.deviceName}"
            val body = if (rec.type == TransferType.TEXT) rec.textContent.orEmpty().take(140) else rec.fileName
            val n = NotificationCompat.Builder(appContext, RECEIVED_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(appContext).notify(rec.id.hashCode(), n)
        } catch (_: SecurityException) {} catch (_: Exception) {}
    }

    companion object {
        private const val MAX_HISTORY = 500
        private const val RECEIVED_CHANNEL = "onspot_received"

        @Volatile
        private var instance: FlowshareCore? = null

        fun get(context: Context): FlowshareCore =
            instance ?: synchronized(this) {
                instance ?: FlowshareCore(context).also { instance = it }
            }
    }
}
