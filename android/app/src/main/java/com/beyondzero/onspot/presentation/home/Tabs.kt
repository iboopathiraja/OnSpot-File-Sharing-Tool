@file:OptIn(ExperimentalMaterial3Api::class)

package com.beyondzero.onspot.presentation.home

import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.rounded.ArrowUpward
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beyondzero.onspot.data.settings.SettingsManager
import com.beyondzero.onspot.domain.model.ClipboardState
import com.beyondzero.onspot.domain.model.Device
import com.beyondzero.onspot.domain.model.TransferRecord
import com.beyondzero.onspot.domain.model.TransferState
import com.beyondzero.onspot.domain.model.TransferType

private val Gutter = 20.dp

private fun pagePadding(bottom: Dp) = PaddingValues(start = Gutter, end = Gutter, top = 4.dp, bottom = bottom)

/** Online first, then most recently used, then by name. */
private fun List<Device>.sortedForPeople() =
    sortedWith(compareByDescending<Device> { it.isConnected }.thenByDescending { it.lastActivity }.thenBy { it.name.lowercase() })

/** Transfers grouped per device, newest device first, each list newest first. */
private fun List<TransferRecord>.byDevice(): List<Pair<String, List<TransferRecord>>> =
    groupBy { it.peerName() }
        .map { (name, list) -> name to list.sortedByDescending { it.timestamp } }
        .sortedByDescending { it.second.first().timestamp }

// ---- HOME (send quickly) -----------------------------------------------------------------------------

@Composable
fun HomePage(
    viewModel: HomeViewModel,
    devices: List<Device>,
    transfers: List<TransferRecord>,
    bottomPad: Dp,
    onOpenDevice: (String) -> Unit,
    toast: (String) -> Unit
) {
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val network by viewModel.networkState.collectAsStateWithLifecycle()
    val mode by viewModel.connectionMode.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var textFor by remember { mutableStateOf<Device?>(null) }
    fun sendUris(uris: List<Uri>) {
        val t = target
        target = null
        if (t != null && uris.isNotEmpty()) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendFiles(uris, t) }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> sendUris(uris) }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris: List<Uri> -> sendUris(uris) }

    val people = remember(devices) { devices.sortedForPeople() }
    val online = people.filter { it.isConnected }
    val offline = people.filter { !it.isConnected }
    val incoming = transfers.filter { it.isIncoming && it.isActive }
    val counts = transfers.groupingBy { it.peerName() }.eachCount()

    LazyColumn(contentPadding = pagePadding(bottomPad), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "status") {
            StatusCard(
                identity.first, identity.second, network.isReady,
                when {
                    !network.isReady -> "Not connected · turn on Wi‑Fi or a hotspot"
                    online.isEmpty() -> "Ready to receive · looking for devices"
                    else -> "Ready to receive · ${plural(online.size, "device")} nearby"
                }
            )
        }
        if (incoming.isNotEmpty()) {
            item(key = "inTitle") { SectionTitle("Receiving now") }
            items(incoming, key = { "in:" + it.id }) { TransferRow(it, viewModel, modifier = Modifier.animateItem()) }
        }
        item(key = "sendTitle") { SectionTitle("Send to") }
        if (online.isEmpty()) {
            item(key = "noOne") {
                TipsCard(
                    listOf(
                        "Open OnSpot on the other device",
                        if (mode == SettingsManager.MODE_HOTSPOT) "Connect both devices to the same hotspot" else "Connect both devices to the same Wi‑Fi",
                        "Tap refresh at the top to scan again"
                    )
                )
            }
        } else {
            items(online, key = { "s:" + it.id }) { d ->
                SendCard(
                    d, activity[d.id], counts[d.name] ?: 0, Modifier.animateItem(),
                    onOpen = { onOpenDevice(d.name) },
                    onPhotos = { target = d.id; media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onFiles = { target = d.id; files.launch(arrayOf("*/*")) },
                    onText = { textFor = d },
                    onClipboard = {
                        if (clipboard.getText()?.text.isNullOrBlank()) toast("Your clipboard is empty")
                        else { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendClipboardNow(d.id); toast("Clipboard sent to ${d.name}") }
                    }
                )
            }
        }
        if (offline.isNotEmpty()) {
            item(key = "offTitle") { SectionTitle("Seen before") }
            items(offline, key = { "o:" + it.id }) { d -> OfflineRow(d, counts[d.name] ?: 0, Modifier.animateItem()) { onOpenDevice(d.name) } }
        }
    }
    textFor?.let { d ->
        TextEntryDialog(title = "Send to ${d.name}", confirm = "Send", onDismiss = { textFor = null }) { text ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.sendText(text, d.id)
            toast("Sent to ${d.name}")
        }
    }
}

/** This phone and whether it can receive right now. No animation, just the facts. */
@Composable
private fun StatusCard(name: String, avatar: Int, ready: Boolean, status: String) {
    Row(
        Modifier.fillMaxWidth().background(cardColor(), RoundedCornerShape(24.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name, avatar, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (ready) Ui.Green else Color(0xFFF59E0B), CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One nearby device, ready to send to: tap an action to send that kind of thing straight away. */
@Composable
private fun SendCard(
    d: Device, act: DeviceActivity?, shared: Int, modifier: Modifier,
    onOpen: () -> Unit, onPhotos: () -> Unit, onFiles: () -> Unit, onText: () -> Unit, onClipboard: () -> Unit
) {
    val busy = act?.busy == true
    val (status, color) = when {
        busy && act?.queuedOnly == true -> "Waiting to send…" to Ui.Blue
        busy -> "Sending · ${((act?.progress ?: 0f) * 100).toInt()}%" to Ui.Blue
        act?.outcome == Outcome.SENT -> "Sent" to Color(0xFF15803D)
        act?.outcome == Outcome.FAILED -> "Couldn't send · tap to see why" to Ui.Red
        else -> "Nearby · ${if (shared > 0) plural(shared, "item") + " shared" else "ready"}" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier.fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x14000000), spotColor = Color(0x22000000))
            .background(cardColor(), RoundedCornerShape(24.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.bounceClick(scaleDown = 0.98f, onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            PersonDot(d, act, 50.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.bodySmall, color = color, fontWeight = if (busy) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
            Icon(Icons.Rounded.ChevronRight, "Open", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (busy) { Spacer(Modifier.height(10.dp)); ThinProgress(act?.progress ?: 0f) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickSend(Icons.Rounded.PhotoLibrary, "Photos", 2, Modifier.weight(1f), onPhotos)
            QuickSend(Icons.Rounded.Description, "Files", 0, Modifier.weight(1f), onFiles)
            QuickSend(Icons.Rounded.TextFields, "Text", 3, Modifier.weight(1f), onText)
            QuickSend(Icons.Rounded.ContentPaste, "Clipboard", 1, Modifier.weight(1f), onClipboard)
        }
    }
}

@Composable
private fun QuickSend(icon: ImageVector, label: String, tint: Int, modifier: Modifier, onClick: () -> Unit) {
    val p = pastelAt(tint)
    Column(
        modifier.bounceClick(scaleDown = 0.92f, onClick = onClick).background(p.bg, RoundedCornerShape(16.dp)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = p.ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = p.ink, maxLines = 1)
    }
}

/** A device picture with a progress ring while busy and a green dot while online. */
@Composable
private fun PersonDot(d: Device, act: DeviceActivity?, size: Dp) {
    val busy = act?.busy == true
    val ring = cardColor()
    Box(Modifier.size(size + 6.dp), contentAlignment = Alignment.Center) {
        if (busy) ProgressRing(maxOf(0.03f, act?.progress ?: 0f), Modifier.fillMaxSize(), track = MaterialTheme.colorScheme.outlineVariant)
        Avatar(d.name, d.avatarIndex, size, Modifier.alpha(if (d.isConnected) 1f else 0.45f))
        if (d.isConnected && !busy) {
            Box(Modifier.align(Alignment.BottomEnd).size(15.dp).background(ring, CircleShape).padding(2.5.dp).background(Ui.Green, CircleShape))
        }
    }
}

/** What to check when nothing shows up. */
@Composable
private fun TipsCard(tips: List<String>) {
    Column(Modifier.fillMaxWidth().background(cardColor(), RoundedCornerShape(24.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("No devices found yet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        tips.forEachIndexed { i, tip ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).background(pastelAt(0).bg, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Ui.Blue)
                }
                Spacer(Modifier.width(10.dp))
                Text(tip, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** A device that isn't around right now. */
@Composable
private fun OfflineRow(d: Device, shared: Int, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().bounceClick(scaleDown = 0.98f, onClick = onClick).background(cardColor(), RoundedCornerShape(20.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(d.name, d.avatarIndex, 40.dp, Modifier.alpha(0.5f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Offline" + (if (d.lastActivity > 0) " · last used ${dayLabel(d.lastActivity).lowercase()}" else "") + (if (shared > 0) " · ${plural(shared, "item")}" else ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- DEVICE (detail) ------------------------------------------------------------------------------------

private enum class Kind(val label: String) { ALL("All"), PHOTOS("Photos"), FILES("Files"), TEXT("Messages") }

private fun Kind.test(t: TransferRecord) = when (this) {
    Kind.ALL -> true
    Kind.PHOTOS -> t.type == TransferType.FILE && isVisualName(t.fileName)
    Kind.FILES -> t.type == TransferType.FILE && !isVisualName(t.fileName)
    Kind.TEXT -> t.type == TransferType.TEXT
}

@Composable
fun DevicePage(
    viewModel: HomeViewModel,
    key: String,
    devices: List<Device>,
    transfers: List<TransferRecord>,
    myAvatar: Int,
    bottomPad: Dp,
    onBack: () -> Unit,
    toast: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val device = devices.byPeer(key)
    val online = device?.isConnected == true
    val all = remember(transfers, key, device?.id) {
        transfers.filter { it.peerName() == key || (device != null && it.deviceId == device.id) }.sortedByDescending { it.timestamp }
    }
    var kind by rememberSaveable { mutableStateOf(Kind.ALL) }
    var dir by rememberSaveable { mutableStateOf(0) } // 0 everything, 1 received, 2 sent
    var showText by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val inDir = all.filter { dir == 0 || (dir == 1) == it.isIncoming }
    val shown = inDir.filter { kind.test(it) }
    fun sent() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty() && device != null) { sent(); viewModel.sendFiles(uris, device.id) }
    }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris: List<Uri> ->
        if (uris.isNotEmpty() && device != null) { sent(); viewModel.sendFiles(uris, device.id) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            Box {
                CircleButton(Icons.Rounded.MoreHoriz, "More") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(18.dp), containerColor = cardColor()) {
                    DropdownMenuItem(
                        text = { Text("Send text") }, leadingIcon = { Icon(Icons.Rounded.TextFields, null) }, enabled = online,
                        onClick = { menu = false; showText = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Send clipboard") }, leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) }, enabled = online,
                        onClick = { menu = false; device?.let { sent(); viewModel.sendClipboardNow(it.id) } }
                    )
                    if (device != null) {
                        DropdownMenuItem(
                            text = { Text(if (device.clipboard == ClipboardState.OFF) "Turn on clipboard sync" else "Turn off clipboard sync") },
                            leadingIcon = { Icon(Icons.Rounded.SyncAlt, null) },
                            enabled = online || device.clipboard != ClipboardState.OFF,
                            onClick = { menu = false; viewModel.toggleClipboardShare(device.id) }
                        )
                    }
                }
            }
        }
        LazyColumn(contentPadding = pagePadding(bottomPad), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "hero") {
                Column(Modifier.fillMaxWidth().background(cardColor(), RoundedCornerShape(24.dp)).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(key, device?.avatarIndex ?: 1, 56.dp, Modifier.alpha(if (online) 1f else 0.5f))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(key, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(if (online) Ui.Green else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (online) "Nearby now" else "Offline", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BluePill("Send files", Modifier.weight(1f), icon = Icons.Rounded.UploadFile, enabled = online) { files.launch(arrayOf("*/*")) }
                        GhostPill("Photos", Icons.Rounded.PhotoLibrary, enabled = online) {
                            media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }
                    }
                }
            }
            item(key = "dir") {
                Row(Modifier.fillMaxWidth().background(cardColor(), CircleShape).padding(4.dp)) {
                    SegTab("All", all.size, dir == 0, Modifier.weight(1f)) { dir = 0 }
                    SegTab("Received", all.count { it.isIncoming }, dir == 1, Modifier.weight(1f)) { dir = 1 }
                    SegTab("Sent", all.count { !it.isIncoming }, dir == 2, Modifier.weight(1f)) { dir = 2 }
                }
            }
            item(key = "kinds") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Kind.entries.toList()) { k ->
                        val n = inDir.count { k.test(it) }
                        if (k == Kind.ALL || n > 0) {
                            val icon = when (k) { Kind.PHOTOS -> Icons.Rounded.PhotoLibrary; Kind.FILES -> Icons.Rounded.Description; Kind.TEXT -> Icons.Rounded.TextFields; else -> null }
                            FilterPill(k.label, kind == k, n, icon = icon) { kind = k }
                        }
                    }
                }
            }
            if (!online) {
                item(key = "offline") {
                    val p = pastelAt(1)
                    Row(Modifier.fillMaxWidth().background(p.bg, RoundedCornerShape(20.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WifiOff, null, tint = p.ink)
                        Spacer(Modifier.width(10.dp))
                        Text("$key is offline. Open OnSpot on it to send again.", style = MaterialTheme.typography.bodySmall, color = p.ink)
                    }
                }
            }
            if (device != null && device.clipboard != ClipboardState.OFF) {
                item(key = "clip") {
                    val p = pastelAt(4)
                    Row(Modifier.fillMaxWidth().background(p.bg, RoundedCornerShape(20.dp)).padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SyncAlt, null, tint = p.ink)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (device.clipboard == ClipboardState.ON) "Clipboard sync is on" else "Waiting for ${device.name} to allow clipboard sync…",
                            style = MaterialTheme.typography.bodySmall, color = p.ink, modifier = Modifier.weight(1f)
                        )
                        Switch(checked = true, onCheckedChange = { viewModel.toggleClipboardShare(device.id) }, colors = SwitchDefaults.colors(checkedTrackColor = Ui.Blue))
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "none") { EmptyNote(Icons.Rounded.UploadFile, "Nothing here yet", "Files and messages you share with $key show up here, sorted newest first.") }
            } else {
                items(shown.take(60).chunked(2), key = { row -> "g:" + row.first().id }) { row ->
                    PrintRow(row, viewModel, devices, Modifier.animateItem())
                }
            }
        }
    }
    if (showText) {
        TextEntryDialog(title = "Send to $key", confirm = "Send", onDismiss = { showText = false }) { text ->
            device?.let { sent(); viewModel.sendText(text, it.id); toast("Sent to $key") }
        }
    }
}

/** Two tiles side by side. */
@Composable
private fun PrintRow(row: List<TransferRecord>, viewModel: HomeViewModel, devices: List<Device>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        row.forEachIndexed { i, t -> PrintTile(t, viewModel, devices, Modifier.weight(1f)) }
        if (row.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PrintTile(t: TransferRecord, viewModel: HomeViewModel, devices: List<Device>, modifier: Modifier) {
    val isText = t.type == TransferType.TEXT
    Column(
        modifier
            .bounceClick(onLongClick = { if (!t.isActive) viewModel.shareTransfer(t.id) }) {
                if (isText) t.textContent?.let(viewModel::copyText) else if (t.state == TransferState.COMPLETED) viewModel.openTransfer(t.id)
            }
            .background(cardColor(), RoundedCornerShape(18.dp))
            .padding(6.dp)
    ) {
        MediaBox(t, Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (t.isIncoming) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, if (t.isIncoming) "Received" else "Sent",
                tint = if (t.isIncoming) Ui.Green else Ui.Blue, modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(if (isText) "Message" else t.fileName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${if (isText) formatTime(t.timestamp) else formatSize(t.sizeInBytes)} · ${dayLabel(t.timestamp)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (t.isActive && !isText) { Spacer(Modifier.height(6.dp)); ThinProgress(t.progress) }
    }
}

// ---- FILES (explore by device) ----------------------------------------------------------------------------

@Composable
fun ReceivedPage(viewModel: HomeViewModel, transfers: List<TransferRecord>, devices: List<Device>, bottomPad: Dp, onOpenDevice: (String) -> Unit) {
    val context = LocalContext.current
    val moving = transfers.filter { it.isActive }
    val groups = remember(transfers) {
        transfers.filter { !it.isActive }.groupBy { it.peerName() }.toList().sortedByDescending { (_, l) -> l.maxOf { it.timestamp } }
    }

    LazyColumn(contentPadding = pagePadding(bottomPad), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        if (moving.isNotEmpty()) {
            item(key = "movingTitle") { SectionTitle("In progress") }
            items(moving, key = { "m:" + it.id }) { TransferRow(it, viewModel, modifier = Modifier.animateItem()) }
        }
        item(key = "byTitle") { SectionTitle(if (groups.isEmpty()) "By device" else "By device · ${groups.size}") }
        if (groups.isEmpty()) {
            item(key = "none") { EmptyNote(Icons.Rounded.Folder, "Nothing shared yet", "Everything you send or receive is kept here, one folder per device.") }
        } else {
            items(groups, key = { "d:" + it.first }) { (name, list) ->
                DeviceFolder(name, devices.byPeer(name), list, Modifier.animateItem()) { onOpenDevice(name) }
            }
        }
        item(key = "folder") {
            SettingRow(Icons.Rounded.Folder, "Saved in Downloads/OnSpot", "Open your Downloads folder", chevron = true) {
                try { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        }
    }
}

/** One device's folder: who, how much went each way, and a peek at the newest four items. */
@Composable
private fun DeviceFolder(name: String, device: Device?, list: List<TransferRecord>, modifier: Modifier, onOpen: () -> Unit) {
    val latest = remember(list) { list.sortedByDescending { it.timestamp } }
    val received = list.count { it.isIncoming }
    val sent = list.size - received
    val online = device?.isConnected == true
    val ring = cardColor()
    Column(
        modifier.fillMaxWidth().bounceClick(scaleDown = 0.98f, onClick = onOpen)
            .background(cardColor(), RoundedCornerShape(24.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp)) {
                Avatar(name, device?.avatarIndex ?: 1, 46.dp)
                if (online) Box(Modifier.align(Alignment.BottomEnd).size(14.dp).background(ring, CircleShape).padding(2.5.dp).background(Ui.Green, CircleShape))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$received received · $sent sent · ${dayLabel(latest.first().timestamp).lowercase()}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Rounded.ChevronRight, "Open", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        val peek = latest.take(4)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            peek.forEachIndexed { i, t ->
                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))) {
                    MediaBox(t, Modifier.fillMaxSize())
                    if (i == 3 && list.size > 4) {
                        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                            Text("+${list.size - 3}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
            repeat(4 - peek.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SegTab(label: String, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .bounceClick(scaleDown = 0.97f, onClick = onClick)
            .background(if (selected) Ui.Blue else Color.Transparent, CircleShape)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(6.dp))
        Text(
            "$count", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            color = if (selected) Ui.Blue else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.background(if (selected) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp)
        )
    }
}

// ---- DEVICES ---------------------------------------------------------------------------------------------

@Composable
fun DevicesPage(
    viewModel: HomeViewModel,
    devices: List<Device>,
    transfers: List<TransferRecord>,
    bottomPad: Dp,
    onOpenDevice: (String) -> Unit,
    onSettings: () -> Unit,
    toast: (String) -> Unit
) {
    val context = LocalContext.current
    val nearbyOnly by viewModel.receiveNearbyOnly.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val network by viewModel.networkState.collectAsStateWithLifecycle()
    val mode by viewModel.connectionMode.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val t = target
        target = null
        if (t != null && uris.isNotEmpty()) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.sendFiles(uris, t) }
    }
    val people = remember(devices) { devices.sortedForPeople() }
    val online = people.filter { it.isConnected }
    val offline = people.filter { !it.isConnected }
    val shared = remember(transfers) { transfers.groupBy { it.peerName() } }
    val hotspot = mode == SettingsManager.MODE_HOTSPOT

    LazyColumn(contentPadding = pagePadding(bottomPad), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        // This phone: who you are, whether you're visible, and which network you use.
        item(key = "me") {
            Group {
                Row(
                    Modifier.fillMaxWidth().bounceClick(scaleDown = 0.98f, onClick = onSettings).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(identity.first, identity.second, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(identity.first, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(if (network.isReady) Ui.Green else Color(0xFFF59E0B), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (!network.isReady) "Not connected" else if (hotspot) "Visible on your hotspot" else "Visible on your Wi‑Fi",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text("Edit", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Ui.Blue)
                }
                Line()
                Row(Modifier.fillMaxWidth().padding(10.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).padding(4.dp)) {
                    ModeChip("Wi‑Fi", !hotspot, Modifier.weight(1f)) { viewModel.setConnectionMode(SettingsManager.MODE_WIFI) }
                    ModeChip("Hotspot", hotspot, Modifier.weight(1f)) { viewModel.setConnectionMode(SettingsManager.MODE_HOTSPOT) }
                }
            }
        }

        item(key = "onTitle") { SectionTitle(if (online.isEmpty()) "Nearby" else "Nearby · ${online.size}") }
        item(key = "online") {
            Group {
                if (online.isEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, null, tint = Ui.Blue, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("No devices nearby", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (hotspot) "Open OnSpot on a device connected to the same hotspot." else "Open OnSpot on a device on the same Wi‑Fi.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    online.forEachIndexed { i, d ->
                        if (i > 0) Line(start = 76.dp)
                        DeviceLine(
                            d, activity[d.id], shared[d.name].orEmpty(),
                            onOpen = { onOpenDevice(d.name) },
                            onSend = { target = d.id; files.launch(arrayOf("*/*")) }
                        )
                    }
                }
            }
        }

        if (offline.isNotEmpty()) {
            item(key = "offTitle") { SectionTitle("Seen before · ${offline.size}") }
            item(key = "offline") {
                Group {
                    offline.forEachIndexed { i, d ->
                        if (i > 0) Line(start = 76.dp)
                        DeviceLine(d, null, shared[d.name].orEmpty(), onOpen = { onOpenDevice(d.name) }, onSend = null)
                    }
                }
            }
        }

        item(key = "rxTitle") { SectionTitle("Receiving") }
        item(key = "rx") {
            Group {
                GroupRow(
                    Icons.Rounded.Shield, "Only from my devices",
                    if (nearbyOnly) "Devices not listed here can't send to you" else "Anyone on the network can send to you",
                    onClick = { viewModel.setReceiveNearbyOnly(!nearbyOnly) }
                ) {
                    Switch(checked = nearbyOnly, onCheckedChange = { viewModel.setReceiveNearbyOnly(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Ui.Blue))
                }
                Line(start = 66.dp)
                GroupRow(
                    Icons.Rounded.Folder, "Save location", "Downloads/OnSpot",
                    onClick = {
                        try { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: Exception) { toast("Couldn't open Downloads") }
                    }
                ) { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** A white rounded card that holds a list of rows, like a settings group. */
@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cardColor())) { content() }
}

/** A hairline between rows of a [Group]. */
@Composable
private fun Line(start: Dp = 0.dp) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
}

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.bounceClick(scaleDown = 0.97f, onClick = onClick)
            .background(if (selected) cardColor() else Color.Transparent, CircleShape)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = if (selected) Ui.Blue else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One device in the list: picture, name, what's going on with it, and a quick send button. */
@Composable
private fun DeviceLine(d: Device, act: DeviceActivity?, shared: List<TransferRecord>, onOpen: () -> Unit, onSend: (() -> Unit)?) {
    val busy = act?.busy == true
    val last = shared.maxOfOrNull { it.timestamp } ?: d.lastActivity
    val (status, color) = when {
        busy -> "Sending · ${((act?.progress ?: 0f) * 100).toInt()}%" to Ui.Blue
        act?.outcome == Outcome.FAILED -> "Couldn't send · tap to see why" to Ui.Red
        d.clipboard == ClipboardState.ON -> "Clipboard sync on" + (if (shared.isNotEmpty()) " · ${plural(shared.size, "item")}" else "") to Color(0xFF15803D)
        d.clipboard == ClipboardState.PENDING -> "Waiting to allow clipboard sync" to MaterialTheme.colorScheme.onSurfaceVariant
        shared.isNotEmpty() -> "${plural(shared.size, "item")} shared · ${dayLabel(last).lowercase()}" to MaterialTheme.colorScheme.onSurfaceVariant
        !d.isConnected && last > 0 -> "Last seen ${dayLabel(last).lowercase()}" to MaterialTheme.colorScheme.onSurfaceVariant
        d.isConnected -> "Ready" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Offline" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().bounceClick(scaleDown = 0.98f, onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PersonDot(d, act, 46.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onSend != null) {
            Box(
                Modifier.size(40.dp).bounceClick(enabled = !busy, scaleDown = 0.88f, onClick = onSend).background(pastelAt(0).bg, CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.UploadFile, "Send files to ${d.name}", tint = Ui.Blue, modifier = Modifier.size(20.dp)) }
        } else {
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A row inside a [Group]: icon, label, a line of explanation and a control on the right. */
@Composable
private fun GroupRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().bounceClick(scaleDown = 0.98f, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).background(pastelAt(0).bg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Ui.Blue, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector, label: String, value: String,
    chevron: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().bounceClick(scaleDown = 0.98f, onClick = onClick).background(cardColor(), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).background(pastelAt(0).bg, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Ui.Blue, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- ACTIVITY ----------------------------------------------------------------------------------------------

private enum class Filter(val label: String) { ALL("All"), SENT("Sent"), RECEIVED("Received"), TEXT("Messages"), FAILED("Failed") }

private sealed interface HistoryEntry {
    data class Day(val label: String) : HistoryEntry
    data class Single(val t: TransferRecord) : HistoryEntry
    data class Batch(val id: String, val members: List<TransferRecord>) : HistoryEntry
}

private fun Filter.test(t: TransferRecord): Boolean = when (this) {
    Filter.ALL -> true
    Filter.SENT -> !t.isIncoming
    Filter.RECEIVED -> t.isIncoming
    Filter.TEXT -> t.type == TransferType.TEXT
    Filter.FAILED -> t.state == TransferState.FAILED || t.state == TransferState.CANCELLED
}

private fun buildEntries(shown: List<TransferRecord>): List<HistoryEntry> {
    val out = mutableListOf<HistoryEntry>()
    val batches = HashMap<String, MutableList<TransferRecord>>()
    var day = ""
    for (t in shown) {
        val b = t.batchId
        val existing = if (b != null) batches[b] else null
        if (existing != null) { existing += t; continue }
        val d = dayLabel(t.timestamp)
        if (d != day) { day = d; out += HistoryEntry.Day(d) }
        if (b != null) {
            val list = mutableListOf(t)
            batches[b] = list
            out += HistoryEntry.Batch(b, list)
        } else out += HistoryEntry.Single(t)
    }
    return out
}

@Composable
fun ActivityPage(
    viewModel: HomeViewModel,
    transfers: List<TransferRecord>,
    devices: List<Device>,
    query: String,
    onQuery: (String) -> Unit,
    bottomPad: Dp
) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var peer by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val q = query.trim().lowercase()
    val peers = transfers.groupingBy { it.peerName() }.eachCount()
    val activePeer = peer?.takeIf { it in peers }
    val shown = transfers.filter { t ->
        filter.test(t) && (activePeer == null || t.peerName() == activePeer) &&
            (q.isEmpty() || listOf(t.fileName, t.textContent, t.deviceName).any { it?.lowercase()?.contains(q) == true })
    }.sortedByDescending { it.timestamp }
    val entries = remember(shown) { buildEntries(shown) }

    LazyColumn(contentPadding = pagePadding(bottomPad), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "search") {
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear search") } },
                placeholder = { Text("Search files, messages and devices") },
                shape = CircleShape, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = cardColor(), focusedContainerColor = cardColor(),
                    unfocusedBorderColor = Color.Transparent, focusedBorderColor = Ui.Blue
                )
            )
        }
        item(key = "chips") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(Filter.entries.toList()) { f ->
                        val n = if (f == Filter.ALL) null else transfers.count { f.test(it) }
                        if (n != 0) FilterPill(f.label, filter == f && activePeer == null, n) { filter = f; peer = null }
                    }
                    items(peers.entries.toList(), key = { "p:" + it.key }) { (name, n) ->
                        FilterPill(name, activePeer == name, n, avatarIndex = devices.byPeer(name)?.avatarIndex ?: 1) { peer = name; filter = Filter.ALL }
                    }
                }
                if (transfers.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear", color = Ui.Blue) }
            }
        }
        if (entries.isEmpty()) {
            item(key = "none") {
                EmptyNote(Icons.Rounded.History, if (transfers.isEmpty()) "No activity yet" else "Nothing matches", "Everything you send and receive shows up here.")
            }
        }
        items(entries, key = { e ->
            when (e) {
                is HistoryEntry.Day -> "day:${e.label}"
                is HistoryEntry.Single -> e.t.id
                is HistoryEntry.Batch -> "batch:${e.id}"
            }
        }) { e ->
            when (e) {
                is HistoryEntry.Day -> Text(e.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                is HistoryEntry.Single -> TransferRow(e.t, viewModel, modifier = Modifier.animateItem())
                is HistoryEntry.Batch -> BatchCard(e.members, viewModel)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear activity?") },
            text = { Text("Received files stay in Downloads/OnSpot. Transfers in progress are kept.") },
            confirmButton = { TextButton(onClick = { viewModel.clearHistory(); confirmClear = false }) { Text("Clear", color = Ui.Blue, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            containerColor = cardColor(), shape = RoundedCornerShape(28.dp)
        )
    }
}

@Composable
private fun BatchCard(members: List<TransferRecord>, viewModel: HomeViewModel) {
    if (members.size == 1) { TransferRow(members[0], viewModel); return }
    var open by rememberSaveable(members.first().batchId) { mutableStateOf(false) }
    val first = members.first()
    val total = members.sumOf { it.sizeInBytes }
    val done = members.count { it.state == TransferState.COMPLETED }
    val failed = members.count { it.state == TransferState.FAILED }
    val active = members.filter { it.isActive }
    val doneBytes = members.sumOf { if (it.state == TransferState.COMPLETED) it.sizeInBytes else it.bytes }
    val progress = if (total > 0) doneBytes.toFloat() / total else 0f

    Column(Modifier.fillMaxWidth().background(cardColor(), RoundedCornerShape(20.dp))) {
        Row(Modifier.fillMaxWidth().bounceClick(scaleDown = 0.98f) { open = !open }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FileBadge("", size = 48.dp, icon = Icons.Rounded.Folder)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${members.size} files · ${formatSize(total)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${if (first.isIncoming) "From" else "To"} ${first.peerName()} · " + when {
                        active.isNotEmpty() -> "$done of ${members.size} done · ${(progress * 100).toInt()}%"
                        failed > 0 -> "$done sent, $failed failed"
                        else -> formatTime(first.timestamp)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active.isEmpty() && failed > 0) Ui.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (active.isNotEmpty()) { Spacer(Modifier.height(6.dp)); ThinProgress(progress) }
            }
            if (active.isNotEmpty()) SmallAction(Icons.Rounded.Close, "Cancel all") { active.forEach { viewModel.cancelTransfer(it.id) } }
            else if (failed > 0 && !first.isIncoming) {
                SmallAction(Icons.Rounded.Replay, "Retry failed") { members.filter { it.state == TransferState.FAILED }.forEach { viewModel.retryTransfer(it.id) } }
            }
            Icon(Icons.Rounded.KeyboardArrowDown, null, modifier = Modifier.rotate(if (open) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                members.sortedBy { it.timestamp }.forEach { TransferRow(it, viewModel, showDevice = false, flat = true) }
            }
        }
    }
}
