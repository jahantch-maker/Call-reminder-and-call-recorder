package com.callbackreminder.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update from inside the app. Every build copies its APK and a small version.json to a public GitHub page that
 * holds no code. This checks that page, downloads the new APK and hands it to Android's installer (Android always
 * asks for one Install tap). The main app and the CR app each follow their own channel (R.string.update_channel).
 */
object AppUpdate {
    private const val BASE = "https://github.com/jahantch-maker/callback-reminder-releases/releases/download"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    data class Latest(val code: Long, val name: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Downloading(val percent: Int) : State
        data class Ready(val file: File) : State
        data class Failed(val why: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun prefs(c: Context) = c.getSharedPreferences("update", Context.MODE_PRIVATE)

    /** Set by the Home update notice so Settings scrolls to the version bubble at its end. */
    @Volatile var jumpToCard = false

    private val _newer = MutableStateFlow<Latest?>(null)
    /** The newer version waiting, or null when this one is the latest. Survives restarts without network. */
    val newer: StateFlow<Latest?> = _newer
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _required = MutableStateFlow<Latest?>(null)
    /**
     * v0.59 A newer version found online just now: the app can't be used until it is installed (the owner's rule, so
     * nobody is left on an old version). Only a check that reached the server sets it, so without internet the app
     * opens as usual.
     */
    val required: StateFlow<Latest?> = _required

    /** The new version's "What's new" lines, sent with version.json. */
    fun notes(c: Context): List<String> =
        (prefs(c).getString("notes", "") ?: "").split('\n').filter { it.isNotBlank() }

    fun installedCode(c: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0))
    } catch (e: Exception) {
        0L
    }

    private fun channel(c: Context) = c.getString(R.string.update_channel)

    /** Reads the last answer saved on disk, so the Home notice shows straight away. */
    fun load(c: Context) {
        val code = prefs(c).getLong("code", 0L)
        _newer.value = if (code > installedCode(c)) Latest(code, prefs(c).getString("name", "") ?: "") else null
    }

    /** Home: check quietly, at most every 6 hours. */
    fun autoCheck(c: Context) {
        val app = c.applicationContext
        load(app)
        if (System.currentTimeMillis() - prefs(app).getLong("checked", 0L) < CHECK_EVERY_MS) return
        scope.launch { fetch(app, quiet = true) }
    }

    /** Every time the app comes to the front: check now, whatever the 6 hours, and lock the app if a newer one is out. */
    fun checkOnOpen(c: Context) {
        val app = c.applicationContext
        if (_state.value is State.Downloading) return
        scope.launch { fetch(app, quiet = true) }
    }

    /** Settings: check now and say what happened. */
    fun checkNow(c: Context) {
        val app = c.applicationContext
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        scope.launch { fetch(app, quiet = false) }
    }

    private fun fetch(c: Context, quiet: Boolean) {
        try {
            val text = open("$BASE/${channel(c)}/version.json").use { it.readBytes().decodeToString() }
            val j = JSONObject(text)
            val code = j.getLong("versionCode")
            val notes = j.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            prefs(c).edit().putLong("code", code).putString("name", j.optString("versionName"))
                .putString("notes", notes.joinToString("\n"))
                .putLong("checked", System.currentTimeMillis()).apply()
            load(c)
            _required.value = _newer.value
            if (!quiet) _state.value = if (code > installedCode(c)) State.Idle else State.UpToDate
        } catch (e: Exception) {
            if (!quiet) _state.value = State.Failed("Couldn't check. Is your internet on?")
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        useCaches = false
        setRequestProperty("Cache-Control", "no-cache")
    }.let { conn ->
        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
        conn.inputStream
    }

    /** Downloads the new APK and, when whole and for this app, opens the installer. */
    fun download(c: Context) {
        val app = c.applicationContext
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(0)
        scope.launch {
            try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val file = File(dir, "update.apk")
                val conn = (URL("$BASE/${channel(app)}/app.apk").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    useCaches = false
                }
                if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) _state.value = State.Downloading((done * 100 / total).toInt())
                        }
                    }
                }
                if (total > 0 && done != total) throw IllegalStateException("cut short")
                // Never hand Android a file that isn't this app, or isn't newer.
                val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
                val ok = info != null && info.packageName == app.packageName &&
                    PackageInfoCompat.getLongVersionCode(info) > installedCode(app)
                if (!ok) throw IllegalStateException("wrong file")
                _state.value = State.Ready(file)
                withContext(Dispatchers.Main) { install(app, file) }
            } catch (e: Exception) {
                _state.value = State.Failed("Download failed. Try again.")
            }
        }
    }

    /** Opens Android's installer; first sends you to the one-time "Allow from this app" switch if needed. */
    fun install(c: Context, file: File) {
        if (!c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", file)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** End of Settings: this version, Check for update, and Update when a newer one is waiting. */
@Composable
fun UpdateCard(onWhatsNew: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val newer by AppUpdate.newer.collectAsState()
    val state by AppUpdate.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { AppUpdate.load(ctx) }
    val n = newer
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (n != null) cs.primaryContainer else cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            val line = when {
                state is AppUpdate.State.Checking -> "Checking…"
                state is AppUpdate.State.Downloading -> "Downloading… ${(state as AppUpdate.State.Downloading).percent}%"
                state is AppUpdate.State.Failed -> (state as AppUpdate.State.Failed).why
                n != null -> "Version ${n.name} is ready"
                state is AppUpdate.State.UpToDate -> "You have the latest version"
                else -> "Tap Check for update to see if there is a newer one"
            }
            Text("Version ${BuildConfigVersion.name(ctx)}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(line, fontSize = 13.sp, color = if (n != null) cs.onPrimaryContainer else cs.onSurfaceVariant)
            val s = state
            if (s is AppUpdate.State.Downloading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (n != null) {
                        Button(onClick = { AppUpdate.download(ctx) }) { Text("Update") }
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(onClick = { AppUpdate.checkNow(ctx) }, enabled = s !is AppUpdate.State.Checking) {
                        Text("Check for update")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onWhatsNew) { Text("What's new ›") }
                }
            }
        }
    }
}

/**
 * v0.59 The full-screen "Update required" page over the whole app: no Back, no close, only Update now. Reminders,
 * call recording and alarms keep running underneath; only the screens can't be used until the new version is in.
 */
@Composable
fun UpdateRequiredScreen(latest: AppUpdate.Latest) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val state by AppUpdate.state.collectAsState()
    val notes = androidx.compose.runtime.remember(latest.code) { AppUpdate.notes(ctx) }
    androidx.activity.compose.BackHandler(enabled = true) { }
    val s = state
    val busy = s is AppUpdate.State.Downloading
    Box(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            // Taps never reach the app underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                // The launcher icon's two layers, cropped the way Android shows them.
                Image(painterResource(R.mipmap.ic_launcher_background), null, Modifier.requiredSize(144.dp), contentScale = ContentScale.Crop)
                Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.requiredSize(144.dp), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height(22.dp))
            Text(
                if (busy) "Updating…" else "Update required",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (busy) "Downloading version ${latest.name}" else "A new version of ${ctx.getString(R.string.app_name)} is ready.\nPlease update to keep using the app.",
                fontSize = 15.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, lineHeight = 22.sp
            )
            Spacer(Modifier.height(22.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    if (s is AppUpdate.State.Downloading) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("Downloading", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("${s.percent}%", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = cs.primary)
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Text("Keep the app open. Android will ask you to tap Update once.", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    } else {
                        Row(Modifier.fillMaxWidth()) {
                            Text("Your version", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(BuildConfigVersion.name(ctx), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Text("New version", fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(latest.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                        }
                        if (notes.isNotEmpty()) {
                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = cs.outlineVariant)
                            Text("What's new", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            notes.forEach { Text("• $it", fontSize = 13.sp, color = cs.onSurfaceVariant, lineHeight = 19.sp) }
                        }
                    }
                }
            }
            if (s is AppUpdate.State.Failed) {
                Spacer(Modifier.height(12.dp))
                Text(s.why, color = cs.error, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(28.dp))
            if (!busy) {
                Button(
                    onClick = { if (s is AppUpdate.State.Ready && s.file.exists()) AppUpdate.install(ctx, s.file) else AppUpdate.download(ctx) },
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Update now", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Your reminders and call recording keep working in the background.",
                fontSize = 12.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}
