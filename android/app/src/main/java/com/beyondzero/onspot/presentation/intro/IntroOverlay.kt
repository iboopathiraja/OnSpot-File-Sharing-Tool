package com.beyondzero.onspot.presentation.intro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beyondzero.onspot.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private val Blue = Color(0xFF2563EB)
private val Dots = listOf(Color(0xFF2563EB), Color(0xFF22C55E), Color(0xFFF59E0B), Color(0xFFD6456B))

/**
 * The opening animation, played once each time the app starts (about 1.8 s, tap to skip):
 *  1. the app icon springs in with a small twist,
 *  2. three ripples spread out from it while coloured dots fly in and settle around it,
 *  3. "OnSpot" rises letter by letter with the tagline under it,
 *  4. everything lifts and fades away while the home screen slides in underneath ([onReveal]).
 */
@Composable
fun IntroOverlay(onReveal: () -> Unit, onFinished: () -> Unit) {
    val logo = remember { Animatable(0f) }
    val ripple = remember { Animatable(0f) }
    val dots = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var revealed by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun reveal() { if (!revealed) { revealed = true; onReveal() } }
    fun finish() { if (!finished) { finished = true; onFinished() } }

    LaunchedEffect(Unit) {
        launch { logo.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f)) }
        delay(160)
        launch { ripple.animateTo(1f, tween(1300, easing = LinearOutSlowInEasing)) }
        launch { dots.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 120f)) }
        delay(260)
        text.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
        delay(380)
        reveal()
        exit.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        finish()
    }

    val bg = MaterialTheme.colorScheme.background
    // The icon PNG is large (1254 px) and lives in drawable/, so Android would scale it up to
    // several thousand pixels on sharp screens and crash. Decode a small copy instead.
    val context = LocalContext.current
    val icon = remember {
        try {
            val opts = BitmapFactory.Options().apply { inScaled = false; inSampleSize = 4 }
            BitmapFactory.decodeResource(context.resources, R.drawable.app_icon, opts)?.asImageBitmap()
        } catch (_: Throwable) { null }
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(bg)
            .pointerInput(Unit) {
                detectTapGestures {
                    scope.launch {
                        reveal()
                        exit.animateTo(1f, tween(260))
                        finish()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Ripples and the dots that fly in to orbit the icon.
        Canvas(Modifier.size(340.dp).graphicsLayer { translationY = -36.dp.toPx() - exit.value * 60.dp.toPx() }) {
            val c = center
            val start = 56.dp.toPx()
            val end = size.minDimension / 2
            for (i in 0 until 3) {
                val p = (ripple.value * 1.4f - i * 0.2f).coerceIn(0f, 1f)
                if (p > 0f && p < 1f) {
                    drawCircle(Blue.copy(alpha = (1f - p) * 0.35f), start + (end - start) * p, c, style = Stroke((2.5f - p * 1.5f).dp.toPx()))
                }
            }
            val d = dots.value
            Dots.forEachIndexed { i, color ->
                val angle = Math.toRadians(-60.0 + i * 90.0 + (1f - d) * 140.0)
                val r = (end * 1.6f) * (1f - d) + 96.dp.toPx() * d
                val pos = androidx.compose.ui.geometry.Offset(c.x + (r * cos(angle)).toFloat(), c.y + (r * sin(angle)).toFloat())
                drawCircle(color.copy(alpha = d.coerceIn(0f, 1f)), (5f + i % 2 * 2f).dp.toPx(), pos)
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                translationY = -exit.value * 60.dp.toPx()
                val s = 1f + exit.value * 0.08f
                scaleX = s; scaleY = s
            }
        ) {
            Box(
                Modifier
                    .size(108.dp)
                    .graphicsLayer {
                        val v = logo.value
                        scaleX = v; scaleY = v
                        alpha = v.coerceIn(0f, 1f)
                        rotationZ = (1f - v) * -24f
                    }
                    .shadow(22.dp, RoundedCornerShape(32.dp), ambientColor = Blue.copy(alpha = 0.35f), spotColor = Blue.copy(alpha = 0.45f))
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.White)
            ) {
                if (icon != null) {
                    Image(icon, contentDescription = "OnSpot", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(Blue), contentAlignment = Alignment.Center) {
                        Text("O", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Row {
                "OnSpot".forEachIndexed { i, ch ->
                    val p = (text.value * 1.7f - i * 0.12f).coerceIn(0f, 1f)
                    Text(
                        ch.toString(),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (i < 2) Blue else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.graphicsLayer {
                            alpha = p
                            translationY = (1f - p) * 22.dp.toPx()
                        }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Share between your devices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer {
                    val p = ((text.value - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    alpha = p
                    translationY = (1f - p) * 10.dp.toPx()
                }
            )
        }

        // A thin loading line along the bottom that fills while the intro plays.
        Box(
            Modifier.align(Alignment.BottomCenter).offset(y = (-72).dp).size(width = 64.dp, height = 4.dp)
                .graphicsLayer { alpha = text.value * (1f - exit.value) }
                .background(Blue.copy(alpha = 0.15f), CircleShape)
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = ripple.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                }.background(Blue, CircleShape)
            )
        }
    }
}
