package com.beyondzero.onspot.presentation.home

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beyondzero.onspot.data.FlowshareCore
import com.beyondzero.onspot.domain.model.TransferRecord
import com.beyondzero.onspot.domain.model.TransferState
import com.beyondzero.onspot.domain.model.TransferType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One thing waiting in the "Choose what to send" tray. */
data class TrayItem(
    val key: String,
    val uri: Uri? = null,
    val name: String,
    val size: Long = 0L,
    val mime: String? = null,
    val text: String? = null,
    val label: String? = null
) {
    val isText: Boolean get() = text != null
}

/** What a device tile shows: a progress ring while sending, then a brief tick or cross. */
data class DeviceActivity(
    val busy: Boolean = false,
    val queuedOnly: Boolean = false,
    val progress: Float = 0f,
    val outcome: Outcome? = null
)

enum class Outcome { SENT, FAILED }

/**
 * UI-facing wrapper around [FlowshareCore] plus the screen-only state (the send tray, per-device
 * progress rings). The core keeps running after this ViewModel is cleared.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val core = FlowshareCore.get(application)

    val settingsManager get() = core.settingsManager
    val networkState = core.networkState
    val isDeviceReady = core.isDeviceReady
    val connectionMode = core.connectionMode
    val receiveNearbyOnly = core.receiveNearbyOnly
    val identity = core.identity
    val isDiscovering = core.isDiscovering
    val recentTransfers = core.recentTransfers
    val devices = core.devices
    val pendingClipboardRequest = core.pendingClipboardRequest
    val events = core.events

    private val _tray = MutableStateFlow<List<TrayItem>>(emptyList())
    val tray: StateFlow<List<TrayItem>> = _tray

    private val _activity = MutableStateFlow<Map<String, DeviceActivity>>(emptyMap())
    val activity: StateFlow<Map<String, DeviceActivity>> = _activity

    /** Received items newer than this get a badge on the Receive tab. */
    private val _receiveSeenAt = MutableStateFlow(System.currentTimeMillis())
    val receiveSeenAt: StateFlow<Long> = _receiveSeenAt

    private val runs = HashMap<String, MutableSet<String>>()
    private var seq = 0

    init {
        viewModelScope.launch { core.recentTransfers.collect { trackActivity(it) } }
    }

    private fun trackActivity(list: List<TransferRecord>) {
        val next = _activity.value.toMutableMap()
        val outgoing = list.filter { !it.isIncoming && it.type == TransferType.FILE }
        val byDevice = outgoing.groupBy { it.deviceId }
        val ids = byDevice.keys + runs.keys
        for (deviceId in ids) {
            val mine = byDevice[deviceId].orEmpty()
            val active = mine.filter { it.isActive }
            if (active.isNotEmpty()) {
                val run = runs.getOrPut(deviceId) { mutableSetOf() }
                active.forEach { run += it.id }
                val inRun = mine.filter { it.id in run }
                val total = inRun.sumOf { it.sizeInBytes }
                val done = inRun.sumOf { if (it.state == TransferState.COMPLETED) it.sizeInBytes else it.bytes }
                next[deviceId] = DeviceActivity(
                    busy = true,
                    queuedOnly = active.all { it.state == TransferState.PENDING },
                    progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                )
            } else {
                val run = runs.remove(deviceId)
                if (run != null) {
                    val inRun = mine.filter { it.id in run }
                    val failed = inRun.any { it.state == TransferState.FAILED }
                    val allCancelled = inRun.isNotEmpty() && inRun.all { it.state == TransferState.CANCELLED }
                    if (allCancelled || inRun.isEmpty()) next.remove(deviceId)
                    else flashOutcome(next, deviceId, if (failed) Outcome.FAILED else Outcome.SENT)
                } else if (next[deviceId]?.busy == true) {
                    next.remove(deviceId)
                }
            }
        }
        _activity.value = next
    }

    private fun flashOutcome(map: MutableMap<String, DeviceActivity>, deviceId: String, outcome: Outcome) {
        map[deviceId] = DeviceActivity(outcome = outcome)
        viewModelScope.launch {
            delay(3500)
            _activity.update { cur -> if (cur[deviceId]?.outcome == outcome && cur[deviceId]?.busy == false) cur - deviceId else cur }
        }
    }

    // ---- tray -----------------------------------------------------------------------------

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val existing = _tray.value.mapNotNull { it.uri }.toSet()
            val fresh = uris.distinct().filter { it !in existing }
            val startSeq = seq.also { seq += fresh.size }
            val items = withContext(Dispatchers.IO) {
                fresh.mapIndexed { i, uri ->
                    // Keep read access across app restarts where the provider allows it, so a
                    // failed send can still be retried later.
                    try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    var name: String? = null
                    var size = 0L
                    try {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                name = c.getString(0)
                                if (!c.isNull(1)) size = c.getLong(1)
                            }
                        }
                    } catch (_: Exception) {}
                    TrayItem(
                        key = "u${startSeq + i}", uri = uri, name = name ?: uri.lastPathSegment ?: "file",
                        size = size, mime = try { resolver.getType(uri) } catch (_: Exception) { null }
                    )
                }
            }
            _tray.update { it + items }
        }
    }

    fun addText(text: String, label: String? = null) {
        val t = text.trim()
        if (t.isEmpty()) return
        _tray.update { it + TrayItem(key = "t${seq++}", name = label ?: "Message", text = t, label = label) }
    }

    fun removeFromTray(key: String) = _tray.update { list -> list.filterNot { it.key == key } }
    fun clearTray() { _tray.value = emptyList() }

    /** Sends everything in the tray to one device. The tray stays, so it can go to another device too. */
    fun sendTrayTo(deviceId: String) {
        val items = _tray.value
        val uris = items.mapNotNull { it.uri }
        if (uris.isNotEmpty()) core.sendFiles(uris, deviceId)
        items.mapNotNull { it.text }.forEach { core.sendText(it, deviceId) }
        if (uris.isEmpty() && items.isNotEmpty()) {
            _activity.update { it + (deviceId to DeviceActivity(outcome = Outcome.SENT)) }
            viewModelScope.launch {
                delay(2500)
                _activity.update { cur -> if (cur[deviceId]?.busy == false) cur - deviceId else cur }
            }
        }
    }

    /** Files and text shared to OnSpot from another app's share sheet. */
    fun handleShareIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (stream != null) addUris(listOf(stream))
                else intent.getStringExtra(Intent.EXTRA_TEXT)?.let { addText(it, "Shared text") }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                list?.let { addUris(it) }
            }
        }
    }

    // ---- pass-throughs -----------------------------------------------------------------------

    fun markReceiveSeen() { _receiveSeenAt.value = System.currentTimeMillis() }
    fun setConnectionMode(mode: String) = core.setConnectionMode(mode)
    fun setReceiveNearbyOnly(on: Boolean) = core.setReceiveNearbyOnly(on)
    fun setIdentity(name: String, avatarIndex: Int) = core.setIdentity(name, avatarIndex)
    fun rescan() = core.rescan()
    fun respondToClipboardRequest(deviceId: String, approved: Boolean) = core.respondToClipboardRequest(deviceId, approved)
    fun toggleClipboardShare(deviceId: String) = core.toggleClipboardShare(deviceId)
    fun startDiscovery() = core.startDiscovery()
    fun stopDiscovery() = core.stopDiscovery()
    fun sendFiles(uris: List<Uri>, deviceId: String) = core.sendFiles(uris, deviceId)
    fun sendText(text: String, deviceId: String) = core.sendText(text, deviceId)
    fun sendClipboardNow(deviceId: String) = core.sendClipboardNow(deviceId)
    fun retryTransfer(transferId: String) = core.retryTransfer(transferId)
    fun cancelTransfer(transferId: String) = core.cancelTransfer(transferId)
    fun openTransfer(transferId: String) = core.openTransfer(transferId)
    fun shareTransfer(transferId: String) = core.shareTransfer(transferId)
    fun copyText(text: String) = core.copyText(text)
    fun clearHistory() = core.clearHistory()
    fun removeRecord(transferId: String) = core.removeRecord(transferId)
}
