package com.beyondzero.onspot.presentation.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue

@Composable
fun Avatar(
    name: String,
    avatarIndex: Int,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resId = remember(avatarIndex) {
        context.resources.getIdentifier(
            "avatar_$avatarIndex",
            "drawable",
            context.packageName
        )
    }

    Surface(
        shape = CircleShape,
        color = Color.DarkGray,
        modifier = modifier.size(size)
    ) {
        if (resId != 0) {
            Image(
                painter = painterResource(id = resId),
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            val colors = listOf(
                Color(0xFFEF5350), Color(0xFFAB47BC), Color(0xFF7E57C2),
                Color(0xFF5C6BC0), Color(0xFF29B6F6), Color(0xFF26A69A),
                Color(0xFF66BB6A), Color(0xFFFFA726), Color(0xFFFF7043)
            )
            val colorIndex = (name.hashCode().absoluteValue) % colors.size
            val bgColor = colors[colorIndex]
            val initial = name.take(1).uppercase()
            
            Box(
                modifier = Modifier.background(bgColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.4).sp
                )
            }
        }
    }
}
