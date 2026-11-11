@file:OptIn(ExperimentalFoundationApi::class)

package com.beyondzero.onspot.presentation.home

import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyondzero.onspot.domain.model.Device
import com.beyondzero.onspot.domain.model.TransferRecord
import com.beyondzero.onspot.domain.model.TransferState
import com.beyondzero.onspot.domain.model.TransferType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

// ---- formatting -------------------------------------------------------------------------

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val i = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
    val v = bytes / 1024.0.pow(i)
    return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", v, units[i])
}

fun formatTime(ts: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))

/** "Jan 2, 2026", like the dates under each collection in the design. */
fun formatDate(ts: Long): String = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ts))

fun dayLabel(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val now = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(c, now)) return "Today"
    now.add(Calendar.DAY_OF_YEAR, -1)
    if (same(c, now)) return "Yesterday"
    return SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date(ts))
}

fun formatEta(seconds: Double): String = when {
    !seconds.isFinite() || seconds < 0 -> ""
    seconds < 60 -> "${seconds.toInt() + 1}s left"
    else -> "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s left"
}

fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

/** "42% of 12.0 MB · 3.1 MB/s · 4s left" for a live transfer. */
fun progressLine(t: TransferRecord): String {
    if (t.state == TransferState.PENDING) return "Waiting · ${formatSize(t.sizeInBytes)}"
    t.retryAttempt?.let { if (t.bytes == 0L) return "Reconnecting (try ${it + 1})" }
    val pct = (t.progress * 100).toInt()
    val secs = (System.currentTimeMillis() - (if (t.startedAt > 0) t.startedAt else t.timestamp)) / 1000.0
    val speed = if (secs > 0.5 && t.bytes > 0) t.bytes / secs else 0.0
    return buildString {
        append("$pct% of ${formatSize(t.sizeInBytes)}")
        if (speed > 0) {
            append(" · ${formatSize(speed.toLong())}/s")
            val eta = formatEta((t.sizeInBytes - t.bytes) / speed)
            if (eta.isNotEmpty()) append(" · $eta")
        }
    }
}

/** The name a transfer is grouped under: the peer's name at the time, else its address. */
fun TransferRecord.peerName(): String = deviceName.ifEmpty { deviceId }

/** The local file behind a transfer, used for thumbnails (received copy, or the file we sent). */
fun TransferRecord.previewUri(): Uri? = (if (isIncoming) localUri else sourceUri)?.let { runCatching { Uri.parse(it) }.getOrNull() }

fun List<Device>.byPeer(name: String): Device? = firstOrNull { it.name == name } ?: firstOrNull { it.id == name }

// ---- palette ------------------------------------------------------------------------------

/** The design's colours: one confident blue on soft grey, with pastel cards. */
object Ui {
    val Blue = Color(0xFF2563EB)
    val BlueLight = Color(0xFF5B8DEF)
    val BlueGradient = Brush.verticalGradient(listOf(Color(0xFF6A9BF4), Color(0xFF3F73E6)))
    val Red = Color(0xFFEF4444)
    val Green = Color(0xFF22C55E)
}

@Composable
fun isDarkUi(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** The white of cards (a raised grey in dark mode). */
@Composable
fun cardColor(): Color = MaterialTheme.colorScheme.surfaceContainerLowest

data class Pastel(val bg: Color, val ink: Color, val sub: Color)

private val LightPastels = listOf(
    Pastel(Color(0xFFDCE7FB), Color(0xFF16305F), Color(0xFF52699A)),
    Pastel(Color(0xFFFBE9AE), Color(0xFF4A3700), Color(0xFF85691A)),
    Pastel(Color(0xFFF6D5D9), Color(0xFF5E1B28), Color(0xFF9B4A59)),
    Pastel(Color(0xFFDADCFB), Color(0xFF29266B), Color(0xFF5D59A3)),
    Pastel(Color(0xFFD5F0E2), Color(0xFF0D4630), Color(0xFF34775A)),
)
private val DarkPastels = listOf(
    Pastel(Color(0xFF1C2944), Color(0xFFDCE6FF), Color(0xFF93A6CF)),
    Pastel(Color(0xFF3A3216), Color(0xFFFFEDB8), Color(0xFFC9B26E)),
    Pastel(Color(0xFF3E2328), Color(0xFFFFD9DF), Color(0xFFC98C97)),
    Pastel(Color(0xFF26254A), Color(0xFFE2E1FF), Color(0xFF9F9CD9)),
    Pastel(Color(0xFF173528), Color(0xFFD3F5E4), Color(0xFF7FBE9F)),
)

@Composable
fun pastelAt(i: Int): Pastel = (if (isDarkUi()) DarkPastels else LightPastels)[Math.floorMod(i, LightPastels.size)]

/** A stable pastel per device, so a device's card always keeps its colour. */
@Composable
fun pastelFor(key: String): Pastel = pastelAt(key.hashCode())

// ---- motion -----------------------------------------------------------------------------------

/** Click that gently shrinks the element while pressed, like iOS buttons, instead of a ripple. */
fun Modifier.bounceClick(
    enabled: Boolean = true,
    scaleDown: Float = 0.96f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) scaleDown else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(interactionSource = source, indication = null, enabled = enabled, onLongClick = onLongClick, onClick = onClick)
}

// ---- file kinds -------------------------------------------------------------------------

private val KINDS = listOf(
    Regex("\\.(png|jpe?g|gif|bmp|webp|heic|svg|tiff?)$", RegexOption.IGNORE_CASE) to listOf(Color(0xFFF9A8D4), Color(0xFFDB2777)),
    Regex("\\.(mp4|mkv|mov|avi|webm|m4v|3gp)$", RegexOption.IGNORE_CASE) to listOf(Color(0xFFFDBA74), Color(0xFFEA580C)),
    Regex("\\.(mp3|wav|flac|aac|ogg|m4a|opus)$", RegexOption.IGNORE_CASE) to listOf(Color(0xFF6EE7B7), Color(0xFF059669)),
    Regex("\\.pdf$", RegexOption.IGNORE_CASE) to listOf(Color(0xFFFCA5A5), Color(0xFFDC2626)),
    Regex("\\.(docx?|xlsx?|pptx?|txt|rtf|odt|csv|md)$", RegexOption.IGNORE_CASE) to listOf(Color(0xFF93C5FD), Color(0xFF2563EB)),
    Regex("\\.(zip|rar|7z|tar|gz|bz2|xz)$", RegexOption.IGNORE_CASE) to listOf(Color(0xFFFCD34D), Color(0xFFD97706)),
    Regex("\\.(apk|exe|msi|appx|dmg)$", RegexOption.IGNORE_CASE) to listOf(Color(0xFFBEF264), Color(0xFF4D7C0F)),
    Regex("\\.(js|ts|py|java|kt|c|cpp|h|cs|go|rs|html|css|json|xml|sh)$", RegexOption.IGNORE_CASE) to listOf(Color(0xFFC4B5FD), Color(0xFF6D28D9)),
)
private val OTHER = listOf(Color(0xFFCBD5E1), Color(0xFF64748B))
private val TEXT_COLORS = listOf(Color(0xFFC4B5FD), Color(0xFF7C3AED))

fun kindColors(name: String): List<Color> = KINDS.firstOrNull { it.first.containsMatchIn(name) }?.second ?: OTHER
fun isImageName(name: String) = KINDS[0].first.containsMatchIn(name)
fun isVideoName(name: String) = KINDS[1].first.containsMatchIn(name)
fun isVisualName(name: String) = isImageName(name) || isVideoName(name)
fun extLabel(name: String) = name.substringAfterLast('.', "FILE").take(4).uppercase()

@Composable
fun FileBadge(name: String, isText: Boolean = false, size: Dp = 44.dp, icon: ImageVector? = null) {
    val colors = when {
        isText -> TEXT_COLORS
        icon != null -> listOf(Color(0xFF93C5FD), Color(0xFF2563EB))
        else -> kindColors(name)
    }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center
    ) {
        when {
            isText -> Icon(Icons.Rounded.TextFields, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
            icon != null -> Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
            else -> Text(extLabel(name), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.23f).sp, maxLines = 1)
        }
    }
}

/** A small preview of an image/video Uri (Android 10+), loaded off the main thread. */
@Composable
fun rememberThumbnail(uri: Uri?, enabled: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val state = produceState<ImageBitmap?>(null, uri, enabled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null && enabled) {
            value = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.loadThumbnail(uri, android.util.Size(480, 480), null).asImageBitmap()
                } catch (_: Exception) { null }
            }
        }
    }
    return state.value
}

/** A file badge that turns into a real thumbnail for photos and videos. */
@Composable
fun ThumbBadge(name: String, uri: Uri?, mime: String?, size: Dp, isText: Boolean = false) {
    val visual = !isText && (isVisualName(name) || mime?.startsWith("image/") == true || mime?.startsWith("video/") == true)
    val bmp = rememberThumbnail(uri, visual)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f))) {
        FileBadge(name, isText = isText, size = size)
        if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** What a transfer looks like as a photo: its thumbnail, a coloured file card, or the message. */
@Composable
fun MediaBox(t: TransferRecord, modifier: Modifier = Modifier) {
    if (t.type == TransferType.TEXT) {
        Box(modifier.background(pastelAt(3).bg).padding(10.dp)) {
            Text(t.textContent.orEmpty(), style = MaterialTheme.typography.bodySmall, color = pastelAt(3).ink, maxLines = 7, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    val bmp = rememberThumbnail(t.previewUri(), isVisualName(t.fileName) && t.state == TransferState.COMPLETED)
    Box(modifier.background(Brush.linearGradient(kindColors(t.fileName))), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.InsertDriveFile, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(26.dp))
                Text(extLabel(t.fileName), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.sp)
            }
        }
    }
}

/** A photo print: white border, soft shadow, rounded corners, optionally tilted. */
@Composable
fun PhotoPrint(
    t: TransferRecord?,
    modifier: Modifier = Modifier,
    angle: Float = 0f,
    corner: Dp = 16.dp,
    border: Dp = 3.dp,
    scene: Int = 0
) {
    val frame = if (isDarkUi()) Color(0xFF2C303A) else Color.White
    Box(
        modifier
            .rotate(angle)
            .shadow(10.dp, RoundedCornerShape(corner), ambientColor = Color(0x33000000), spotColor = Color(0x44000000))
            .background(frame, RoundedCornerShape(corner))
            .padding(border)
            .clip(RoundedCornerShape(corner - border))
    ) {
        if (t != null) MediaBox(t, Modifier.fillMaxSize()) else ScenicArt(scene, Modifier.fillMaxSize())
    }
}

/**
 * A little painted landscape (sky, sun, hills, sea) used where the design shows photos but there
 * is nothing shared yet, so empty screens still look like the reference instead of grey boxes.
 */
@Composable
fun ScenicArt(variant: Int, modifier: Modifier = Modifier) {
    val v = Math.floorMod(variant, 3)
    val sky = when (v) {
        0 -> listOf(Color(0xFF8EC5FF), Color(0xFFD9ECFF))
        1 -> listOf(Color(0xFF1E2A55), Color(0xFF4B4E8C))
        else -> listOf(Color(0xFFFFB38A), Color(0xFFFFE1C6))
    }
    val far = when (v) { 0 -> Color(0xFF7FA7D9); 1 -> Color(0xFF2A3466); else -> Color(0xFFE59A7A) }
    val near = when (v) { 0 -> Color(0xFF4FA36A); 1 -> Color(0xFF151B38); else -> Color(0xFFB8695A) }
    val sun = when (v) { 0 -> Color(0xFFFFF3B0); 1 -> Color(0xFFF5F0D0); else -> Color(0xFFFFF0C8) }
    Canvas(modifier.background(Brush.verticalGradient(sky))) {
        val w = size.width
        val h = size.height
        drawCircle(sun, radius = w * 0.11f, center = Offset(w * 0.72f, h * 0.26f))
        val back = Path().apply {
            moveTo(0f, h * 0.62f); lineTo(w * 0.28f, h * 0.38f); lineTo(w * 0.5f, h * 0.55f)
            lineTo(w * 0.72f, h * 0.36f); lineTo(w, h * 0.58f); lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(back, far)
        val front = Path().apply {
            moveTo(0f, h * 0.8f)
            cubicTo(w * 0.25f, h * 0.66f, w * 0.55f, h * 0.9f, w, h * 0.72f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(front, near)
    }
}

// ---- avatars & rings ---------------------------------------------------------------------------

/** A progress arc around an avatar while files are moving. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, stroke: Dp = 3.dp, color: Color = Ui.Blue, track: Color = Color.Transparent) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "ring")
    Canvas(modifier) {
        val w = stroke.toPx()
        val inset = w / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        val topLeft = Offset(inset, inset)
        if (track != Color.Transparent) drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(w))
        if (animated > 0f) drawArc(color, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Expanding rings: "this phone is visible / still looking". */
@Composable
fun PulseRings(modifier: Modifier = Modifier, color: Color = Ui.Blue) {
    val t = rememberInfiniteTransition(label = "pulse")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(modifier) {
        for (i in 0 until 3) {
            val p = (phase + i / 3f) % 1f
            drawCircle(color = color.copy(alpha = (1f - p) * 0.45f), radius = size.minDimension / 2 * (0.35f + 0.65f * p), style = Stroke(2.dp.toPx()))
        }
    }
}

/** Overlapping round avatars with an optional "+N", as under each collection in the design. */
@Composable
fun AvatarStack(indices: List<Int>, extra: Int = 0, size: Dp = 24.dp, ring: Color = cardColor(), trailing: (@Composable () -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(-(size / 3)), verticalAlignment = Alignment.CenterVertically) {
        indices.forEach { i ->
            Box(Modifier.size(size).clip(CircleShape).background(ring).padding(1.5.dp)) { Avatar("", i, size - 3.dp) }
        }
        if (extra > 0) {
            Box(Modifier.size(size).clip(CircleShape).background(ring).padding(1.5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Text("+$extra", fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        trailing?.invoke()
    }
}

/** A device as a round portrait with its name under it, like "People" in the design. */
@Composable
fun PersonBubble(device: Device, activity: DeviceActivity?, label: String = device.name, size: Dp = 62.dp, onClick: () -> Unit) {
    val busy = activity?.busy == true
    val ring = MaterialTheme.colorScheme.background
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(size + 18.dp).bounceClick(onClick = onClick)
    ) {
        Box(Modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
            if (busy) ProgressRing(maxOf(0.03f, activity?.progress ?: 0f), Modifier.fillMaxSize(), track = MaterialTheme.colorScheme.outlineVariant)
            Avatar(device.name, device.avatarIndex, size, Modifier.alpha(if (device.isConnected) 1f else 0.45f))
            val outcome = if (busy) null else activity?.outcome
            when {
                outcome != null -> Box(
                    Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(ring).padding(2.dp).clip(CircleShape)
                        .background(if (outcome == Outcome.SENT) Ui.Green else Ui.Red),
                    contentAlignment = Alignment.Center
                ) { Icon(if (outcome == Outcome.SENT) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
                device.isConnected -> Box(
                    Modifier.align(Alignment.BottomEnd).offset((-3).dp, (-3).dp).size(15.dp).clip(CircleShape).background(ring).padding(2.5.dp)
                        .clip(CircleShape).background(Ui.Green)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (busy) Text("${((activity?.progress ?: 0f) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Ui.Blue, fontWeight = FontWeight.SemiBold)
    }
}

// ---- controls ---------------------------------------------------------------------------------

/** The white round buttons at the top of every screen (search, filter, menu). */
@Composable
fun CircleButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, dot: Boolean = false, onClick: () -> Unit) {
    Box(modifier.size(44.dp).bounceClick(onClick = onClick)) {
        Box(
            Modifier.fillMaxSize().shadow(3.dp, CircleShape, ambientColor = Color(0x22000000), spotColor = Color(0x22000000)).background(cardColor(), CircleShape),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(21.dp)) }
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(8.dp).clip(CircleShape).background(Ui.Red))
    }
}

/** The blue pill call-to-action ("Create your first collection", "Explore memories"). */
@Composable
fun BluePill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .bounceClick(enabled = enabled, onClick = onClick)
            .shadow(if (enabled) 14.dp else 0.dp, CircleShape, ambientColor = Ui.Blue, spotColor = Ui.Blue)
            .background(Ui.BlueGradient, CircleShape)
            .padding(horizontal = 26.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A white pill with an icon, the secondary actions beside the blue pill. */
@Composable
fun GhostPill(text: String, icon: ImageVector, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.45f)
            .bounceClick(enabled = enabled, onClick = onClick)
            .shadow(2.dp, CircleShape, ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
            .background(cardColor(), CircleShape)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Ui.Blue, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A filter chip like "Natalia (20)": white, hairline border, optional avatar or icon. */
@Composable
fun FilterPill(label: String, selected: Boolean, count: Int? = null, avatarIndex: Int? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.onSurface else cardColor()
    val fg = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(12.dp), color = bg,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.bounceClick(onClick = onClick)
    ) {
        Row(
            Modifier.padding(start = if (avatarIndex != null || icon != null) 6.dp else 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                avatarIndex != null -> { Avatar("", avatarIndex, 20.dp); Spacer(Modifier.width(6.dp)) }
                icon != null -> { Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)) }
            }
            Text(if (count != null) "$label ($count)" else label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        }
    }
}

/** A section title with an optional "View all ›" link. */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Row(Modifier.bounceClick(onClick = onAction).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = Ui.Blue, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ui.Blue, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The white "+ Add memory" tile at the end of a photo row. */
@Composable
fun AddTile(label: String, tint: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .bounceClick(enabled = enabled, onClick = onClick)
            .shadow(8.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
            .background(cardColor(), RoundedCornerShape(16.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Add, null, tint = tint, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---- transfer rows ----------------------------------------------------------------------------

@Composable
fun TransferRow(t: TransferRecord, vm: HomeViewModel, showDevice: Boolean = true, modifier: Modifier = Modifier, flat: Boolean = false) {
    val isText = t.type == TransferType.TEXT
    Row(
        modifier
            .fillMaxWidth()
            .bounceClick(
                scaleDown = 0.98f,
                onLongClick = { if (!t.isActive) vm.removeRecord(t.id) },
                onClick = {
                    when {
                        isText -> t.textContent?.let { vm.copyText(it) }
                        t.isIncoming && t.state == TransferState.COMPLETED -> vm.openTransfer(t.id)
                        else -> {}
                    }
                }
            )
            .background(if (flat) MaterialTheme.colorScheme.surfaceContainerHigh else cardColor(), RoundedCornerShape(20.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThumbBadge(t.fileName, if (t.state == TransferState.COMPLETED) t.previewUri() else null, null, 48.dp, isText = isText)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (isText) t.textContent.orEmpty() else t.fileName,
                style = MaterialTheme.typography.bodyMedium, fontWeight = if (isText) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = if (isText) 2 else 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (t.isIncoming) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null,
                    tint = if (t.isIncoming) Ui.Green else Ui.Blue, modifier = Modifier.size(13.dp)
                )
                val peer = t.peerName()
                val who = if (showDevice) (if (t.isIncoming) "From $peer" else "To $peer") else (if (t.isIncoming) "Received" else "Sent")
                val detail = when (t.state) {
                    TransferState.PENDING, TransferState.TRANSFERRING -> if (isText) "Sending…" else progressLine(t)
                    TransferState.FAILED -> "Failed" + (t.error?.let { " · $it" } ?: "")
                    TransferState.CANCELLED -> "Cancelled"
                    else -> (if (isText) "" else formatSize(t.sizeInBytes) + " · ") + formatTime(t.timestamp)
                }
                Text(
                    " $who · $detail", style = MaterialTheme.typography.labelSmall,
                    color = if (t.state == TransferState.FAILED) Ui.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (t.isActive && !isText) {
                Spacer(Modifier.height(6.dp))
                if (t.state == TransferState.PENDING) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = Ui.Blue)
                else ThinProgress(t.progress)
            }
        }
        Spacer(Modifier.width(6.dp))
        RowActions(t, vm)
    }
}

@Composable
private fun RowActions(t: TransferRecord, vm: HomeViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            t.isActive && t.type == TransferType.FILE -> SmallAction(Icons.Rounded.Close, "Cancel") { vm.cancelTransfer(t.id) }
            t.type == TransferType.TEXT && t.state == TransferState.COMPLETED -> {
                SmallAction(Icons.Rounded.ContentCopy, "Copy") { t.textContent?.let { vm.copyText(it) } }
                SmallAction(Icons.Rounded.Share, "Share") { vm.shareTransfer(t.id) }
            }
            t.isIncoming && t.state == TransferState.COMPLETED && t.localUri != null -> {
                SmallAction(Icons.Rounded.OpenInNew, "Open") { vm.openTransfer(t.id) }
                SmallAction(Icons.Rounded.Share, "Share") { vm.shareTransfer(t.id) }
            }
            !t.isIncoming && t.type == TransferType.FILE && (t.state == TransferState.FAILED || t.state == TransferState.CANCELLED) && t.sourceUri != null ->
                SmallAction(Icons.Rounded.Replay, "Retry") { vm.retryTransfer(t.id) }
            else -> {}
        }
    }
}

@Composable
fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).bounceClick(onClick = onClick).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, modifier = Modifier.size(17.dp)) }
}

@Composable
fun ThinProgress(progress: Float) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
        color = Ui.Blue, trackColor = MaterialTheme.colorScheme.outlineVariant
    )
}

/** A quiet empty message inside a list. */
@Composable
fun EmptyNote(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().background(cardColor(), RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 26.dp)
    ) {
        Box(Modifier.size(52.dp).background(pastelAt(0).bg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Ui.Blue, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Three tilted photos: the "it starts with a collection" illustration from the design. */
@Composable
fun StackIllustration(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "float")
    val bob by t.animateFloat(-4f, 4f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "bob")
    Box(modifier.size(230.dp, 210.dp)) {
        PhotoPrint(null, Modifier.offset(14.dp, 14.dp).size(112.dp, 146.dp), angle = -11f, scene = 2, border = 4.dp)
        PhotoPrint(null, Modifier.offset(104.dp, 12.dp).size(112.dp, 146.dp), angle = 9f, scene = 1, border = 4.dp)
        PhotoPrint(null, Modifier.offset(56.dp, (40 + bob).dp).size(122.dp, 156.dp), angle = -2f, scene = 0, border = 4.dp)
    }
}
