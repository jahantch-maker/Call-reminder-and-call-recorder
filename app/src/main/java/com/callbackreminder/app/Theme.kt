package com.callbackreminder.app

import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings › Appearance: System / Light / Dark, the accent colour (or the wallpaper's colours on Android 12+),
 * and Pure black for dark mode. Saved in the app settings; [version] bumps so the theme redraws at once.
 */
object Appearance {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    /** Index into [ACCENTS], or [WALLPAPER]. */
    const val WALLPAPER = -1

    private const val KEY_MODE = "app_theme_mode"
    private const val KEY_ACCENT = "app_accent"
    private const val KEY_BLACK = "app_pure_black"

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun mode(c: Context): Int = AppSettings.getString(c, KEY_MODE).toIntOrNull()?.takeIf { it in SYSTEM..DARK } ?: SYSTEM
    fun setMode(c: Context, m: Int) { AppSettings.setString(c, KEY_MODE, m.toString()); _version.value++ }

    fun accent(c: Context): Int {
        val a = AppSettings.getString(c, KEY_ACCENT).toIntOrNull() ?: 0
        return if (a == WALLPAPER && !wallpaperSupported()) 0 else a.takeIf { it == WALLPAPER || it in ACCENTS.indices } ?: 0
    }
    fun setAccent(c: Context, a: Int) { AppSettings.setString(c, KEY_ACCENT, a.toString()); _version.value++ }

    fun pureBlack(c: Context) = AppSettings.get(c, KEY_BLACK, false)
    fun setPureBlack(c: Context, on: Boolean) { AppSettings.set(c, KEY_BLACK, on); _version.value++ }

    fun wallpaperSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Display size, this app only: Extra small · Small · Default · Large (Large kept modest so nothing is cut). */
    val SIZES = listOf("Extra small" to 0.80f, "Small" to 0.90f, "Default" to 1.0f, "Large" to 1.10f)
    const val SIZE_DEFAULT = 1
    private const val KEY_SIZE = "app_display_size"
    fun displaySize(c: Context): Int = AppSettings.getString(c, KEY_SIZE).toIntOrNull()?.takeIf { it in SIZES.indices } ?: SIZE_DEFAULT
    fun setDisplaySize(c: Context, i: Int) { AppSettings.setString(c, KEY_SIZE, i.toString()); _version.value++ }
    fun scale(c: Context): Float = SIZES[displaySize(c)].second

    /** Whether the app is dark right now, for code outside Compose (widgets, notifications). */
    fun isDark(c: Context): Boolean = when (mode(c)) {
        LIGHT -> false
        DARK -> true
        else -> (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}

/** One accent: its main colour and a second tone for gradients, in light and in dark. */
class Accent(
    val name: String,
    val light: Long, val light2: Long, val lightContainer: Long, val lightOnContainer: Long, val lightTint: Long,
    val dark: Long, val dark2: Long, val darkContainer: Long, val darkOnContainer: Long, val darkOn: Long,
)

val ACCENTS = listOf(
    Accent("Indigo", 0xFF4F46E5, 0xFF7C6CF7, 0xFFE7E7FF, 0xFF2D2692, 0xFFF3F4FA, 0xFF8D88FF, 0xFFB3A6FF, 0xFF2B2A63, 0xFFE2E1FF, 0xFF14104A),
    Accent("Ocean", 0xFF2563EB, 0xFF3B82F6, 0xFFDCE8FF, 0xFF123A8F, 0xFFF2F5FB, 0xFF8AB4FF, 0xFFA9C8FF, 0xFF1E3566, 0xFFDCE8FF, 0xFF0A1F52),
    Accent("Teal", 0xFF0D9488, 0xFF14B8A6, 0xFFD4F4EF, 0xFF0B4D47, 0xFFF1F7F6, 0xFF4FD6C4, 0xFF7FE6D8, 0xFF0F4741, 0xFFC9F5EE, 0xFF00332E),
    Accent("Violet", 0xFF9333EA, 0xFFB063F2, 0xFFF1E3FF, 0xFF4A1580, 0xFFF6F2FB, 0xFFC99BFF, 0xFFE0A8FF, 0xFF3B2357, 0xFFF3E3FF, 0xFF2A0B4A),
    Accent("Rose", 0xFFE11D48, 0xFFF43F6B, 0xFFFFE1E8, 0xFF7A0F2A, 0xFFFBF2F4, 0xFFFF8FA8, 0xFFFFADC0, 0xFF5A1A2A, 0xFFFFDDE5, 0xFF4A0618),
    Accent("Amber", 0xFFC2660A, 0xFFE08A1E, 0xFFFFEBCF, 0xFF6A3A00, 0xFFFBF6EE, 0xFFFFB55C, 0xFFFFCA85, 0xFF4A3410, 0xFFFFE6C2, 0xFF3A2200),
)

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
    Font(R.font.manrope_extrabold, FontWeight.Black),
)

private val AppTypography: Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = Manrope),
        displayMedium = t.displayMedium.copy(fontFamily = Manrope),
        displaySmall = t.displaySmall.copy(fontFamily = Manrope),
        headlineLarge = t.headlineLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold),
        headlineSmall = t.headlineSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
        titleSmall = t.titleSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
        bodyLarge = t.bodyLarge.copy(fontFamily = Manrope),
        bodyMedium = t.bodyMedium.copy(fontFamily = Manrope),
        bodySmall = t.bodySmall.copy(fontFamily = Manrope),
        labelLarge = t.labelLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
        labelMedium = t.labelMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.SemiBold),
    )
}

internal fun lightScheme(a: Accent) = lightColorScheme(
    primary = Color(a.light),
    onPrimary = Color.White,
    primaryContainer = Color(a.lightContainer),
    onPrimaryContainer = Color(a.lightOnContainer),
    secondary = Color(0xFF0E9F87),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F4EC),
    onSecondaryContainer = Color(0xFF05584A),
    tertiary = Color(0xFFC2660A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF0D4),
    onTertiaryContainer = Color(0xFF7A4B00),
    background = Color(a.lightTint),
    onBackground = Color(0xFF12141C),
    surface = Color.White,
    onSurface = Color(0xFF12141C),
    surfaceVariant = Color(0xFFEEF0F8),
    onSurfaceVariant = Color(0xFF5E6578),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FD),
    surfaceContainer = Color(0xFFF2F3F9),
    surfaceContainerHigh = Color(0xFFECEEF6),
    surfaceContainerHighest = Color(0xFFE6E8F1),
    error = Color(0xFFE5484D),
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E7),
    onErrorContainer = Color(0xFF9B1C24),
    outline = Color(0xFFC9CDDA),
    outlineVariant = Color(0xFFE2E5F0),
)

internal fun darkScheme(a: Accent, black: Boolean) = darkColorScheme(
    primary = Color(a.dark),
    onPrimary = Color(a.darkOn),
    primaryContainer = Color(a.darkContainer),
    onPrimaryContainer = Color(a.darkOnContainer),
    secondary = Color(0xFF3FD6BC),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF103F37),
    onSecondaryContainer = Color(0xFFBDF2E6),
    tertiary = Color(0xFFFFB55C),
    onTertiary = Color(0xFF3A2200),
    tertiaryContainer = Color(0xFF3B2C10),
    onTertiaryContainer = Color(0xFFFFD48A),
    background = if (black) Color.Black else Color(0xFF0C0E15),
    onBackground = Color(0xFFF1F2F8),
    surface = if (black) Color(0xFF0E0F14) else Color(0xFF171A24),
    onSurface = Color(0xFFF1F2F8),
    surfaceVariant = if (black) Color(0xFF181A22) else Color(0xFF212533),
    onSurfaceVariant = Color(0xFF9AA2B7),
    surfaceContainerLowest = if (black) Color.Black else Color(0xFF0C0E15),
    surfaceContainerLow = if (black) Color(0xFF0B0C10) else Color(0xFF141720),
    surfaceContainer = if (black) Color(0xFF111218) else Color(0xFF1A1D28),
    surfaceContainerHigh = if (black) Color(0xFF16171E) else Color(0xFF1F2230),
    surfaceContainerHighest = if (black) Color(0xFF1B1D25) else Color(0xFF252938),
    error = Color(0xFFFF7A85),
    onError = Color(0xFF4A0010),
    errorContainer = Color(0xFF47161E),
    onErrorContainer = Color(0xFFFFD6DA),
    outline = Color(0xFF3A4052),
    outlineVariant = Color(0xFF262A38),
)

/** The app's colours for code outside the screens (home screen widgets): the chosen accent in [dark] or light. */
fun appScheme(c: Context, dark: Boolean): ColorScheme {
    val a = Appearance.accent(c)
    if (a == Appearance.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return if (dark) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
    }
    val accent = ACCENTS.getOrElse(a) { ACCENTS[0] }
    return if (dark) darkScheme(accent, Appearance.pureBlack(c)) else lightScheme(accent)
}

/** True while the app draws in dark colours (the user's choice, or the phone's when on System). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** The accent's second tone, for gradients (Review card, + button, selected day). */
val LocalAccent2 = staticCompositionLocalOf { Color(0xFF7C6CF7) }

/**
 * The app's theme. Reads Settings › Appearance unless [dark] is given (the full-screen reminder and review
 * popups pass true: they always stay dark). Every colour fades smoothly when the choice changes.
 */
@Composable
fun CallbackTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // Read so a change in Settings › Appearance redraws the whole app.
    val version by Appearance.version.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = dark ?: when (Appearance.mode(ctx)) {
        Appearance.LIGHT -> false
        Appearance.DARK -> true
        else -> systemDark
    }
    val accentIndex = version.let { Appearance.accent(ctx) }
    val black = isDark && Appearance.pureBlack(ctx)
    val accent = ACCENTS.getOrElse(accentIndex) { ACCENTS[0] }
    val target: ColorScheme = if (accentIndex == Appearance.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val d = if (isDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        if (black) d.copy(background = Color.Black, surface = Color(0xFF0E0F14)) else d
    } else if (isDark) darkScheme(accent, black) else lightScheme(accent)
    val second = when {
        accentIndex == Appearance.WALLPAPER -> target.tertiary
        isDark -> Color(accent.dark2)
        else -> Color(accent.light2)
    }
    val scheme = animatedScheme(target)
    // Status and navigation bar icons follow the app's mode, not the phone's.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            var c: Context? = view.context
            while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
            (c as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
            }
        }
    }
    // Display size: everything (dp and text) grows or shrinks together, like the phone's own display size.
    val base = androidx.compose.ui.platform.LocalDensity.current
    val k = version.let { Appearance.scale(ctx) }
    CompositionLocalProvider(
        LocalDarkTheme provides isDark, LocalAccent2 provides second,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * k, base.fontScale),
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

@Composable
private fun anim(c: Color, label: String): Color = animateColorAsState(c, tween(350), label = label).value

/** The main colours fade over 350 ms, so switching Light / Dark or the accent is smooth, not a jump. */
@Composable
private fun animatedScheme(t: ColorScheme): ColorScheme = t.copy(
    primary = anim(t.primary, "primary"),
    onPrimary = anim(t.onPrimary, "onPrimary"),
    primaryContainer = anim(t.primaryContainer, "primaryContainer"),
    onPrimaryContainer = anim(t.onPrimaryContainer, "onPrimaryContainer"),
    secondaryContainer = anim(t.secondaryContainer, "secondaryContainer"),
    onSecondaryContainer = anim(t.onSecondaryContainer, "onSecondaryContainer"),
    background = anim(t.background, "background"),
    onBackground = anim(t.onBackground, "onBackground"),
    surface = anim(t.surface, "surface"),
    onSurface = anim(t.onSurface, "onSurface"),
    surfaceVariant = anim(t.surfaceVariant, "surfaceVariant"),
    onSurfaceVariant = anim(t.onSurfaceVariant, "onSurfaceVariant"),
    surfaceContainer = anim(t.surfaceContainer, "surfaceContainer"),
    surfaceContainerHigh = anim(t.surfaceContainerHigh, "surfaceContainerHigh"),
    surfaceContainerHighest = anim(t.surfaceContainerHighest, "surfaceContainerHighest"),
    outline = anim(t.outline, "outline"),
    outlineVariant = anim(t.outlineVariant, "outlineVariant"),
)

/**
 * Coloured chips, tiles and badges that used to be fixed light pastels. Each tone has a soft container and
 * a readable ink, picked for the current mode so they look right in light and in dark.
 */
class Tone(val bg: Color, val fg: Color) {
    val pair: Pair<Color, Color> get() = bg to fg
}

object Tones {
    val red: Tone @Composable get() = if (LocalDarkTheme.current) Tone(Color(0xFF47161E), Color(0xFFFFC2C8)) else Tone(Color(0xFFFFE3E6), Color(0xFF8A1424))
    val amber: Tone @Composable get() = if (LocalDarkTheme.current) Tone(Color(0xFF3B2C10), Color(0xFFFFD48A)) else Tone(Color(0xFFFFF0D4), Color(0xFF7A4B00))
    val green: Tone @Composable get() = if (LocalDarkTheme.current) Tone(Color(0xFF103F37), Color(0xFFBDF2E6)) else Tone(Color(0xFFD9F3EC), Color(0xFF0B5A4C))
    val purple: Tone @Composable get() = if (LocalDarkTheme.current) Tone(Color(0xFF2E2350), Color(0xFFE3D6FF)) else Tone(Color(0xFFEDE4FF), Color(0xFF3F1F8C))
    val blue: Tone @Composable get() = if (LocalDarkTheme.current) Tone(Color(0xFF1E2E5E), Color(0xFFD6E0FF)) else Tone(Color(0xFFE1E8FF), Color(0xFF1E3A8A))

    // The big bar buttons (✓ Done, 📞 Call, 📅 Deadline) and Needs you tiles: one place for these colours.
    val done: Tone @Composable get() = if (LocalDarkTheme.current) Tone(Color(0xFF173D33), Color(0xFF7FE0C2)) else Tone(Color(0xFFDDF5EC), Color(0xFF0B6B55))
    val call: Tone @Composable get() = if (LocalDarkTheme.current) Tone(Color(0xFF1E2A55), Color(0xFFB9C8FF)) else Tone(Color(0xFFE3EAFF), Color(0xFF1F3A93))
    val date: Tone @Composable get() = if (LocalDarkTheme.current) Tone(Color(0xFF33285E), Color(0xFFD6CCFF)) else Tone(Color(0xFFEDE7FF), Color(0xFF4B2FA8))
    val late: Tone @Composable get() = if (LocalDarkTheme.current) Tone(Color(0xFF4A1E24), Color(0xFFFF9AA4)) else Tone(Color(0xFFFFE1E3), Color(0xFFA61B2B))

    /** 🔴 Urgent card fill: a deep red tint (light pink by day), clearly apart from the ⚠ Stuck red. */
    val urgentCard: Color @Composable get() = if (LocalDarkTheme.current) Color(0xFF3B1619) else Color(0xFFFFEAEE)
}
