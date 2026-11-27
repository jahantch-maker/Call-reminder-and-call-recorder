package com.callbackreminder.app

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 27 Nov (S12) The first-open sign-in screen: new installs only, before the permission screens. "Sign in with Google"
 * for the team, or "Use without an account" (guest: the app as it works without sign-in, every team part hidden).
 * Once a phone has joined the team, Guest never comes back: signed out, it shows only Sign in.
 */
object SignInGate {
    private fun p(c: Context) = c.getSharedPreferences("sign_in_gate", Context.MODE_PRIVATE)
    /** Bumps when Guest is picked, so the screen goes. */
    val version = MutableStateFlow(0)

    /** Decided once, on the first open with this version: phones that already had the app never see the first-open screen. */
    fun init(c: Context) {
        if (p(c).contains("new")) return
        val fresh = !FollowUps.introSeen(c) && ReminderStore.items.value.isEmpty() && MyTaskStore.items.value.isEmpty() && TeamRepo.user.value == null
        p(c).edit().putBoolean("new", fresh).putBoolean("joined", TeamRepo.teamId != null).apply()
    }

    fun guest(c: Context) = p(c).getBoolean("guest", false) && !everJoined(c)
    fun setGuest(c: Context) {
        p(c).edit().putBoolean("guest", true).apply()
        version.value++
    }

    fun everJoined(c: Context) = p(c).getBoolean("joined", false)
    fun markJoined(c: Context) {
        if (!everJoined(c)) p(c).edit().putBoolean("joined", true).putBoolean("guest", false).apply()
    }

    /** Show the screen: not in the team yet, and either a new install that hasn't picked Guest, or a phone that was in the team. */
    fun needed(c: Context): Boolean =
        TeamRepo.teamId == null && (everJoined(c) || (p(c).getBoolean("new", false) && !guest(c)))
}

private val Brand = Color(0xFF4F46E5)

@Composable
fun SignInGateScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by TeamRepo.user.collectAsState()
    val back = SignInGate.everJoined(ctx)
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val notInvited = user != null && msg == TeamRepo.NOT_ADDED

    fun signIn() {
        val act = ctx as? Activity ?: return
        busy = true
        msg = null
        scope.launch {
            msg = if (TeamRepo.user.value != null) TeamRepo.autoJoin() else TeamRepo.signIn(act)
            busy = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().safeBottomPadding(0.dp).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            Image(painterResource(R.drawable.ic_rb_logo), contentDescription = "RB360", modifier = Modifier.size(120.dp))
            Text("— 360 —", color = Brand, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(24.dp))
            Text(if (back) "Welcome back" else "Welcome to RB360", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                if (back) TeamRepo.signedOutNote() ?: "You're signed out of ${Team.HOME_TEAM}. If your manager gave you a new Gmail, sign in with it."
                else "Call reminders, tasks and your team, in one app.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            if (notInvited) {
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFFFF1D6), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("This Gmail isn't invited yet", color = Color(0xFF7A4B00), fontWeight = FontWeight.Bold)
                        Text(user?.email.orEmpty(), color = Color(0xFF7A4B00))
                        Text("Ask your manager to add it, then tap Try again." + if (back) "" else " Or continue as guest.", color = Color(0xFF7A4B00))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { signIn() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand)) { Text("Try again", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { TeamRepo.signOut { msg = null } },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp),
                ) { Text("Use another Gmail", color = Brand, fontSize = 16.sp) }
            } else {
                Button(onClick = { signIn() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Brand)) {
                    Box(Modifier.size(26.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(if (busy) "Signing in…" else "Sign in with Google", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text("For ${Team.HOME_TEAM} team · all features", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                msg?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
            if (!back) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text("  or  ", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                    HorizontalDivider(Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    if (notInvited) "Continue as guest" else "Use without an account",
                    color = Brand, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { SignInGate.setGuest(ctx) }.padding(8.dp),
                )
                Text("For personal use · no team", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            } else {
                Spacer(Modifier.height(20.dp))
                Text("Your tasks and settings come back by themselves after you sign in.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(40.dp))
            if (!back) Text("You can sign in later from Settings.", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Text(buildAnnotatedString {
                append("Built by ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Jahanzaib") }
                append(" for ${Team.HOME_TEAM}")
            }, color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
        }
    }
}
