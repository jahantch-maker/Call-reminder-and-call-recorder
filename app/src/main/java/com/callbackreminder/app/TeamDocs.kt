package com.callbackreminder.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** One document from the portal's Documents page: a Google Drive link tagged for designations. */
private data class PortalDoc(
    val id: String, val type: String, val title: String, val url: String, val version: Int,
    val designations: List<String>, val part: String,
)

/**
 * v0.49 Documents: the same list as the portal's "My reading list" and "Work documents": his job description first, then his
 * SOPs with "Your part", then tools. Read ✓ is saved for the portal; Need training gives his head a task.
 */
@Composable
fun TeamDocsDialog(me: TeamMember, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf<List<PortalDoc>?>(null) }
    var done by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    val team = TeamRepo.teamId

    LaunchedEffect(tick) {
        val t = team ?: run { error = "You are not in a team."; return@LaunchedEffect }
        try {
            val db = FirebaseFirestore.getInstance().collection("teams").document(t)
            val list = db.collection("docs").get().await().documents.mapNotNull { d ->
                val type = d.getString("type") ?: return@mapNotNull null
                val des = (d.get("designations") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                val parts = d.get("parts") as? Map<*, *>
                PortalDoc(
                    id = d.id, type = type, title = d.getString("title").orEmpty(), url = d.getString("url").orEmpty(),
                    version = (d.getLong("version") ?: 1L).toInt(), designations = des, part = (parts?.get(me.designation) as? String).orEmpty(),
                )
            }.filter { me.designation in it.designations && it.type in setOf("jd", "sop", "work") }
                .sortedWith(compareBy({ when (it.type) { "jd" -> 0; "sop" -> 1; else -> 2 } }, { it.title.lowercase() }))
            val r = db.collection("reading").document(me.uid).get().await()
            val dn = (r.get("done") as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
                (k as? String)?.let { key -> key to (((v as? Map<*, *>)?.get("version") as? Number)?.toInt() ?: 1) }
            }?.toMap() ?: emptyMap()
            done = dn
            docs = list
        } catch (e: Exception) {
            error = "Couldn't load the documents: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun open(url: String) {
        if (url.isBlank()) { Toast.makeText(ctx, "No link yet", Toast.LENGTH_SHORT).show(); return }
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(ctx, "Couldn't open the link", Toast.LENGTH_SHORT).show() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Documents") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val list = docs
                when {
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    list == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    list.isEmpty() -> Text("Nothing for ${me.designation.ifBlank { "your seat" }} yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        val must = list.filter { it.type != "work" }
                        if (must.isNotEmpty()) Text("My reading list · ${must.count { (done[it.id] ?: 0) >= it.version }} of ${must.size}", fontWeight = FontWeight.Bold)
                        list.forEach { d ->
                            val read = (done[d.id] ?: 0) >= d.version
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text((if (d.type == "jd") "Job description · " else if (d.type == "sop") "SOP · " else "Tool · ") + d.title, fontWeight = FontWeight.SemiBold)
                                if (d.part.isNotBlank()) Text("Your part: ${d.part}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(onClick = { open(d.url) }) { Text("Open") }
                                    if (d.type != "work") {
                                        if (read) Text("✓ Read", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                        else {
                                            Button(onClick = {
                                                scope.launch {
                                                    val t = team ?: return@launch
                                                    runCatching {
                                                        FirebaseFirestore.getInstance().collection("teams").document(t).collection("reading").document(me.uid)
                                                            .set(mapOf("done" to mapOf(d.id to mapOf("at" to System.currentTimeMillis(), "version" to d.version))), SetOptions.merge()).await()
                                                    }.onSuccess { tick++ }.onFailure { Toast.makeText(ctx, "Couldn't save. Try again online.", Toast.LENGTH_SHORT).show() }
                                                }
                                            }) { Text("Read ✓") }
                                            TextButton(onClick = {
                                                val head = TeamRepo.member(me.reportsTo)
                                                if (head == null || !head.active) { Toast.makeText(ctx, "You have no head in the chart yet.", Toast.LENGTH_SHORT).show(); return@TextButton }
                                                val id = TeamRepo.give(head, "Train ${me.first} on ${d.title}", System.currentTimeMillis() + 3 * 24 * 3_600_000L, false, null)
                                                val t = team
                                                if (id != null && t != null) {
                                                    FirebaseFirestore.getInstance().collection("teams").document(t).collection("tasks").document(id)
                                                        .update("docRef", mapOf("id" to d.id, "version" to d.version))
                                                    Toast.makeText(ctx, "Asked ${head.first} for training", Toast.LENGTH_SHORT).show()
                                                } else Toast.makeText(ctx, "Couldn't ask. Try again online.", Toast.LENGTH_SHORT).show()
                                            }) { Text("Need training") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
