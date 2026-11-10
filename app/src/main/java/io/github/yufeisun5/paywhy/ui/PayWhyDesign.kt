package io.github.yufeisun5.paywhy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Paper {
    val Ink = Color(0xFF263D35)
    val Muted = Color(0xFF727A73)
    val Ground = Color(0xFFF5F3ED)
    val White = Color(0xFFFFFEFA)
    val Line = Color(0xFFE4E5DC)
    val Sage = Color(0xFFE7EDE3)
    val Ochre = Color(0xFF8E6C3E)
}

@Composable fun PayWhyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Paper.Ink, onPrimary = Paper.White,
            primaryContainer = Paper.Sage, onPrimaryContainer = Paper.Ink,
            secondary = Paper.Ochre, secondaryContainer = Paper.Sage,
            background = Paper.Ground, onBackground = Paper.Ink,
            surface = Paper.White, onSurface = Paper.Ink, onSurfaceVariant = Paper.Muted,
            surfaceVariant = Paper.Sage, surfaceContainer = Paper.White,
            surfaceContainerHighest = Paper.Sage, outline = Color(0xFFA4AAA0), outlineVariant = Paper.Line),
        typography = Typography(
            headlineMedium = TextStyle(fontSize = 25.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 15.sp)),
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)),
        content = content)
}

@Composable fun StatusDot(text: String, healthy: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).background(if (healthy) Paper.Ink else Paper.Ochre, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (healthy) Paper.Ink else Paper.Ochre)
    }
}

@Composable fun SectionHeading(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Paper.Muted) }
    }
}

@Composable fun SmallLabel(text: String, warning: Boolean = false) {
    Surface(color = if (warning) Color(0xFFF3ECDD) else Paper.Sage, shape = RoundedCornerShape(5.dp)) {
        Text(text, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = if (warning) Paper.Ochre else Paper.Ink,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable fun SourceMark(resource: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).background(Paper.Sage, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        // Generated artwork includes its own optical padding; the image intentionally fills the tile.
        androidx.compose.foundation.Image(painterResource(resource), contentDescription = null, modifier = Modifier.size(40.dp))
    }
}
