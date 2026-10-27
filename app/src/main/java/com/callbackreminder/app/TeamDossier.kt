package com.callbackreminder.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/** One block of a member's Dossier, as the Owner set it. [proof]: his tick opens the camera first. */
data class TeamBlock(
    val id: String,
    val title: String,
    val note: String = "",
    val minute: Int,
    val endMinute: Int,
    val days: Int = Dossier.ALL_DAYS,
    val monthDay: Int = 0,
    val everyWeeks: Int = 1,
    val onOffDay: Boolean = false,
    val remindBefore: Int = 0,
    val proof: Boolean = false,
    /** Who added it (uid) and his name then; blocks never change after that, only the Owner deletes them. */
    val by: String = "",
    val byName: String = "",
) {
    /** "Jahanzaib": your name for whoever added it. */
    val byFirst: String get() = TeamRepo.member(by)?.first ?: Names.first(byName) ?: byName

    /** Same days and the times overlap: a block can't sit on top of another one. */
    fun clashes(o: TeamBlock): Boolean {
        val sameDays = monthDay > 0 || o.monthDay > 0 || (days and o.days) != 0
        return sameDays && minute < o.endMinute && o.minute < endMinute
    }

    /** The same block as a dossier task, to show and edit it with the usual Dossier window. */
    fun toTask(localId: Long = 0L) = DossierTask(
        id = localId, title = title, note = note, minute = minute, days = days, monthDay = monthDay,
        remindBefore = remindBefore, startDate = LocalDate.now().toString(), createdAt = 0L,
        endMinute = endMinute, everyWeeks = everyWeeks, onOffDay = onOffDay,
    )

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id, "title" to title, "note" to note, "minute" to minute, "endMinute" to endMinute, "days" to days,
        "monthDay" to monthDay, "everyWeeks" to everyWeeks, "onOffDay" to onOffDay, "remindBefore" to remindBefore, "proof" to proof,
        "by" to by, "byName" to byName,
    )

    val rangeLabel: String get() = Dossier.timeLabel(minute) + " - " + Dossier.timeLabel(endMinute % (24 * 60))
    val frequency: String get() = toTask().frequency

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(12)

        fun of(m: Map<*, *>): TeamBlock? {
            fun int(k: String, d: Int) = (m[k] as? Number)?.toInt() ?: d
            val id = m["id"] as? String ?: return null
            val minute = int("minute", -1).takeIf { it >= 0 } ?: return null
            return TeamBlock(
                id = id, title = m["title"] as? String ?: "", note = m["note"] as? String ?: "", minute = minute,
                endMinute = int("endMinute", minOf(minute + 30, 24 * 60)), days = int("days", Dossier.ALL_DAYS),
                monthDay = int("monthDay", 0), everyWeeks = int("everyWeeks", 1), onOffDay = m["onOffDay"] == true,
                remindBefore = int("remindBefore", 0), proof = m["proof"] == true,
                by = m["by"] as? String ?: "", byName = m["byName"] as? String ?: "",
            )
        }
    }
}

/**
 * Staff Dossier (team edition): the Owner, a senior with "✏ Sets Dossier of people under him" and (with "✏ Adds his
 * own Dossier") the member himself add blocks. Once added a block never moves or changes (Undo for 10 s right after
 * Save); only the Owner deletes one. They arrive in the member's
 * My tasks › Dossier, where he can tick (with the time recorded) but not edit. Blocks with 📷 Photo proof need a
 * photo before the tick; "Why not done?" can carry a voice note. His ticks, reasons and proofs show in his day.
 *
 * Online: teams/{t}/dossier/{uid} holds his blocks; teams/{t}/proofs/{uid_block_date_kind} holds a photo or voice
 * note (kept small, like Timeline media).
 */
object TeamDossier {
    const val PHOTO = "photo"
    const val VOICE = "voice"

    private fun db() = FirebaseFirestore.getInstance()
    private fun team() = TeamRepo.teamId?.let { db().collection("teams").document(it) }

    // ------------------------------------------------------------ Owner side

    /** A member's blocks (null until read; empty when none are set yet). */
    fun watch(uid: String, onChange: (List<TeamBlock>) -> Unit): ListenerRegistration? =
        team()?.collection("dossier")?.document(uid)?.addSnapshotListener { s, _ -> if (s != null) onChange(blocksOf(s)) }

    /** Who may add blocks to [m]'s Dossier: the Owner; a senior with ✏ Sets Dossier; himself with ✏ Adds his own Dossier. */
    fun canAdd(m: TeamMember): Boolean {
        val me = TeamRepo.me.value ?: return false
        if (!me.active || m.role == Team.OWNER) return false
        if (ownerIsMe()) return true
        if (m.uid == me.uid) return me.ownDossier
        return me.setDossier && me.uid in m.managers
    }

    fun ownerIsMe(): Boolean = TeamRepo.uid != null && TeamRepo.team.value?.ownerUid == TeamRepo.uid

    /** A new block waits 10 s for Undo before it goes online; then it is there for good. */
    class Pending(val member: TeamMember, val current: List<TeamBlock>, val block: TeamBlock)
    val pending = MutableStateFlow<Pending?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var commitJob: Job? = null

    fun addLater(m: TeamMember, current: List<TeamBlock>, b: TeamBlock) {
        commitNow()
        val me = TeamRepo.me.value
        val myName = me?.let { it.typed.ifBlank { it.name } }.orEmpty()
        pending.value = Pending(m, current, b.copy(by = me?.uid.orEmpty(), byName = myName))
        commitJob = scope.launch { delay(UNDO_MS); commitNow() }
    }

    fun undo() {
        commitJob?.cancel()
        pending.value = null
    }

    fun commitNow() {
        val p = pending.value ?: return
        pending.value = null
        commitJob?.cancel()
        save(p.member, p.current.filter { it.id != p.block.id } + p.block)
    }

    const val UNDO_MS = 10_000L

    /** Your own blocks as they came from online (to add one to them). */
    @Volatile var mine: List<TeamBlock> = emptyList()
        private set

    /** Saves a member's whole Dossier (the server rules check who may, and that only the Owner removes blocks). */
    fun save(m: TeamMember, blocks: List<TeamBlock>) {
        val t = team() ?: return
        val owner = TeamRepo.team.value?.ownerUid.orEmpty()
        val viewers = (listOf(m.uid, owner) + m.managers).filter { it.isNotBlank() }.distinct()
        t.collection("dossier").document(m.uid).set(
            mapOf(
                "blocks" to blocks.sortedBy { it.minute }.map { it.toMap() },
                "viewers" to viewers, "updatedAt" to System.currentTimeMillis(), "by" to (TeamRepo.uid ?: ""),
            )
        )
    }

    private fun blocksOf(s: DocumentSnapshot): List<TeamBlock> =
        (s.get("blocks") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(TeamBlock::of) }?.sortedBy { it.minute } ?: emptyList()

    /** A proof from his phone (photo or voice note) for one block on one day, or null when there is none. */
    fun fetchProof(uid: String, blockId: String, date: String, kind: String, onResult: (ByteArray?, String, Long) -> Unit) {
        val t = team() ?: return onResult(null, "", 0L)
        t.collection("proofs").document(proofId(uid, blockId, date, kind)).get()
            .addOnSuccessListener { d -> onResult(d.getBlob("data")?.toBytes(), d.getString("caption").orEmpty(), d.getLong("durMs") ?: 0L) }
            .addOnFailureListener { onResult(null, "", 0L) }
    }

    private fun proofId(uid: String, blockId: String, date: String, kind: String) = "${uid}_${blockId}_${date}_$kind"

    // ------------------------------------------------------------ member side

    /** His Dossier changed online: add, change or remove the blocks in his own Dossier. */
    fun apply(c: Context, blocks: List<TeamBlock>) {
        val me = TeamRepo.me.value ?: return
        if (me.role == Team.OWNER) return
        mine = blocks
        DossierStore.init(c)
        synchronized(this) {
            val links = TeamLinks.map(c, "dossier")
            // The first time nothing is announced (it is all new then).
            val seen = TeamLinks.set(c, "dossierSeen")
            val first = seen.isEmpty() && links.isEmpty()
            val proof = mutableSetOf<String>()
            blocks.forEach { b ->
                val local = links[b.id]?.toLongOrNull()?.let { DossierStore.get(it) }
                if (local == null) {
                    val t = DossierStore.add(b.title, b.note, b.minute, b.days, b.monthDay, b.remindBefore, false, b.endMinute, b.everyWeeks, b.onOffDay)
                    links[b.id] = t.id.toString()
                    if (b.proof) proof += t.id.toString()
                    // "✏ Basharat added: Stock check · 12:00 - 12:30 pm · Mon-Sat"
                    if (!first && b.id !in seen && b.by.isNotBlank() && b.by != me.uid) {
                        TeamSync.notify(c, "dossier:${b.id}", "✏ ${b.byFirst} added to your Dossier",
                            "${b.title} · ${b.rangeLabel} · ${b.frequency}", mine = null, kind = NotifKind.DOSSIER)
                    }
                } else {
                    val want = b.toTask(local.id)
                    if (local.title != want.title || local.note != want.note || local.minute != want.minute || local.endMinute != want.endMinute ||
                        local.days != want.days || local.monthDay != want.monthDay || local.everyWeeks != want.everyWeeks ||
                        local.onOffDay != want.onOffDay || local.remindBefore != want.remindBefore
                    ) {
                        DossierStore.edit(local.id, b.title, b.note, b.minute, b.days, b.monthDay, b.remindBefore, null, b.endMinute, b.everyWeeks, b.onOffDay)
                    }
                    if (b.proof) proof += local.id.toString()
                }
            }
            val keep = blocks.map { it.id }.toSet()
            links.keys.filter { it !in keep }.forEach { id ->
                links[id]?.toLongOrNull()?.let { DossierStore.delete(it) }
                links.remove(id)
            }
            TeamLinks.putMap(c, "dossier", links)
            TeamLinks.putSet(c, "dossierProof", proof)
            TeamLinks.putSet(c, "dossierSeen", keep.ifEmpty { setOf("-") })
        }
        TeamSync.version.value++
    }

    /** Set by the Owner: he can tick it, not edit or delete it. */
    fun isTeam(c: Context, localId: Long): Boolean = TeamRepo.activeNow && localId.toString() in TeamLinks.map(c, "dossier").values

    fun blockOf(c: Context, localId: Long): String? = TeamLinks.map(c, "dossier").entries.firstOrNull { it.value == localId.toString() }?.key

    fun needsProof(c: Context, localId: Long): Boolean = TeamRepo.activeNow && localId.toString() in TeamLinks.set(c, "dossierProof")

    /** What he already sent for that day: "photo" / "voice". */
    fun sent(c: Context, localId: Long, date: LocalDate, kind: String): Boolean = "$localId|$date|$kind" in TeamLinks.set(c, "dossierSent")

    /** The name shown as "Set by …". */
    fun ownerFirst(): String = TeamRepo.team.value?.ownerUid?.let { TeamRepo.member(it)?.first } ?: "the owner"

    /** Who added this block of yours ("you" for your own). */
    fun byFirst(c: Context, localId: Long): String {
        val b = blockOf(c, localId)?.let { id -> mine.firstOrNull { it.id == id } } ?: return ownerFirst()
        return if (b.by == TeamRepo.uid) "you" else b.byFirst.ifBlank { ownerFirst() }
    }

    /** A tick waiting for its photo (the window shows on top of whatever is open). */
    val asking = MutableStateFlow<Pair<Long, LocalDate>?>(null)
    @Volatile private var askedAt = 0L

    /**
     * Called before every tick: a 📷 block with no photo for that day yet is held, and the photo window opens
     * (the app comes to the front when the tick came from a notification or widget). True = the tick waits.
     */
    fun holdTick(c: Context, localId: Long, date: LocalDate): Boolean {
        if (!needsProof(c, localId) || sent(c, localId, date, PHOTO)) return false
        asking.value = localId to date
        askedAt = System.currentTimeMillis()
        if (!TeamRepo.foreground) {
            runCatching {
                c.startActivity(
                    Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_MY_TASKS)
                )
            }
        }
        return true
    }

    /** "This day is locked", unless the tick is only waiting for its photo. */
    fun lockedToast(c: Context) {
        if (System.currentTimeMillis() - askedAt < 2_000L) return
        Toast.makeText(c, "This day is locked in History", Toast.LENGTH_SHORT).show()
    }

    /** Sends a photo (then the tick goes through) or a "Why not done?" voice note for one block and day. */
    fun sendProof(c: Context, localId: Long, date: LocalDate, kind: String, data: ByteArray, caption: String = "", durMs: Long = 0L): Boolean {
        val t = team() ?: return false
        val me = TeamRepo.me.value ?: return false
        val block = blockOf(c, localId) ?: return false
        val owner = TeamRepo.team.value?.ownerUid.orEmpty()
        val viewers = (listOf(me.uid, owner) + me.managers).filter { it.isNotBlank() }.distinct()
        t.collection("proofs").document(proofId(me.uid, block, date.toString(), kind)).set(
            mapOf(
                "uid" to me.uid, "blockId" to block, "date" to date.toString(), "kind" to kind, "caption" to caption.trim(),
                "durMs" to durMs, "at" to System.currentTimeMillis(), "viewers" to viewers, "data" to Blob.fromBytes(data),
            )
        )
        val s = TeamLinks.set(c, "dossierSent")
        s += "$localId|$date|$kind"
        // Only the last 60 days are kept on the phone.
        val oldest = LocalDate.now().minusDays(60).toString()
        TeamLinks.putSet(c, "dossierSent", s.filter { it.split('|').getOrNull(1).orEmpty() >= oldest }.toSet())
        TeamSync.version.value++
        return true
    }

    /** He joined the team: his daily list (SMS / WhatsApp) stops, the app brings him his tasks now. */
    fun stopDailyList(c: Context, phone: String) {
        if (phone.isBlank()) return
        DailyList.people(c).filter { key -> SmsStore.sameNumber(key, phone) || key == RecentPeople.key(phone) }
            .forEach { DailyList.setOn(c, it, false) }
    }

    /** Daily-list people who are on the team now (skipped even if still switched on). */
    fun isMemberKey(key: String): Boolean =
        TeamRepo.activeNow && TeamRepo.members.value.any { it.active && it.numbers.any { n -> RecentPeople.key(n) == key || SmsStore.sameNumber(n, key) } }
}
