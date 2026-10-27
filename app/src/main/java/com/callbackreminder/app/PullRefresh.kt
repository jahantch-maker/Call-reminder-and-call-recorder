package com.callbackreminder.app

import android.widget.Toast
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pull down to refresh, the same on Home, My tasks, Follow-ups, Needs you and a member's day: the team's tasks
 * and your Dossier are read again from the server, then "✓ Up to date".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefresh(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = busy,
        onRefresh = {
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { if (TeamRepo.activeNow) TeamRepo.fetchOnce(ctx.applicationContext) }
                        .onFailure { CrashLog.record(ctx.applicationContext, it, "Pull to refresh") }
                        .isSuccess
                }
                // Long enough to see it spin, even when nothing had to come down.
                delay(350L)
                busy = false
                Toast.makeText(ctx, if (ok) "✓ Up to date" else "Couldn't refresh · check the internet", Toast.LENGTH_SHORT).show()
            }
        },
        modifier = modifier,
        content = content,
    )
}
