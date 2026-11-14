package com.jarvis.agent.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

// Brand palette: deep navy night with violet → cyan accents.
val JarvisViolet = Color(0xFF7C5CFF)
val JarvisCyan = Color(0xFF22D3EE)
val JarvisBg = Color(0xFF090E1C)
val JarvisBg2 = Color(0xFF111A33)
val JarvisSurface = Color(0xFF151E37)
val JarvisSurface2 = Color(0xFF1D2846)
val JarvisBorder = Color(0xFF27324F)
val JarvisText = Color(0xFFEAF0FF)
val JarvisMuted = Color(0xFF8E9AC0)
val JarvisDanger = Color(0xFFEF4444)
val JarvisOk = Color(0xFF22C55E)
val JarvisWarn = Color(0xFFF59E0B)

val BrandGradient = Brush.linearGradient(listOf(JarvisViolet, JarvisCyan))

private val scheme = darkColorScheme(
    primary = JarvisViolet,
    onPrimary = Color.White,
    secondary = JarvisCyan,
    onSecondary = Color(0xFF00242B),
    background = JarvisBg,
    surface = JarvisSurface,
    surfaceVariant = JarvisSurface2,
    surfaceContainer = JarvisSurface,
    onBackground = JarvisText,
    onSurface = JarvisText,
    onSurfaceVariant = JarvisMuted,
    outline = JarvisBorder,
    error = JarvisDanger,
)

private val type = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

private val shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(JarvisBg, JarvisBg2))),
        ) { content() }
    }
}
