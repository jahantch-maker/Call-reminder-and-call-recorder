package com.callbackreminder.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * The optional 🎤 in note and message boxes (Settings › Voice typing, off to start with). It opens the
 * phone's own voice typing (Google), so English and Urdu both work and the app needs no internet.
 */
object VoiceTyping {
    enum class Lang(val label: String, val tag: String?) {
        PHONE("Phone's language", null),
        ENGLISH("English", "en-US"),
        URDU("Urdu", "ur-PK"),
    }

    private var on: MutableState<Boolean>? = null

    private fun p(c: Context) = c.getSharedPreferences("voice_typing", Context.MODE_PRIVATE)

    fun state(c: Context): MutableState<Boolean> =
        on ?: mutableStateOf(p(c).getBoolean("on", false)).also { on = it }

    fun setEnabled(c: Context, value: Boolean) {
        p(c).edit().putBoolean("on", value).apply()
        state(c).value = value
    }

    fun lang(c: Context): Lang =
        p(c).getString("lang", null)?.let { s -> Lang.values().firstOrNull { it.name == s } } ?: Lang.PHONE

    fun setLang(c: Context, l: Lang) = p(c).edit().putString("lang", l.name).apply()

    fun summary(c: Context): String = if (state(c).value) "On · ${lang(c).label}" else "Off"

    fun intent(c: Context): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        .apply { lang(c).tag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) } }
}

/**
 * A trailing mic for a text box when voice typing is on (null when it's off). What you say is added
 * to the end of [current].
 */
@Composable
fun voiceMic(current: String, onText: (String) -> Unit): (@Composable () -> Unit)? {
    val ctx = LocalContext.current
    val on by VoiceTyping.state(ctx)
    if (!on) return null
    return { VoiceMicButton(current, onText) }
}

@Composable
private fun VoiceMicButton(current: String, onText: (String) -> Unit) {
    val ctx = LocalContext.current
    val now by rememberUpdatedState(current)
    val done by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!heard.isNullOrEmpty()) done(if (now.isBlank()) heard else now.trimEnd() + " " + heard)
    }
    IconButton(onClick = {
        try {
            launcher.launch(VoiceTyping.intent(ctx))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "Voice typing isn't available on this phone", Toast.LENGTH_SHORT).show()
        }
    }) {
        Icon(Icons.Rounded.Mic, contentDescription = "Voice typing", tint = MaterialTheme.colorScheme.primary)
    }
}
