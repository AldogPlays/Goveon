package com.goveon.app.ui

import android.os.Build
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import com.goveon.app.lighting.AppSettings
import com.goveon.app.lighting.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF6745A0), onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDFF), onPrimaryContainer = Color(0xFF251044),
    secondary = Color(0xFF795A18), secondaryContainer = Color(0xFFFFDFA0),
    tertiary = Color(0xFF376B68), background = Color(0xFFFFF8FE),
    surface = Color(0xFFFFF8FE), surfaceContainer = Color(0xFFF1EAF4),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFD3BBFF), onPrimary = Color(0xFF38205C),
    primaryContainer = Color(0xFF4F337D), onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFF0C56F), secondaryContainer = Color(0xFF5C4208),
    tertiary = Color(0xFFA0D0CB), background = Color(0xFF141117),
    surface = Color(0xFF141117), surfaceContainer = Color(0xFF231E29),
)

@Composable
fun GoveonTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (settings.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) DarkColors else LightColors
    val view = LocalView.current
    SideEffect {
        view.context.activity()?.let { activity ->
            WindowInsetsControllerCompat(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
