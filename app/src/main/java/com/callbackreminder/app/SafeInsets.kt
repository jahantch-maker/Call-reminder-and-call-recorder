package com.callbackreminder.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Popups (Dialog windows) sometimes report a zero navigation bar inset, so Save/Cancel ended up
 * behind the phone's back/home buttons. This reads the nav bar height three ways (the popup's own
 * insets, the activity window's insets, and the system nav bar size) and uses the biggest.
 */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun systemNavBarPx(ctx: Context): Int {
    val res = ctx.resources
    // 2 = gesture navigation: only a thin handle, the full bar size would leave a big gap.
    val modeId = res.getIdentifier("config_navBarInteractionMode", "integer", "android")
    val mode = if (modeId > 0) runCatching { res.getInteger(modeId) }.getOrDefault(0) else 0
    val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
    val h = if (id > 0) runCatching { res.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    return if (mode == 2) h / 3 else h
}

/** Height of the phone's navigation bar (buttons or gesture handle), never under-reported. */
@Composable
fun navBarHeight(): Dp {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val local = WindowInsets.navigationBars.getBottom(density)
    val root = ctx.findActivity()?.window?.decorView
        ?.let { ViewCompat.getRootWindowInsets(it) }
        ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
    val px = maxOf(local, root, if (local == 0 && root == 0) systemNavBarPx(ctx) else 0)
    return with(density) { px.toDp() }
}

/** Bottom padding that keeps buttons above the nav bar (or the keyboard), plus a small gap. */
@Composable
fun Modifier.safeBottomPadding(gap: Dp = 8.dp): Modifier {
    val density = LocalDensity.current
    val ime = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    return this.padding(bottom = maxOf(navBarHeight(), ime) + gap)
}

/** For sheets that already pad by their own insets: adds only what those insets missed. */
@Composable
fun NavBarMissingSpacer() {
    val density = LocalDensity.current
    val local = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val missing = navBarHeight() - local
    if (missing > 0.dp) Spacer(Modifier.height(missing))
}

/** What a sheet's own nav bar padding missed, to add to its bottom padding. */
@Composable
fun navBarMissing(): Dp {
    val density = LocalDensity.current
    val local = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    return (navBarHeight() - local).coerceAtLeast(0.dp)
}

/**
 * Keeps a full-screen popup between the phone's bars by measuring where it really is on the screen, instead of trusting
 * the insets a popup reports (some phones report none and the popup also reaches past the screen's edge). Whatever of
 * the popup lies under the status bar or the navigation buttons is added as padding; the keyboard counts too.
 */
@Composable
fun Modifier.fitBetweenBars(): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    val decor = view.context.findActivity()?.window?.decorView
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val imePx = WindowInsets.ime.getBottom(density)
    val held = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    // Where this window's content really is on the screen, against the phone's bars and the keyboard. Measured, not
    // assumed: when the system already shrank the window for the keyboard nothing is added (no gap above the keyboard),
    // when it did not, the keyboard's height is added.
    fun measure() {
        val c = held[0] ?: return
        decor ?: return
        if (!c.isAttached) return
        val root = ViewCompat.getRootWindowInsets(decor) ?: return
        val bars = root.getInsets(WindowInsetsCompat.Type.systemBars())
        val imeRoot = root.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val keyboard = if (imeRoot > 0) imeRoot else imePx
        val mine = IntArray(2).also { view.getLocationOnScreen(it) }
        val screen = IntArray(2).also { decor.getLocationOnScreen(it) }
        val top = mine[1] + c.positionInRoot().y.toInt()
        val bottom = top + c.size.height
        val newTop = (screen[1] + bars.top - top).coerceAtLeast(0)
        val newBottom = (bottom - (screen[1] + decor.height - maxOf(bars.bottom, keyboard))).coerceAtLeast(0)
        // A coarse step so a one-pixel wobble never starts a loop.
        if (kotlin.math.abs(newTop - topPx) > 1) topPx = newTop
        if (kotlin.math.abs(newBottom - bottomPx) > 1) bottomPx = newBottom
    }
    androidx.compose.runtime.LaunchedEffect(imePx) {
        // The window may resize a moment after the keyboard starts moving: measure again once it settled.
        kotlinx.coroutines.delay(150)
        measure()
    }
    return this
        .onGloballyPositioned { c -> held[0] = c; measure() }
        .padding(top = with(density) { topPx.toDp() }, bottom = with(density) { bottomPx.toDp() })
}
