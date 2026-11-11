@file:OptIn(ExperimentalMaterial3Api::class)

package com.beyondzero.onspot.presentation.home

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloatAsState
import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beyondzero.onspot.data.settings.SettingsManager
import com.beyondzero.onspot.domain.model.TransferRecord
import com.beyondzero.onspot.domain.model.TransferState
import com.beyondzero.onspot.domain.model.TransferType
import kotlinx.coroutines.launch

enum class Page(val label: String) { HOME("Home"), RECEIVED("Files"), DEVICES("Devices") }

/** Which screen is showing; drives the animated transition between screens. */
private data class Screen(val page: Page, val device: String?, val activity: Boolean)

/**
 * OnSpot's home, after the "Memories" design: soft grey canvas, pastel collection cards with
 * photo stacks, white round buttons, and a floating white navigation bar with a blue + in the middle.
 */
@Composable
fun MainScreen(viewModel: HomeViewModel, entered: Boolean = true) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val transfers by viewModel.recentTransfers.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val pendingClip by viewModel.pendingClipboardRequest.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val seenAt by viewModel.receiveSeenAt.collectAsStateWithLifecycle()
    val tray by viewModel.tray.collectAsStateWithLifecycle()

    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    var deviceKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showActivity by rememberSaveable { mutableStateOf(false) }
    var showSend by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var historyQuery by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }
    LaunchedEffect(event) { event?.let { snackbar.showSnackbar(it.second) } }
    LaunchedEffect(page, deviceKey, showActivity, transfers.size) {
        if (page == Page.RECEIVED && deviceKey == null && !showActivity) viewModel.markReceiveSeen()
    }
    LaunchedEffect(tray.isNotEmpty()) { if (tray.isNotEmpty()) showSend = true }
    BackHandler(enabled = showActivity || deviceKey != null || page != Page.HOME) {
        when {
            showActivity -> showActivity = false
            deviceKey != null -> deviceKey = null
            else -> page = Page.HOME
        }
    }

    // Entrance after the intro: the page rises into place, then the bar springs up from the bottom.
    val pageIn by animateFloatAsState(if (entered) 1f else 0f, tween(620, easing = FastOutSlowInEasing), label = "pageIn")
    val navIn by animateFloatAsState(if (entered) 1f else 0f, tween(700, delayMillis = 160, easing = EaseOutBack), label = "navIn")

    val unread = transfers.count { it.isIncoming && it.timestamp > seenAt && it.state != TransferState.FAILED }
    val moving = transfers.any { it.isActive && it.type == TransferType.FILE }
    val bottomPad = if (moving) 196.dp else 116.dp
    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }
    fun openDevice(name: String) { showActivity = false; deviceKey = name }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = Screen(page, deviceKey, showActivity),
            transitionSpec = {
                val deeper = (targetState.device != null && initialState.device == null) || (targetState.activity && !initialState.activity)
                (fadeIn(tween(240)) + slideInHorizontally(tween(260)) { w -> if (deeper) w / 6 else -w / 12 }) togetherWith fadeOut(tween(140))
            },
            label = "screens",
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = pageIn
                translationY = (1f - pageIn) * 48.dp.toPx()
                val sc = 0.96f + 0.04f * pageIn
                scaleX = sc; scaleY = sc
            }
        ) { s ->
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                when {
                    s.activity -> {
                        TitleBar("Activity", onBack = { showActivity = false })
                        ActivityPage(viewModel, transfers, devices, historyQuery, { historyQuery = it }, bottomPad)
                    }
                    s.device != null -> DevicePage(viewModel, s.device, devices, transfers, identity.second, bottomPad, onBack = { deviceKey = null }, toast = ::toast)
                    s.page == Page.HOME -> {
                        HomeBar(identity.first, identity.second, onProfile = { showSettings = true }, onSearch = { showActivity = true }, onRefresh = { viewModel.rescan(); toast("Looking for devices…") })
                        HomePage(viewModel, devices, transfers, bottomPad, onOpenDevice = ::openDevice, toast = ::toast)
                    }
                    s.page == Page.RECEIVED -> {
                        TitleBar("Files", action = Icons.Rounded.History, actionLabel = "Activity", onAction = { showActivity = true })
                        ReceivedPage(viewModel, transfers, devices, bottomPad, onOpenDevice = ::openDevice)
                    }
                    else -> {
                        TitleBar("Devices", action = Icons.Rounded.Refresh, actionLabel = "Scan again", onAction = { viewModel.rescan(); toast("Looking for devices…") })
                        DevicesPage(viewModel, devices, transfers, bottomPad, onOpenDevice = ::openDevice, onSettings = { showSettings = true }, toast = ::toast)
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 10.dp)
                .graphicsLayer {
                    alpha = navIn.coerceIn(0f, 1f)
                    translationY = (1f - navIn) * 140.dp.toPx()
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TransferDock(transfers, Modifier.padding(bottom = 10.dp)) {
                val active = transfers.filter { it.isActive }
                val peers = active.map { it.peerName() }.distinct()
                if (peers.size == 1) openDevice(peers[0]) else { deviceKey = null; showActivity = true; historyQuery = "" }
            }
            BottomNav(
                page = page, onDevice = deviceKey != null || showActivity, unread = unread, avatarIndex = identity.second,
                onPage = { showActivity = false; deviceKey = null; page = it },
                onFab = { showSend = true },
                onAccount = { showSettings = true }
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
    }

    if (showSend) SendSheet(viewModel, ::toast) { showSend = false }
    if (showSettings) SettingsSheet(viewModel) { showSettings = false }
    pendingClip?.let { d ->
        AlertDialog(
            onDismissRequest = { viewModel.respondToClipboardRequest(d.id, false) },
            icon = { Icon(Icons.Rounded.ContentPaste, null, tint = Ui.Blue) },
            title = { Text("Sync clipboards?") },
            text = { Text("${d.name} wants to sync clipboards. Text you copy on either device will appear on the other.") },
            confirmButton = { TextButton(onClick = { viewModel.respondToClipboardRequest(d.id, true) }) { Text("Allow", color = Ui.Blue, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { viewModel.respondToClipboardRequest(d.id, false) }) { Text("Decline") } },
            containerColor = cardColor(), shape = RoundedCornerShape(28.dp)
        )
    }
}

// ---- top bars ---------------------------------------------------------------------------------------

/** Home's bar: your avatar on the left, white round search and refresh buttons on the right. */
@Composable
private fun HomeBar(name: String, avatar: Int, onProfile: () -> Unit, onSearch: () -> Unit, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).bounceClick(onClick = onProfile).shadow(3.dp, CircleShape).background(cardColor(), CircleShape).padding(2.dp)) {
            Avatar(name, avatar, 40.dp)
        }
        Spacer(Modifier.weight(1f))
        CircleButton(Icons.Rounded.Search, "Search", onClick = onSearch)
        Spacer(Modifier.width(10.dp))
        CircleButton(Icons.Rounded.Refresh, "Scan again", onClick = onRefresh)
    }
}

/** A large left-aligned title ("Memories" in the design), optional back arrow and one round button. */
@Composable
fun TitleBar(
    title: String,
    onBack: (() -> Unit)? = null,
    action: ImageVector? = null,
    actionLabel: String = "",
    onAction: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 8.dp else 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(Modifier.size(44.dp).bounceClick(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        if (action != null && onAction != null) {
            CircleButton(action, actionLabel, onClick = onAction)
        }
    }
}

// ---- bottom navigation --------------------------------------------------------------------------------

/** The white floating bar from the design, with a raised blue + in the middle. */
@Composable
private fun BottomNav(
    page: Page, onDevice: Boolean, unread: Int, avatarIndex: Int,
    onPage: (Page) -> Unit, onFab: () -> Unit, onAccount: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(30.dp), ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
            .background(cardColor(), RoundedCornerShape(30.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavSlot(Icons.Rounded.Home, Icons.Outlined.Home, "Home", page == Page.HOME && !onDevice, Modifier.weight(1f)) { onPage(Page.HOME) }
        NavSlot(Icons.Rounded.Folder, Icons.Outlined.Folder, "Files", page == Page.RECEIVED && !onDevice, Modifier.weight(1f), dot = unread > 0) { onPage(Page.RECEIVED) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(54.dp)
                    .bounceClick(scaleDown = 0.9f) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onFab() }
                    .shadow(12.dp, CircleShape, ambientColor = Ui.Blue, spotColor = Ui.Blue)
                    .background(Ui.BlueGradient, CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Add, "Send", tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
        NavSlot(Icons.Rounded.Devices, Icons.Outlined.Devices, "Devices", page == Page.DEVICES && !onDevice, Modifier.weight(1f)) { onPage(Page.DEVICES) }
        Column(Modifier.weight(1f).bounceClick(onClick = onAccount), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar("", avatarIndex, 24.dp)
            Spacer(Modifier.height(4.dp))
            Text("Account", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun NavSlot(filled: ImageVector, outlined: ImageVector, label: String, selected: Boolean, modifier: Modifier, dot: Boolean = false, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.bounceClick(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Icon(if (selected) filled else outlined, label, tint = color, modifier = Modifier.size(24.dp))
            if (dot) Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(Ui.Red))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

/** Everything moving right now, floating above the navigation bar. */
@Composable
private fun TransferDock(transfers: List<TransferRecord>, modifier: Modifier, onView: () -> Unit) {
    val active = transfers.filter { it.isActive && it.type == TransferType.FILE }
    AnimatedVisibility(
        visible = active.isNotEmpty(), modifier = modifier,
        enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()
    ) {
        val total = active.sumOf { it.sizeInBytes }
        val done = active.sumOf { it.bytes }
        val progress = if (total > 0) done.toFloat() / total else 0f
        val ins = active.count { it.isIncoming }
        val peers = active.map { it.peerName() }.distinct()
        val title = when {
            ins > 0 && ins < active.size -> "${active.size} transfers in progress"
            else -> "${if (ins > 0) "Receiving" else "Sending"} ${plural(active.size, "file")} ${if (ins > 0) "from" else "to"} " +
                (if (peers.size > 1) "${peers.size} devices" else peers.firstOrNull().orEmpty())
        }
        Row(
            Modifier
                .fillMaxWidth()
                .bounceClick(scaleDown = 0.98f, onClick = onView)
                .shadow(14.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
                .background(cardColor(), RoundedCornerShape(24.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                ProgressRing(maxOf(progress, 0.02f), Modifier.fillMaxSize(), track = MaterialTheme.colorScheme.outlineVariant)
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatSize(done)} of ${formatSize(total)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("View", color = Ui.Blue, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp))
        }
    }
}

// ---- send sheet ------------------------------------------------------------------------------------

@Composable
private fun SendSheet(viewModel: HomeViewModel, toast: (String) -> Unit, onDismiss: () -> Unit) {
    val tray by viewModel.tray.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var showText by remember { mutableStateOf(false) }
    var directTarget by rememberSaveable { mutableStateOf<String?>(null) }

    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris: List<Uri> -> viewModel.addUris(uris) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> viewModel.addUris(uris) }
    val direct = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val target = directTarget
        directTarget = null
        if (target != null && uris.isNotEmpty()) { viewModel.sendFiles(uris, target); onDismiss() }
    }

    val fileItems = tray.filter { !it.isText }
    val textCount = tray.count { it.isText }
    val summary = buildList {
        if (fileItems.isNotEmpty()) add("${plural(fileItems.size, "file")} · ${formatSize(fileItems.sumOf { it.size })}")
        if (textCount > 0) add(plural(textCount, "message"))
    }.joinToString(" + ").ifEmpty { "Pick photos, files or text, then a device" }
    val nearby = devices.filter { it.isConnected }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (tray.isEmpty()) "New share" else "Ready to send", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tray.isNotEmpty()) TextButton(onClick = viewModel::clearTray) { Text("Clear", color = Ui.Blue) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickTile(Icons.Rounded.PhotoLibrary, "Photos", 2, Modifier.weight(1f)) { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                PickTile(Icons.Rounded.Description, "Files", 0, Modifier.weight(1f)) { files.launch(arrayOf("*/*")) }
                PickTile(Icons.Rounded.TextFields, "Text", 3, Modifier.weight(1f)) { showText = true }
                PickTile(Icons.Rounded.ContentPaste, "Paste", 1, Modifier.weight(1f)) {
                    val text = clipboard.getText()?.text
                    if (text.isNullOrBlank()) toast("The clipboard is empty") else viewModel.addText(text, "Clipboard")
                }
            }
            if (tray.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tray, key = { it.key }) { item -> TrayChip(item) { viewModel.removeFromTray(item.key) } }
                }
            }
            SectionTitle("Send to")
            if (nearby.isEmpty()) {
                EmptyNote(Icons.Rounded.Devices, "No devices nearby yet", "Open OnSpot on your PC or another phone on the same Wi‑Fi.")
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(nearby, key = { it.id }) { d ->
                        PersonBubble(d, activity[d.id]) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (tray.isEmpty()) { directTarget = d.id; direct.launch(arrayOf("*/*")) }
                            else {
                                val n = tray.size
                                viewModel.sendTrayTo(d.id)
                                viewModel.clearTray()
                                toast("Sending ${plural(n, "item")} to ${d.name}")
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
    if (showText) TextEntryDialog(onDismiss = { showText = false }) { viewModel.addText(it) }
}

/** A white square with a pastel icon, for picking what to send. */
@Composable
private fun PickTile(icon: ImageVector, label: String, tint: Int, modifier: Modifier, onClick: () -> Unit) {
    val p = pastelAt(tint)
    Column(
        modifier.aspectRatio(0.9f).bounceClick(onClick = onClick).background(cardColor(), RoundedCornerShape(22.dp)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(42.dp).background(p.bg, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = p.ink, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ---- settings sheet --------------------------------------------------------------------------------

@Composable
private fun SettingsSheet(viewModel: HomeViewModel, onDismiss: () -> Unit) {
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val mode by viewModel.connectionMode.collectAsStateWithLifecycle()
    val nearbyOnly by viewModel.receiveNearbyOnly.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(identity.first) }
    var avatar by remember { mutableIntStateOf(identity.second) }
    var editMode by remember { mutableStateOf(mode) }
    var editNearby by remember { mutableStateOf(nearbyOnly) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                    PulseRings(Modifier.fillMaxSize())
                    Box(Modifier.shadow(8.dp, CircleShape).background(cardColor(), CircleShape).padding(3.dp)) { Avatar(name, avatar, 78.dp) }
                }
                Spacer(Modifier.height(8.dp))
                Text(name.ifBlank { "Your device" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("How other devices see this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    label = { Text("Device name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ui.Blue, focusedLabelColor = Ui.Blue)
                )
                Text("Picture", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..11).chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { i ->
                                val sel = i == avatar
                                Box(
                                    Modifier.weight(1f).aspectRatio(1f).bounceClick(scaleDown = 0.9f) { avatar = i }
                                        .border(3.dp, if (sel) Ui.Blue else Color.Transparent, CircleShape)
                                        .padding(if (sel) 5.dp else 2.dp)
                                ) { Avatar("", i, 44.dp, Modifier.fillMaxSize()) }
                            }
                            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Card {
                Text("Connection", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeTile("Wi‑Fi", "Same Wi‑Fi network", editMode == SettingsManager.MODE_WIFI, Modifier.weight(1f)) { editMode = SettingsManager.MODE_WIFI }
                    ModeTile("Hotspot", "One device shares it", editMode == SettingsManager.MODE_HOTSPOT, Modifier.weight(1f)) { editMode = SettingsManager.MODE_HOTSPOT }
                }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Only accept from nearby devices", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Block files from devices that aren't in your list", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = editNearby, onCheckedChange = { editNearby = it }, colors = SwitchDefaults.colors(checkedTrackColor = Ui.Blue))
                }
            }
            BluePill("Save", Modifier.fillMaxWidth()) {
                viewModel.setIdentity(name, avatar)
                viewModel.setConnectionMode(editMode)
                viewModel.setReceiveNearbyOnly(editNearby)
                onDismiss()
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(cardColor(), RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) { content() }
}

@Composable
private fun ModeTile(title: String, body: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .bounceClick(onClick = onClick)
            .background(if (selected) pastelAt(0).bg else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
            .border(2.dp, if (selected) Ui.Blue else Color.Transparent, RoundedCornerShape(18.dp))
            .padding(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Type a message" dialog, used by the send sheet and the device page. */
@Composable
fun TextEntryDialog(title: String = "Add text", confirm: String = "Add", onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, minLines = 3, maxLines = 8,
                placeholder = { Text("Type a message or paste a link…") },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ui.Blue)
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text); onDismiss() }, enabled = text.isNotBlank()) { Text(confirm, color = Ui.Blue, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = cardColor(), shape = RoundedCornerShape(28.dp)
    )
}

/** One item waiting in the send sheet. */
@Composable
fun TrayChip(item: TrayItem, onRemove: () -> Unit) {
    Row(
        Modifier.width(216.dp).background(cardColor(), RoundedCornerShape(18.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThumbBadge(item.name, item.uri, item.mime, 42.dp, isText = item.isText)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (item.isText) item.text.orEmpty().replace(Regex("\\s+"), " ") else item.name,
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (item.isText) "${item.label ?: "Message"} · ${item.text.orEmpty().length} chars" else formatSize(item.size),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
            )
        }
        SmallAction(Icons.Rounded.Close, "Remove", onRemove)
    }
}
