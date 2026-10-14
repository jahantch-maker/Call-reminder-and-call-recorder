package com.callbackreminder.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Round badge with the first letter of the caller's name. */
@Composable
fun Avatar(text: String, size: Dp = 44.dp) {
    val initial = text.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.4f).sp
        )
    }
}

/**
 * The Recordings and Settings icons at the top right of every bottom-bar tab. The tabs page
 * provides them; anywhere else (a page opened over the tabs) shows nothing.
 */
val LocalTopActions = compositionLocalOf<@Composable () -> Unit> { {} }

@Composable
fun TopActions() {
    // One Row, so a header using SpaceBetween keeps the two icons together instead of spreading them.
    Row(verticalAlignment = Alignment.CenterVertically) { LocalTopActions.current() }
}
