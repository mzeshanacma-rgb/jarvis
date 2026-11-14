package com.jarvis.agent.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Light, professional palette in the style of WhatsApp / Facebook:
// soft grey page, white cards, near-black text, blue primary, WhatsApp-green accent.
val JarvisPrimary = Color(0xFF1877F2)   // buttons, selection, links
val JarvisAccent = Color(0xFF00A884)    // positive / listening / "free" accents
val JarvisBg = Color(0xFFF0F2F5)        // page background
val JarvisBg2 = Color(0xFFF0F2F5)
val JarvisSurface = Color(0xFFFFFFFF)   // cards
val JarvisSurface2 = Color(0xFFE7F0FD)  // selected / tinted surfaces
val JarvisBorder = Color(0xFFE4E6EB)
val JarvisText = Color(0xFF050505)
val JarvisMuted = Color(0xFF65676B)
val JarvisDanger = Color(0xFFE41E3F)
val JarvisOk = Color(0xFF00A884)
val JarvisWarn = Color(0xFFB7791F)
val JarvisUserBubble = Color(0xFFD9FDD3) // WhatsApp-style outgoing bubble

val BrandGradient = Brush.linearGradient(listOf(JarvisPrimary, JarvisAccent))

private val scheme = lightColorScheme(
    primary = JarvisPrimary,
    onPrimary = Color.White,
    primaryContainer = JarvisSurface2,
    onPrimaryContainer = Color(0xFF0B3D91),
    secondary = JarvisAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7F5EC),
    onSecondaryContainer = Color(0xFF00513F),
    background = JarvisBg,
    onBackground = JarvisText,
    surface = JarvisSurface,
    onSurface = JarvisText,
    surfaceVariant = Color(0xFFF5F6F7),
    onSurfaceVariant = JarvisMuted,
    surfaceContainer = JarvisSurface,
    surfaceContainerHigh = Color(0xFFF7F8FA),
    outline = Color(0xFFCED0D4),
    outlineVariant = JarvisBorder,
    error = JarvisDanger,
)

private val type = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = JarvisText),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, color = JarvisText),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = JarvisText),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes) {
        CompositionLocalProvider(LocalContentColor provides JarvisText) {
            Box(Modifier.fillMaxSize().background(JarvisBg)) { content() }
        }
    }
}
