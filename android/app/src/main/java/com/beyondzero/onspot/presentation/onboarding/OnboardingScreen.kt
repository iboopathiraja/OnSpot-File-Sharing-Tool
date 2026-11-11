package com.beyondzero.onspot.presentation.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beyondzero.onspot.data.settings.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    settingsManager: SettingsManager,
    onComplete: () -> Unit
) {
    var deviceName by remember { mutableStateOf(settingsManager.deviceName) }
    var selectedAvatarIndex by remember { mutableIntStateOf(settingsManager.avatarIndex) }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Live Animated Video Guide Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFB09DC7), Color.Black)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                LiveAnimationGuide()
            }

            // Input Section
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)),
                color = Color.Black
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Welcome to OnSpot",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // How to use guide
                    Surface(
                        color = Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "How to connect:", 
                                color = Color.White, 
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = Color(0xFFB09DC7).copy(alpha = 0.2f), modifier = Modifier.size(28.dp)) {
                                    Text("1", color = Color(0xFFB09DC7), fontWeight = FontWeight.Bold, modifier = Modifier.wrapContentSize(Alignment.Center))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Join the same Wi‑Fi (or hotspot) on both devices", color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = Color(0xFFB09DC7).copy(alpha = 0.2f), modifier = Modifier.size(28.dp)) {
                                    Text("2", color = Color(0xFFB09DC7), fontWeight = FontWeight.Bold, modifier = Modifier.wrapContentSize(Alignment.Center))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Open OnSpot on both — they find each other automatically", color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Choose your avatar",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        items(11) { index ->
                            val avatarIdx = index + 1
                            val resId = context.resources.getIdentifier(
                                "avatar_$avatarIdx",
                                "drawable",
                                context.packageName
                            )
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(if (selectedAvatarIndex == avatarIdx) Color(0xFFB09DC7) else Color.DarkGray)
                                    .border(
                                        width = if (selectedAvatarIndex == avatarIdx) 3.dp else 0.dp,
                                        color = if (selectedAvatarIndex == avatarIdx) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedAvatarIndex = avatarIdx },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = resId),
                                    contentDescription = "Avatar $avatarIdx",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(if (selectedAvatarIndex == avatarIdx) 4.dp else 0.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text("Your Device Name", color = Color.Gray) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Devices, contentDescription = null, tint = Color.Gray)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFB09DC7),
                            unfocusedBorderColor = Color.DarkGray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFFB09DC7)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = {
                            if (deviceName.isNotBlank()) {
                                settingsManager.deviceName = deviceName.trim()
                                settingsManager.avatarIndex = selectedAvatarIndex
                                settingsManager.hasCompletedOnboarding = true
                                onComplete()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFB09DC7),
                            contentColor = Color.Black
                        ),
                        shape = CircleShape
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Get Started", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun LiveAnimationGuide() {
    val infiniteTransition = rememberInfiniteTransition(label = "live_guide")
    val transferProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "transfer"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )

    val dashPathEffect = remember { androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)) }
    val path = remember { Path() }

    Canvas(modifier = Modifier.size(300.dp)) {
        val phoneWidth = 60.dp.toPx()
        val phoneHeight = 120.dp.toPx()
        val cornerRadius = CornerRadius(12.dp.toPx())
        
        val leftPhoneX = size.width / 2 - 100.dp.toPx() - phoneWidth / 2
        val rightPhoneX = size.width / 2 + 100.dp.toPx() - phoneWidth / 2
        val phoneY = size.height / 2 - phoneHeight / 2

        // Draw Left Phone (Sender)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.2f),
            topLeft = Offset(leftPhoneX, phoneY),
            size = Size(phoneWidth, phoneHeight),
            cornerRadius = cornerRadius,
            style = Stroke(width = 3.dp.toPx())
        )
        // Phone screen
        drawRoundRect(
            color = Color(0xFFB09DC7).copy(alpha = 0.5f),
            topLeft = Offset(leftPhoneX + 4.dp.toPx(), phoneY + 4.dp.toPx()),
            size = Size(phoneWidth - 8.dp.toPx(), phoneHeight - 8.dp.toPx()),
            cornerRadius = CornerRadius(8.dp.toPx())
        )

        // Draw Right Phone (Receiver)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.2f),
            topLeft = Offset(rightPhoneX, phoneY),
            size = Size(phoneWidth, phoneHeight),
            cornerRadius = cornerRadius,
            style = Stroke(width = 3.dp.toPx())
        )
        // Phone screen
        drawRoundRect(
            color = Color(0xFF8CA5B7).copy(alpha = 0.5f),
            topLeft = Offset(rightPhoneX + 4.dp.toPx(), phoneY + 4.dp.toPx()),
            size = Size(phoneWidth - 8.dp.toPx(), phoneHeight - 8.dp.toPx()),
            cornerRadius = CornerRadius(8.dp.toPx())
        )

        // Signal waves
        drawCircle(
            color = Color(0xFF25D366).copy(alpha = pulseAlpha),
            radius = 30.dp.toPx() + (20.dp.toPx() * (1f - pulseAlpha)),
            center = Offset(leftPhoneX + phoneWidth / 2, phoneY + phoneHeight / 2),
            style = Stroke(width = 2.dp.toPx())
        )

        // Transferring File (Document Icon)
        val startX = leftPhoneX + phoneWidth
        val endX = rightPhoneX
        val currentX = startX + (endX - startX) * transferProgress
        
        // Arc path
        path.reset()
        path.moveTo(startX, phoneY + phoneHeight / 2)
        path.quadraticTo(
            size.width / 2, phoneY - 40.dp.toPx(),
            endX, phoneY + phoneHeight / 2
        )
        
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.3f),
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = dashPathEffect
            )
        )

        val yPos = phoneY + phoneHeight / 2 - (40.dp.toPx() * Math.sin(transferProgress * Math.PI).toFloat())
        
        // Document
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(currentX - 12.dp.toPx(), yPos - 16.dp.toPx()),
            size = Size(24.dp.toPx(), 32.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx())
        )
        // Inner lines of document
        drawLine(
            color = Color.Gray,
            start = Offset(currentX - 6.dp.toPx(), yPos - 8.dp.toPx()),
            end = Offset(currentX + 6.dp.toPx(), yPos - 8.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.Gray,
            start = Offset(currentX - 6.dp.toPx(), yPos),
            end = Offset(currentX + 6.dp.toPx(), yPos),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.Gray,
            start = Offset(currentX - 6.dp.toPx(), yPos + 8.dp.toPx()),
            end = Offset(currentX, yPos + 8.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}
