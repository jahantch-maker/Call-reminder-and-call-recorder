package com.callbackreminder.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The gold of a star that is on. */
val StarGold = Color(0xFFF5B400)

/**
 * Cancel and Back for an edit window. When [changed] says nothing was changed it closes at once;
 * otherwise it asks "Discard changes?" and only Discard closes it (throwing the edits away).
 * Returns the function to call from Cancel, Back and the window's dismiss request.
 * [onKeep] runs on "Keep editing": a bottom sheet slid away before asking must be brought back up,
 * or its hidden window keeps covering the app and nothing responds to taps.
 */
@Composable
fun rememberDiscardGuard(changed: () -> Boolean, onClose: () -> Unit, onKeep: () -> Unit = {}): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    val isChanged by rememberUpdatedState(changed)
    val close by rememberUpdatedState(onClose)
    val keep by rememberUpdatedState(onKeep)
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false; keep() },
            title = { Text("Discard changes?") },
            text = { Text("You changed this. Discard puts it back exactly as it was.") },
            confirmButton = {
                TextButton(onClick = { asking = false; close() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { asking = false; keep() }) { Text("Keep editing") } },
        )
    }
    return remember { { if (isChanged()) asking = true else close() } }
}

/** A small ☆ / ★ button for a card. */
@Composable
fun StarButton(starred: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
        Icon(
            if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            contentDescription = if (starred) "Unstar" else "Star as important",
            tint = if (starred) StarGold else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** "★ Important" with a switch, for New and Edit windows. */
@Composable
fun StarSwitchRow(starred: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!starred) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Star, contentDescription = null, tint = StarGold, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Important", fontWeight = FontWeight.SemiBold)
            Text(
                "Shows ★ on the card, found with the Starred filter",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = starred, onCheckedChange = onChange)
    }
}
