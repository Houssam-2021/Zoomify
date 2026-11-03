package com.zoomify.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Velora's start card, shown until camera permission is granted. */
@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFC212230), Color(0xFF030407))))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(97.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Accent.copy(alpha = 0.12f))
                .border(1.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(32.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("◉", color = Accent, fontSize = 40.sp) }
        Spacer(Modifier.height(22.dp))
        Text("MOTION, REIMAGINED", color = Accent, fontSize = 11.sp, letterSpacing = 3.3.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Zoom into\nthe moment.", color = Color.White, fontSize = 40.sp, lineHeight = 44.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Up to 50× zoom with fluid motion blur, natural color science, Log and LUTs, and 4K/30 high-bitrate video.",
            color = Color.White.copy(alpha = 0.58f), fontSize = 14.sp, lineHeight = 24.sp, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        Spacer(Modifier.height(30.dp))
        Box(
            Modifier
                .widthIn(min = 240.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(Color(0xFFF3F3F5))
                .clickable(onClick = onRequest)
                .padding(horizontal = 21.dp, vertical = 17.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Enable camera →", color = Color(0xFF171719), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
    }
}
