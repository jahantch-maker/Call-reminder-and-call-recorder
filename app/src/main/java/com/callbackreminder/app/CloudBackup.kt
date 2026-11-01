package com.callbackreminder.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * v0.49 Emergency cloud backup for Rafiq Builders members. At the nightly backup, when the phone is charging and online,
 * the whole app data (tasks including 🔒 Private, Dossier, settings, SMS schedules, voice notes) goes up under his own
 * space in Firebase; the last 7 nights stay. Call recordings are never included. Only he can read it (the rules give the
 * owner no access). A new phone offers "Restore last night's backup?" after sign-in. The phone's own backup is unchanged.
 */
object CloudBackup {
    private const val KEEP = 7
    private const val CHUNK = 600_000 // characters of base64 per Firestore document (limit 1 MB)

    private fun nights(t: String, u: String) =
        FirebaseFirestore.getInstance().collection("teams").document(t).collection("backups").document(u).collection("nights")

    /** Whether this phone is in a Rafiq Builders seat (the only people who back up to the cloud). */
    private fun eligible(): Boolean {
        val me = TeamRepo.me.value ?: return false
        return me.active && TeamRepo.teamId != null && TeamRepo.uid != null
    }

    /** Asks for tonight's upload: runs when the phone is charging and online. Called by the nightly backup. */
    fun request(ctx: Context) {
        if (!eligible()) return
        val req = OneTimeWorkRequestBuilder<CloudBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(true).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("cloud_backup", ExistingWorkPolicy.REPLACE, req)
    }

    /** Uploads tonight's backup, then removes nights older than the last 7. Returns null when it worked. */
    suspend fun upload(ctx: Context, dayName: String? = null): String? = try {
        val t = TeamRepo.teamId ?: return "Not in a team"
        val u = TeamRepo.uid ?: return "Not signed in"
        val raw = Backup.export(ctx).toString().toByteArray(Charsets.UTF_8)
        val zipped = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()
        val text = Base64.encodeToString(zipped, Base64.NO_WRAP)
        val day = dayName ?: LocalDate.now().toString()
        val parts = text.chunked(CHUNK)
        val col = nights(t, u)
        parts.forEachIndexed { i, part -> col.document("$day-${i.toString().padStart(3, '0')}").set(mapOf("day" to day, "i" to i, "data" to part)).await() }
        // The index of the night goes last: a half-written night is never offered for restore.
        col.document(day).set(mapOf("day" to day, "parts" to parts.size, "at" to System.currentTimeMillis(), "bytes" to raw.size)).await()
        if (dayName != null) {
            // The sign-out save must be confirmed by the server before anything is erased from the phone.
            val back = col.document(day).get(Source.SERVER).await()
            if ((back.getLong("parts") ?: 0L).toInt() != parts.size) throw IllegalStateException("Couldn't confirm the save")
        }
        val days = col.get().await().documents.mapNotNull { it.getString("day") }.distinct().sortedDescending()
        days.drop(KEEP).forEach { old ->
            col.get().await().documents.filter { it.getString("day") == old }.forEach { it.reference.delete().await() }
        }
        null
    } catch (e: Exception) {
        e.message?.takeIf { it.isNotBlank() } ?: "Cloud backup failed"
    }

    /** The newest night that is complete: its day and when it was made, or null when there is none. */
    suspend fun latest(): Pair<String, Long>? = try {
        val t = TeamRepo.teamId
        val u = TeamRepo.uid
        if (t == null || u == null) null
        else nights(t, u).whereGreaterThan("parts", 0).get().await().documents
            .mapNotNull { d -> d.getString("day")?.let { it to (d.getLong("at") ?: 0L) } }.maxByOrNull { it.first }
    } catch (e: Exception) {
        null
    }

    /** Every complete night in the cloud, newest first: its day name and when it was made. Empty when there is none. */
    suspend fun available(): List<Pair<String, Long>> = try {
        val t = TeamRepo.teamId
        val u = TeamRepo.uid
        if (t == null || u == null) emptyList()
        else nights(t, u).whereGreaterThan("parts", 0).get().await().documents
            .mapNotNull { d -> d.getString("day")?.let { it to (d.getLong("at") ?: 0L) } }.sortedByDescending { it.second }
    } catch (e: Exception) {
        emptyList()
    }

    /** The day name of the save made at sign-out. */
    const val SIGN_OUT_DAY = "signout"

    private const val OUT = "signout"

    /** The save made when he signs out: null when it is safely online, else why not (then nothing is erased). */
    suspend fun saveForSignOut(ctx: Context): String? = try {
        kotlinx.coroutines.withTimeout(90_000L) { upload(ctx, OUT) }
    } catch (e: Exception) {
        "No internet, so nothing was saved. Try again when you are online."
    }

    /** Removes the sign-out save once it has been used (or turned down). */
    suspend fun deleteSignOut() {
        runCatching {
            val t = TeamRepo.teamId ?: return
            val u = TeamRepo.uid ?: return
            kotlinx.coroutines.withTimeoutOrNull(8000L) {
                nights(t, u).get().await().documents.filter { it.getString("day") == OUT }.forEach { it.reference.delete().await() }
            }
        }
    }

    /** Downloads that night and puts everything back; the app then restarts. Returns null when it worked. */
    suspend fun restore(ctx: Context, day: String): String? = try {
        restoreOrThrow(ctx, day)
    } catch (e: Exception) {
        e.message?.takeIf { it.isNotBlank() } ?: "Couldn't restore"
    }

    private suspend fun restoreOrThrow(ctx: Context, day: String): String? {
        val t = TeamRepo.teamId ?: return "Not in a team"
        val u = TeamRepo.uid ?: return "Not signed in"
        val col = nights(t, u)
        val index = col.document(day).get().await()
        val n = (index.getLong("parts") ?: 0L).toInt()
        if (n <= 0) return "That backup is not complete"
        val sb = StringBuilder()
        for (i in 0 until n) {
            val d = col.document("$day-${i.toString().padStart(3, '0')}").get().await()
            sb.append(d.getString("data") ?: return "Part ${i + 1} of the backup is missing")
        }
        val zipped = Base64.decode(sb.toString(), Base64.NO_WRAP)
        val json = GZIPInputStream(ByteArrayInputStream(zipped)).use { it.readBytes() }
        val file = File(ctx.cacheDir, "cloud-restore.json").apply { writeBytes(json) }
        val err = Backup.restore(ctx, Uri.fromFile(file))
        file.delete()
        if (err == null) {
            if (day == OUT) deleteSignOut()
            Backup.clearWiped(ctx)
            Backup.restartApp(ctx)
        }
        return err
    }
}

/** The nightly upload: waits until the phone is charging and online. */
class CloudBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val err = runCatching { CloudBackup.upload(applicationContext) }.getOrElse { it.message }
        return if (err == null) Result.success() else Result.retry()
    }
}
