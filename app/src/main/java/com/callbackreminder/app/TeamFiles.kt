package com.callbackreminder.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * N4 (27 Nov) Media from the phone and WhatsApp into a task Timeline. Photos are shrunk and kept in the message as
 * today; voice notes, documents and videos (max 15 MB) go to Firebase Storage and the message keeps their link.
 * Works offline: the message shows at once with 🕓, the file waits in this phone's outbox and goes up when data is on.
 */
object TeamFiles {
    private fun p(c: Context) = c.getSharedPreferences("team_files", Context.MODE_PRIVATE)
    private fun outbox(c: Context) = File(c.filesDir, "outbox").apply { mkdirs() }

    /** "From WhatsApp": the task the next share from WhatsApp goes to (for 30 minutes). */
    fun rememberTarget(c: Context, taskId: String) = p(c).edit().putString("target", taskId).putLong("targetAt", System.currentTimeMillis()).apply()
    fun target(c: Context): String? = p(c).getString("target", null)
        ?.takeIf { System.currentTimeMillis() - p(c).getLong("targetAt", 0L) < 30 * 60_000L }
    fun clearTarget(c: Context) = p(c).edit().remove("target").apply()

    private fun nameAndSize(c: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = -1L
        runCatching {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    cur.getString(0)?.let { name = it }
                    if (!cur.isNull(1)) size = cur.getLong(1)
                }
            }
        }
        return name.ifBlank { "file" } to size
    }

    /** Sends one shared or picked item to [t]'s Timeline. Returns a problem in plain words, or null when it went. */
    fun attach(c: Context, t: TeamTask, uri: Uri, mimeIn: String?, via: String = ""): String? {
        val mime = (mimeIn ?: c.contentResolver.getType(uri)).orEmpty().ifBlank { "application/octet-stream" }
        val extra = if (via.isNotEmpty()) mapOf<String, Any>("via" to via) else emptyMap()
        if (mime.startsWith("image/")) {
            // Photos: always shrunk (about 150 KB) and kept in the message, as today.
            val bytes = TeamMedia.shrink(c, uri) ?: return "Couldn't read that photo"
            return if (TeamRepo.send(t, Team.PHOTO, "", data = bytes, extra = extra)) null else "Couldn't send: check Settings › Team"
        }
        val (name, size) = nameAndSize(c, uri)
        if (size > Team.MAX_FILE) return "Too big, share a smaller file (15 MB at most)"
        val file = File(outbox(c), System.currentTimeMillis().toString() + "_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        runCatching { c.contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { i.copyTo(it) } } }
            .onFailure { file.delete(); return "Couldn't read that file" }
        if (file.length() > Team.MAX_FILE) { file.delete(); return "Too big, share a smaller file (15 MB at most)" }
        val kind = when {
            mime.startsWith("audio/") -> Team.VOICE
            mime.startsWith("video/") -> Team.VIDEO
            else -> Team.FILE
        }
        val dur = if (kind == Team.FILE) 0L else runCatching {
            val r = android.media.MediaMetadataRetriever()
            try { r.setDataSource(file.path); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L } finally { r.release() }
        }.getOrDefault(0L)
        val ok = TeamRepo.send(t, kind, "", durMs = dur, extra = extra + mapOf("fname" to name, "size" to file.length(), "mime" to mime, "url" to ""))
        val msgId = TeamRepo.lastSentId
        if (!ok || msgId == null) { file.delete(); return "Couldn't send: check Settings › Team" }
        val list = queue(c)
        list.put(JSONObject().put("team", TeamRepo.teamId).put("task", t.id).put("msg", msgId).put("file", file.path).put("mime", mime))
        p(c).edit().putString("queue", list.toString()).apply()
        flush(c)
        return null
    }

    private fun queue(c: Context): JSONArray = runCatching { JSONArray(p(c).getString("queue", "[]")) }.getOrDefault(JSONArray())

    private val busy = mutableSetOf<String>()

    /** Uploads every waiting file; each one's message gets its link once it is up. Safe to call again any time. */
    fun flush(c: Context) {
        val list = queue(c)
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val id = e.getString("msg")
            val file = File(e.getString("file"))
            if (!file.exists()) { drop(c, id); continue }
            val added = synchronized(busy) { busy.add(id) }
            if (!added) continue
            val path = "teams/${e.getString("team")}/tasks/${e.getString("task")}/${id}_${file.name.substringAfter('_')}"
            val ref = FirebaseStorage.getInstance().reference.child(path)
            ref.putFile(Uri.fromFile(file), StorageMetadata.Builder().setContentType(e.getString("mime")).build())
                .continueWithTask { ref.downloadUrl }
                .addOnSuccessListener { url ->
                    FirebaseFirestore.getInstance().collection("teams").document(e.getString("team")).collection("tasks")
                        .document(e.getString("task")).collection("msgs").document(id).update("url", url.toString())
                    file.delete()
                    drop(c, id)
                }
                .addOnCompleteListener { synchronized(busy) { busy.remove(id) } }
        }
    }

    private fun drop(c: Context, msgId: String) {
        val list = queue(c)
        val keep = JSONArray()
        for (i in 0 until list.length()) list.getJSONObject(i).takeIf { it.getString("msg") != msgId }?.let { keep.put(it) }
        p(c).edit().putString("queue", keep.toString()).apply()
    }

    /** After 90 days (with the task's other media). */
    fun delete(url: String) {
        FirebaseStorage.getInstance().getReferenceFromUrl(url).delete()
    }

    /** A voice note kept in Storage, for the ▶ player. */
    fun bytes(url: String, onDone: (ByteArray?) -> Unit) {
        FirebaseStorage.getInstance().getReferenceFromUrl(url).getBytes(Team.MAX_FILE)
            .addOnSuccessListener { onDone(it) }.addOnFailureListener { onDone(null) }
    }

    /** Opens a document or video in the phone's own app for it. */
    fun open(c: Context, m: TeamMsg) {
        if (m.url.isBlank()) return
        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(m.url), m.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .recoverCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(c, "No app on this phone opens this file", Toast.LENGTH_SHORT).show() }
    }

    fun sizeLabel(b: Long): String = when {
        b >= 1024 * 1024 -> String.format(java.util.Locale.ENGLISH, "%.1f MB", b / 1048576.0)
        b >= 1024 -> "${b / 1024} KB"
        else -> "$b B"
    }
}

/**
 * N4 📎 Attach in the Timeline composer: Camera, Gallery (through the photo editor, as today), Files on the phone,
 * and From WhatsApp (remembers this task, opens WhatsApp; Share › RB360 there lands straight back in it).
 */
@Composable
internal fun rememberTeamAttach(t: TeamTask, onPhoto: (ByteArray) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var choose by remember { mutableStateOf(false) }
    val (camera, gallery) = rememberTeamPhotoSources(false, onPhoto)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u -> TeamFiles.attach(ctx, t, u, null)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }
    }
    if (choose) AlertDialog(
        onDismissRequest = { choose = false },
        title = { Text("Attach") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AttachButton(Icons.Rounded.PhotoCamera, "Camera", Modifier.weight(1f)) { choose = false; camera() }
                    AttachButton(Icons.Rounded.Image, "Gallery", Modifier.weight(1f)) { choose = false; gallery() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AttachButton(Icons.Rounded.Description, "Files", Modifier.weight(1f)) { choose = false; files.launch(arrayOf("*/*")) }
                    AttachButton(Icons.Rounded.Chat, "From WhatsApp", Modifier.weight(1f)) {
                        choose = false
                        TeamFiles.rememberTarget(ctx, t.id)
                        val pkg = WhatsApp.app(ctx)
                        val go = ctx.packageManager.getLaunchIntentForPackage(pkg)
                        if (go == null) Toast.makeText(ctx, "WhatsApp is not on this phone", Toast.LENGTH_SHORT).show()
                        else {
                            Toast.makeText(ctx, "In WhatsApp: hold the photo, voice note or file › Share › RB360", Toast.LENGTH_LONG).show()
                            ctx.startActivity(go)
                        }
                    }
                }
                Text("Files, voice notes and videos up to 15 MB. Photos are made smaller.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { choose = false }) { Text("Cancel") } },
    )
    return { choose = true }
}

@Composable
private fun AttachButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(72.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * N4 RB360 in Android's Share menu. Started from a task's "From WhatsApp": straight into that task. Otherwise a
 * searchable list of his open tasks (team and plot) to pick from.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CrashLog.guard(this, "Team") { TeamRepo.start(this) }
        val i = intent
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (uris.isEmpty() && text.isBlank()) { finish(); return }
        val fromWa = (referrer?.host ?: "").startsWith("com.whatsapp")
        fun send(t: TeamTask) {
            val via = if (fromWa || TeamFiles.target(this) == t.id) "whatsapp" else ""
            val problems = uris.mapNotNull { TeamFiles.attach(this, t, it, if (uris.size == 1) i.type else null, via) }
            if (uris.isEmpty()) TeamRepo.send(t, Team.TEXT, text, extra = if (via.isNotEmpty()) mapOf("via" to via) else emptyMap())
            TeamFiles.clearTarget(this)
            Toast.makeText(this, problems.firstOrNull() ?: "Added to “${t.title.take(40)}”", Toast.LENGTH_LONG).show()
            finish()
        }
        TeamFiles.target(this)?.let { id -> TeamRepo.tasks.value.firstOrNull { it.id == id }?.let { send(it); return } }
        setContent {
            CallbackTheme { SharePicker(onBack = { finish() }, onPick = { send(it) }) }
        }
    }
}

@Composable
private fun SharePicker(onBack: () -> Unit, onPick: (TeamTask) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tasks by TeamRepo.tasks.collectAsState()
    val me = TeamRepo.me.collectAsState().value?.uid
    var q by remember { mutableStateOf("") }
    val open = tasks.filter { it.open && (it.toUid == me || it.fromUid == me) }
        .filter { q.isBlank() || (it.title + " " + it.plotNo + " " + it.toName + " " + it.fromName).contains(q.trim(), ignoreCase = true) }
        .sortedByDescending { it.lastMsgAt.coerceAtLeast(it.createdAt) }
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Add to which task?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(q, { q = it }, placeholder = { Text("Search tasks") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }, shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            if (open.isEmpty()) Text(if (me == null) "Sign in to RB360 first (Settings › Team)." else "No open tasks.",
                color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(open, key = { it.id }) { t ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(t) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(if (t.plot) "Plot ${t.plotNo} · ${t.title}" else t.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (t.plot) "Plot file · Step ${t.step}" else if (t.toUid == me) "From ${t.fromFirst}" else "To ${t.toFirst}",
                            fontSize = 13.sp, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
