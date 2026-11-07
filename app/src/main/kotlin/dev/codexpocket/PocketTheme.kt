package dev.codexpocket

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

internal val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val Mint: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Panel: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable internal fun PocketTheme(mode: String, content: @Composable () -> Unit) {
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    val colors = if (dark) darkColorScheme(primary = Color(0xff7edcbd), onPrimary = Color(0xff00382b),
        primaryContainer = Color(0xff164e40), onPrimaryContainer = Color(0xffb4f4d9), secondaryContainer = Color(0xff274b41), onSecondaryContainer = Color(0xffb4f4d9),
        background = Color(0xff101719), surface = Color(0xff101719), onSurface = Color(0xffe2ece9), onBackground = Color(0xffe2ece9),
        surfaceContainer = Color(0xff243033), surfaceContainerLow = Color(0xff1a2326), surfaceContainerHigh = Color(0xff283336), surfaceContainerHighest = Color(0xff303c3f),
        secondary = Color(0xff9fbcb3), tertiary = Color(0xffcbbca1), error = Color(0xffffb4ab), errorContainer = Color(0xff4c2525), onErrorContainer = Color(0xffffdad6), onSurfaceVariant = Color(0xffaabbb5), outlineVariant = Color(0xff526660))
    else lightColorScheme(primary = Color(0xff087c68), onPrimary = Color.White, primaryContainer = Color(0xffdcf3e9), onPrimaryContainer = Color(0xff0d4438),
        secondaryContainer = Color(0xffdcf3e9), onSecondaryContainer = Color(0xff087c68), background = Color(0xfff5f6f8), surface = Color(0xfff5f6f8), onSurface = Color(0xff142a2c), onBackground = Color(0xff142a2c),
        surfaceContainer = Color(0xffe9edf0), surfaceContainerLow = Color.White, surfaceContainerHigh = Color(0xffe9edf0), surfaceContainerHighest = Color(0xffe2e8e8),
        secondary = Color(0xff45675d), tertiary = Color(0xff735f3e), error = Color(0xffb3261e), errorContainer = Color(0xffffe9e5), onErrorContainer = Color(0xff6d2019), onSurfaceVariant = Color(0xff58665f), outlineVariant = Color(0xffd5dfda))
    MaterialTheme(colorScheme = colors, typography = Typography(
        titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp), bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp), labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)),
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)), content = content)
}
