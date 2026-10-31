package com.callbackreminder.app

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v0.48 Own My tasks for the team meeting. While the team is on, each person's own tasks (never 🔒 Private, never
 * the ones given by the team, which are already shared) go to teams/{team}/own/{uid}/tasks, where only he and the
 * owner can read them. Only what changed is written; a task made Private or deleted leaves the cloud.
 */
object OwnShare {
    /** Finished tasks older than this stay on the phone only. */
    private const val KEEP_DONE_DAYS = 120L

    private var job: Job? = null

    private fun p(c: Context) = c.getSharedPreferences("own_share", Context.MODE_PRIVATE)

    /** Something changed in My tasks (or the team just came on): write the differences a moment later. */
    fun changed(c: Context) {
        if (!TeamRepo.activeNow) return
        val app = c.applicationContext
        job?.cancel()
        job = TeamRepo.scope.launch {
            delay(3_000L)
            CrashLog.guard(app, "Own share") { push(app) }
        }
    }

    private fun push(c: Context) {
        val team = TeamRepo.teamId ?: return
        val uid = TeamRepo.uid ?: return
        if (!TeamRepo.activeNow) return
        val now = System.currentTimeMillis()
        val keepFrom = now - KEEP_DONE_DAYS * 86_400_000L
        val shared = MyTaskStore.items.value.filter { t ->
            !TeamLinks.isPrivate(c, t.id) && TeamLinks.teamTaskOfMyTask(c, t.id) == null && (!t.done || t.doneAt >= keepFrom)
        }
        val prefs = p(c)
        val key = "sent_$team/$uid"
        val sent = runCatching { org.json.JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrElse { org.json.JSONObject() }
        val col = FirebaseFirestore.getInstance().collection("teams").document(team).collection("own").document(uid).collection("tasks")
        val next = org.json.JSONObject()
        var batch = FirebaseFirestore.getInstance().batch()
        var n = 0
        fun flush() {
            if (n > 0) batch.commit()
            batch = FirebaseFirestore.getInstance().batch()
            n = 0
        }
        shared.forEach { t ->
            val doc = docOf(t)
            val sig = doc.toString().hashCode().toString()
            next.put(t.id.toString(), sig)
            if (sent.optString(t.id.toString()) == sig) return@forEach
            batch.set(col.document(t.id.toString()), doc + ("updatedAt" to now))
            if (++n >= 400) flush()
        }
        // Gone from the shared set: deleted, made 🔒 Private, or old.
        sent.keys().forEach { id ->
            if (!next.has(id)) {
                batch.delete(col.document(id))
                if (++n >= 400) flush()
            }
        }
        flush()
        prefs.edit().putString(key, next.toString()).apply()
    }

    private fun docOf(t: MyTask): Map<String, Any> = mapOf(
        "title" to t.title,
        "person" to t.personTitle,
        "createdAt" to t.createdAt,
        "dueAt" to t.dueAt,
        "firstDueAt" to (t.firstDueAt.takeIf { it > 0L } ?: t.dueAt),
        "moves" to t.moves,
        "done" to t.done,
        "doneAt" to t.doneAt,
        "reasons" to t.reasons.map { mapOf("at" to it.at, "r" to it.reason, "k" to it.kind, "o" to it.outside, "n" to it.note) },
        "events" to t.events.takeLast(40).map { mapOf("at" to it.at, "text" to it.text) },
    )
}
