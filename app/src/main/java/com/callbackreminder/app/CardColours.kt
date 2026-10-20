package com.callbackreminder.app

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Calendar settings › Card colours and Done tasks. One colour from [PALETTE] per kind of card, saved in the
 * app settings so the Calendar and the home screen calendar widgets read the same choice.
 */
object CardColours {
    /** A card's soft fill and its darker edge stripe. Text on the fill is always [INK]. */
    data class Swatch(val name: String, val fill: Long, val edge: Long) {
        val fillColor get() = Color(fill)
        val edgeColor get() = Color(edge)

        /** Card fill for the mode: a soft tint in light, a deep tint of the same colour in dark. */
        fun fill(dark: Boolean): Color = if (dark) lerp(Color(0xFF14161F), Color(edge), 0.38f) else lerp(Color(fill), Color.White, 0.45f)
        /** Text on [fill]: dark ink in light, a light pastel in dark. */
        fun ink(dark: Boolean): Color = if (dark) lerp(Color(fill), Color.White, 0.55f) else lerp(Color(edge), Color(0xFF101014), 0.55f)
        /** The 4 dp edge on the card's left: the strong tone in light, the bright pastel in dark. */
        fun edge(dark: Boolean): Color = if (dark) Color(fill) else Color(edge)
    }

    /** 8 soft pastels, then 4 stronger tones, all clearly different from each other. */
    val PALETTE = listOf(
        Swatch("Sky", 0xFFB4D9F9, 0xFF1866AA),
        Swatch("Peach", 0xFFFDD4AF, 0xFFAA5C18),
        Swatch("Mint", 0xFFB8EAC9, 0xFF2C964F),
        Swatch("Lavender", 0xFFDAC2F4, 0xFF5C1DA5),
        Swatch("Lemon", 0xFFFCF39C, 0xFFAA9B18),
        Swatch("Rose", 0xFFFAC6D3, 0xFFAA183D),
        Swatch("Sand", 0xFFE2D8CA, 0xFF8D6835),
        Swatch("Cloud", 0xFFDDE0E3, 0xFF495D79),
        Swatch("Coral", 0xFFF4837B, 0xFFAA2218),
        Swatch("Turquoise", 0xFF64D4D8, 0xFF27979B),
        Swatch("Fuchsia", 0xFFE495DE, 0xFF9B2791),
        Swatch("Mustard", 0xFFEBBA47, 0xFFAA7E18),
    )

    val INK = Color(0xFF1C1B1F)

    enum class Kind(val label: String, val key: String, val default: Int) {
        TASK("My tasks", "cc_task", 0),
        FOLLOW_UP("Follow-ups", "cc_fu", 1),
        DOSSIER("Dossier", "cc_dossier", 2),
        GOOGLE("Google meetings", "cc_google", 3),
    }

    private const val KEY_DONE_LEAVE = "cal_done_leave"

    private val _version = MutableStateFlow(0)
    /** Bumps on every change, so the Calendar (and widgets) redraw. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun index(c: Context, kind: Kind): Int =
        AppSettings.getString(c, kind.key).toIntOrNull()?.takeIf { it in PALETTE.indices } ?: kind.default

    fun swatch(c: Context, kind: Kind): Swatch = PALETTE[index(c, kind)]

    fun set(c: Context, kind: Kind, index: Int) {
        AppSettings.setString(c, kind.key, index.coerceIn(PALETTE.indices).toString())
        _version.value++
    }

    fun reset(c: Context) {
        Kind.entries.forEach { AppSettings.setString(c, it.key, "") }
        _version.value++
    }

    /** Done My tasks and follow-ups: false = stay greyed (default), true = leave the calendar. Dossier always stays. */
    fun doneLeaves(c: Context) = AppSettings.get(c, KEY_DONE_LEAVE, false)

    fun setDoneLeaves(c: Context, leave: Boolean) {
        AppSettings.set(c, KEY_DONE_LEAVE, leave)
        _version.value++
    }
}
