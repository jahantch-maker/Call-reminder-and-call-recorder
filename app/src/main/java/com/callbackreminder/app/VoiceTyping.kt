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
 * The 🎤 in note and message boxes (Settings › Voice typing; 27 Nov N5: on to start with, Urdu first). It opens the
 * phone's own voice typing (Google), so English and Urdu both work and the app needs no internet.
 */
object VoiceTyping {
    enum class Lang(val label: String, val tag: String) {
        URDU("Urdu", "ur-PK"),
        ENGLISH("English", "en-US"),
    }

    private var on: MutableState<Boolean>? = null

    private fun p(c: Context) = c.getSharedPreferences("voice_typing", Context.MODE_PRIVATE)

    /** 27 Nov (N5) Every phone switches it on once with this update; after that his own choice stays. */
    private const val ON_ONCE = "on_once"

    fun state(c: Context): MutableState<Boolean> = on ?: run {
        val pr = p(c)
        if (!pr.getBoolean(ON_ONCE, false)) pr.edit().putBoolean("on", true).putBoolean(ON_ONCE, true).apply()
        mutableStateOf(pr.getBoolean("on", true))
    }.also { on = it }

    fun setEnabled(c: Context, value: Boolean) {
        p(c).edit().putBoolean("on", value).apply()
        state(c).value = value
    }

    fun lang(c: Context): Lang =
        p(c).getString("lang", null)?.let { s -> Lang.values().firstOrNull { it.name == s } } ?: Lang.URDU

    fun setLang(c: Context, l: Lang) = p(c).edit().putString("lang", l.name).apply()

    fun summary(c: Context): String = if (state(c).value) "On · ${lang(c).label}" else "Off"

    fun intent(c: Context): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang(c).tag)
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
