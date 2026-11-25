package com.callbackreminder.app

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/*
 * Team edition, Phase 1. Tasks you give a team member live online (Firebase, the Rafiq Builders project) and
 * on every phone that may see them; everything else (recordings, calls, texts, contacts, vendor follow-ups)
 * never leaves the phone. Who sees what comes from the chart: a task is seen by the giver, the person it is
 * for, and everyone above that person ("managers").
 */

/** The team: its name, who owns it, and the 6-letter join code. */
data class TeamInfo(val id: String, val name: String, val ownerUid: String, val code: String)

/** One person in the team. [managers] is everyone above them in the chart, nearest first. */
data class TeamMember(
    val uid: String,
    val name: String,
    val email: String,
    val phone: String,
    val role: String,
    val reportsTo: String,
    val managers: List<String>,
    val status: String,
    val joinedAt: Long,
    val lastSeen: Long,
    /** His other numbers (a 2nd SIM, a site phone), picked at approval. [phone] is the ⭐ Main one (calls and SMS). */
    val more: List<String> = emptyList(),
    /** 🟢 His WhatsApp number, blank = the main one. */
    val wa: String = "",
    /** The name he typed when joining. [name] is your contact name for his ⭐ Main number when you have one. */
    val typed: String = "",
    /** ✏ Adds his own Dossier blocks (the Owner's switch). */
    val ownDossier: Boolean = false,
    /** ✏ Sets the Dossier of the people under him (the Owner's switch). */
    val setDossier: Boolean = false,
    /** v0.49 The seat (his official Gmail) and designation in the Rafiq Builders portal; blank = not in a seat yet. */
    val seatId: String = "",
    /** His Google account photo link, saved by his own phone or browser at sign-in; blank = the letter avatar. */
    val photo: String = "",
    val designation: String = "",
    /** 0.58.2 Other jobs this person covers ("Also does" on the website seat): plot steps for them come to him too. */
    val also: List<String> = emptyList(),
    /** v0.49 The app switches the portal set for him (null = none set yet: the old behaviour). */
    val appPerms: Map<String, Boolean>? = null,
    /** v0.49 Whom the chart lets him give tasks to (uids); null = everyone, as before. */
    val giveTo: List<String>? = null,
    /** v0.49.1 He signed out of the team on his phone (shown to his managers until he signs in again). */
    val signedOut: Boolean = false,
) {
    /** One app switch from the portal's Access page; [legacy] is what applies until the portal has set any. */
    fun can(key: String, legacy: Boolean): Boolean = if (role == Team.OWNER && key != "callRec") true else appPerms?.get(key) ?: legacy

    /** Every number of his, Main first: any of them is him. */
    val numbers: List<String> get() = (listOf(phone) + more).filter { it.isNotBlank() }.distinctBy { it.filter(Char::isDigit).takeLast(9) }
    val waNumber: String get() = wa.ifBlank { phone }
    val active: Boolean get() = status == Team.ACTIVE
    /** His designation or one of his "Also does" jobs is this SOP role. */
    fun does(role: String): Boolean = designation == role || role in also
    val pending: Boolean get() = status == Team.PENDING
    val first: String get() = Names.first(name) ?: name.ifBlank { "Member" }
    /** v0.49.3 The app owner shows with the seat's designation ("GM · App owner"); the real company owners are just a designation. */
    val roleLabel: String get() =
        if (role == Team.OWNER) (if (designation.isBlank()) "App owner" else "$designation · App owner") else designation.ifBlank { role.replaceFirstChar { it.uppercase() } }

    /** 🟢 Online now: the app is open on his phone (live, once Realtime Database is on), else opened in the last 15 minutes. */
    fun onlineNow(now: Long = System.currentTimeMillis()): Boolean =
        if (TeamPresence.known(uid)) TeamPresence.online(uid) else lastSeen > 0L && now - lastSeen < 15 * 60_000L

    /** Opened the app today. */
    fun onlineToday(now: Long = System.currentTimeMillis()): Boolean =
        lastSeen > 0L && java.time.Instant.ofEpochMilli(lastSeen).atZone(java.time.ZoneId.systemDefault()).toLocalDate() ==
            java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
}

/**
 * A task one member gave another. [dateBy] is [Team.HIS_DATE] (he picks his date, on or before [deadline]) or
 * [Team.GIVER_DATE] (the giver fixed it; he can only ask for more time). [due] 0 = no date yet.
 */
data class TeamTask(
    val id: String,
    val title: String,
    val fromUid: String,
    val fromName: String,
    val toUid: String,
    val toName: String,
    val toPhone: String,
    val viewers: List<String>,
    val createdAt: Long,
    val deadline: Long,
    val urgent: Boolean,
    val dateBy: String,
    val due: Long,
    val status: String,
    val seenAt: Long,
    val doneAt: Long,
    val checkedAt: Long,
    val moreDue: Long,
    val moreReason: String,
    val moreAt: Long,
    val reason: String,
    val passedTo: String,
    /** v0.44 ✓ Done, ➜ next part: who did the part before (his part stays done in his history, view only). */
    val partFrom: String = "",
    val events: List<TaskEvent>,
    /** ❓ Ask waiting for the giver's answer (the no-date timer pauses meanwhile). */
    val askAt: Long = 0L,
    /** ⚠ Stuck: a blocker, red until the giver answers. */
    val stuckAt: Long = 0L,
    val stuckText: String = "",
    /** The newest message on the task: when, from whom, and a short preview. */
    val lastMsgAt: Long = 0L,
    val lastMsgBy: String = "",
    val lastMsgText: String = "",
    /** 📷 Photo proof needed: his Done asks for a photo. [proofAt] is when he sent it. */
    val photoProof: Boolean = false,
    val proofAt: Long = 0L,
    val mediaCleared: Boolean = false,
    /** His phone received it (WhatsApp's two grey ticks); 0 = not yet. */
    val deliveredAt: Long = 0L,
    /** Date rules when it was given: his deadline can't be moved once set, and how often he may push it without asking. */
    val locked: Boolean = true,
    val freeExt: Int = 0,
    /** Times the deadline was pushed (approved, or within the free ones). */
    val extCount: Int = 0,
    /** v0.47 the first deadline ever set: the on-time mark is measured against it (0 = none yet, or an older task). */
    val firstDue: Long = 0L,
    /** His own reminder (only his phone rings for it); 0 = at the deadline. */
    val reminderAt: Long = 0L,
    /** Messages sent on the Timeline, by sender; photos and voice notes in all. */
    val nBy: Map<String, Long> = emptyMap(),
    val nPhoto: Int = 0,
    val nVoice: Int = 0,
    /** The newest message each person's phone has received (two grey ticks on a bubble). */
    val msgDel: Map<String, Long> = emptyMap(),
    /** Read receipts kept on the task (small writes that always go through): the newest message each person has
     *  seen on the Timeline, and the voice notes / photos each person has played or opened. */
    val msgSeen: Map<String, Long> = emptyMap(),
    val played: Map<String, List<String>> = emptyMap(),
    /** v0.48 🤝 Meeting: an action agreed in a team meeting (given from the meeting web app). */
    val meeting: Boolean = false,
    /** Plot files: one step of a plot file (its rules come from the published SOP, see [PlotFiles]). */
    val plot: Boolean = false,
    /** Plot files: the step's timer, days and hours (from the SOP), and whether the doer's phone has set its due time yet. */
    val timer: String = "",
    val days: Int? = null,
    val hours: Int? = null,
    val dueSet: Boolean = true,
    val plotId: String = "",
    val plotNo: String = "",
    val step: String = "",
    val role: String = "",
    val test: Boolean = false,
    /** Checklist line -> who ticked it, when, and the picked option or typed number. */
    val ticks: Map<String, PlotTick> = emptyMap(),
    /** "Waiting for plot owner" started (0 = not waiting): the timer stops meanwhile. */
    val waitAt: Long = 0L,
    /** The role of the step that opened this one ("" for the first step). */
    val fromRole: String = "",
    /** The plot owner, copied from the file, so the card can show and call him without reading the file. */
    val ownerName: String = "",
    val ownerWa: String = "",
    val ownerSim2: String = "",
    /** Picks made in earlier steps of the file ("2.2·3" -> "3b"), copied on by the cloud for "Shown only if" lines. */
    val picks: Map<String, String> = emptyMap(),
    /** Plot files: who is CC on this step and for what (uid -> "start,done,late"), written by the cloud from the SOP's Notify columns. */
    val cc: Map<String, String> = emptyMap(),
) {
    /** [uid] has seen [m] on the Timeline (blue ticks). */
    fun saw(m: TeamMsg, uid: String): Boolean = uid in m.seenBy || (msgSeen[uid] ?: 0L) >= m.at
    /** [uid] played the voice note / opened the photo [m]. */
    fun playedBy(m: TeamMsg, uid: String): Boolean = uid in m.playedBy || m.id in played[uid].orEmpty()

    /** His deadline is set and can't be moved by him any more (only 📅 Extend deadline). */
    val deadlineLocked: Boolean get() = locked && due > 0L
    /** Messages from everyone but [uid]. */
    fun msgsFromOthers(uid: String?): Long = nBy.filterKeys { it != uid }.values.sum()
    val asks: Boolean get() = open && askAt > 0L
    val stuck: Boolean get() = open && stuckAt > 0L
    val open: Boolean get() = status in Team.OPEN_STATES
    val hisDate: Boolean get() = dateBy == Team.HIS_DATE
    /** He asked for more time and you haven't answered yet. */
    val asking: Boolean get() = open && moreAt > 0L
    val doneCheck: Boolean get() = status == Team.DONE
    // Your contact names win: the stored names are only for people not in the team list.
    val toFirst: String get() = TeamRepo.member(toUid)?.first ?: Names.first(toName) ?: toName
    val fromFirst: String get() = TeamRepo.member(fromUid)?.first ?: Names.first(fromName) ?: fromName
}

object Team {
    const val PENDING = "pending"
    const val ACTIVE = "active"
    const val REMOVED = "removed"

    /** The pilot company: staff only see "Ask to join" this team. */
    const val HOME_TEAM = "Rafiq Builders"
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val MEMBER = "member"

    const val HIS_DATE = "his"
    const val GIVER_DATE = "giver"

    const val SENT = "sent"
    const val SEEN = "seen"
    const val DATED = "dated"
    const val DONE = "done"
    const val CHECKED = "checked"
    const val REOPENED = "reopened"
    const val RETURNED = "returned"
    const val CANCELLED = "cancelled"
    val OPEN_STATES = setOf(SENT, SEEN, DATED, REOPENED)

    /** Letters that can't be mixed up when read out (no I, O, L, 0, 1). */
    private const val CODE_LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    fun newCode(): String = (1..6).map { CODE_LETTERS.random() }.joinToString("")

    const val TEXT = "text"
    const val VOICE = "voice"
    const val PHOTO = "photo"
    const val ASK = "ask"
    const val STUCK = "stuck"
    const val PROOF = "proof"
    const val BRIEF = "brief"

    /** Photos and voice notes are kept this long after a task closes; the text stays. */
    const val MEDIA_DAYS = 90L

    /** What a message's text becomes when its sender undoes it ("🚫 This message was deleted"). */
    const val DELETED = "\u0001deleted"
    /** Your own message can be undone for one hour. */
    const val UNDO_MS = 3_600_000L

    fun canUndo(m: TeamMsg, me: String?): Boolean =
        me != null && m.fromUid == me && !m.isDeleted && !m.pending && System.currentTimeMillis() - m.at < UNDO_MS
}

/** Plot files: one ticked checklist line. [value] is the picked option ("3b") or the typed number, else "". */
data class PlotTick(val at: Long, val name: String, val value: String)

/** One message on a task's Timeline: typed, a voice note or a photo; [tag] marks ❓ Ask, ⚠ Stuck, proof or the brief. */
data class TeamMsg(
    val id: String,
    val at: Long,
    val fromUid: String,
    val fromName: String,
    val kind: String,
    val tag: String,
    val text: String,
    val data: ByteArray?,
    val durMs: Long,
    /** Who saw it on the Timeline, and when (blue ticks once everyone has). */
    val seenBy: Map<String, Long> = emptyMap(),
    /** A voice note played / a photo opened full screen, by whom and when. */
    val playedBy: Map<String, Long> = emptyMap(),
    /** Not on the server yet (one grey tick shows a clock). */
    val pending: Boolean = false,
    /** A voice note's wave, one digit (1-9) per bar ("" for notes from before v0.42). */
    val wave: String = "",
) {
    val fromFirst: String get() = TeamRepo.member(fromUid)?.first ?: Names.first(fromName) ?: fromName
    /** Taken back by its sender: only "🚫 This message was deleted" is left (see [TeamRepo.deleteMsg]). */
    val isDeleted: Boolean get() = text == Team.DELETED
}

/** Settings › Team › Date rules: when a missing date or an unseen task comes to you. Kept on this phone. */
object TeamRules {
    private fun p(c: Context) = c.getSharedPreferences("team_rules", Context.MODE_PRIVATE)
    val NO_DATE_CHOICES = listOf(60, 120, 240, 480, 24 * 60)
    val URGENT_CHOICES = listOf(30, 60, 120, 240)
    val SMS_CHOICES = listOf(0, 60, 120, 240)

    fun noDateMin(c: Context) = p(c).getInt("no_date", 240)
    fun setNoDateMin(c: Context, m: Int) = p(c).edit().putInt("no_date", m).apply()
    fun urgentMin(c: Context) = p(c).getInt("urgent", 60)
    fun setUrgentMin(c: Context, m: Int) = p(c).edit().putInt("urgent", m).apply()
    /** v0.55 An urgent task with no deadline: a reminder to both of you every N working hours until he picks one. */
    val REPEAT_CHOICES = listOf(60, 120, 240)
    fun repeatMin(c: Context) = p(c).getInt("repeat", 120)
    fun setRepeatMin(c: Context, m: Int) = p(c).edit().putInt("repeat", m).apply()
    fun smsMin(c: Context) = p(c).getInt("sms", 120)
    fun setSmsMin(c: Context, m: Int) = p(c).edit().putInt("sms", m).apply()
    fun workingOnly(c: Context) = p(c).getBoolean("working_only", true)
    fun setWorkingOnly(c: Context, on: Boolean) = p(c).edit().putBoolean("working_only", on).apply()

    /** Deadline locked: once he sets it he can only 📅 Extend deadline (your Approve, or a free push). */
    fun deadlineLocked(c: Context) = p(c).getBoolean("locked", true)
    fun setDeadlineLocked(c: Context, on: Boolean) = p(c).edit().putBoolean("locked", on).apply()
    /** Extend without asking: how many times he may push it himself (0 = Never). */
    val FREE_EXT_CHOICES = listOf(0, 1, 2, 3)
    fun freeExt(c: Context) = p(c).getInt("free_ext", 0)
    fun setFreeExt(c: Context, n: Int) = p(c).edit().putInt("free_ext", n).apply()
    fun extLabel(n: Int): String = if (n <= 0) "Never" else "${n}×"

    /** ↩ Not done yet · the quick WHY chips, yours to change in Settings › Team. */
    val NOT_DONE_DEFAULT = listOf("Photo missing", "Incomplete", "Wrong site", "Not as asked", "Quality")
    fun notDoneReasons(c: Context): List<String> =
        p(c).getString("not_done", null)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: NOT_DONE_DEFAULT
    fun setNotDoneReasons(c: Context, list: List<String>) =
        p(c).edit().putString("not_done", list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")).apply()

    fun label(m: Int): String = when {
        m <= 0 -> "Off"
        m >= 24 * 60 -> "1 day"
        m % 60 == 0 -> "${m / 60}h"
        else -> "${m}m"
    }

    /** Minutes from [from] to [to], counting only working hours (weekly off day skipped) when that's on. */
    /** One working day in minutes: your working hours, else the office's 11 AM - 9 PM. */
    fun dayMin(c: Context): Long = if (WorkHours.isSet(c)) (WorkHours.end(c) - WorkHours.start(c)).coerceAtLeast(60).toLong() else 600L

    fun minutesSince(c: Context, from: Long, to: Long = System.currentTimeMillis()): Long {
        if (to <= from) return 0L
        if (!workingOnly(c)) return (to - from) / 60_000L
        val zone = java.time.ZoneId.systemDefault()
        var day = java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val last = java.time.Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        var total = 0L
        var guard = 0
        while (!day.isAfter(last) && guard++ < 60) {
            if (!OffDays.isOff(day) && !Seats.isHoliday(c, day)) {
                // Working hours from Settings, else the office's 11 AM - 9 PM.
                val w = WorkHours.window(c, day) ?: if (!WorkHours.isSet(c)) CalendarData.at(day, 11 * 60) to CalendarData.at(day, 21 * 60) else null
                w?.let { (s, e) ->
                    val a = maxOf(s, from)
                    val b = minOf(e, to)
                    if (b > a) total += (b - a) / 60_000L
                }
            }
            day = day.plusDays(1)
        }
        return total
    }
}

/**
 * The online part: sign-in, the team, its members and the tasks you can see. Listens while the app is open;
 * [TeamWorker] checks every 15 minutes when it isn't.
 */
object TeamRepo {
    private lateinit var appCtx: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _user = MutableStateFlow<FirebaseUser?>(null)
    val user: StateFlow<FirebaseUser?> = _user.asStateFlow()
    private val _team = MutableStateFlow<TeamInfo?>(null)
    val team: StateFlow<TeamInfo?> = _team.asStateFlow()
    private val _me = MutableStateFlow<TeamMember?>(null)
    val me: StateFlow<TeamMember?> = _me.asStateFlow()
    private val _members = MutableStateFlow<List<TeamMember>>(emptyList())
    val members: StateFlow<List<TeamMember>> = _members.asStateFlow()
    private val _tasks = MutableStateFlow<List<TeamTask>>(emptyList())
    val tasks: StateFlow<List<TeamTask>> = _tasks.asStateFlow()

    /** The app is on screen: tasks that arrive now count as Seen. */
    @Volatile var foreground = false

    private var listeners = listOf<ListenerRegistration>()
    private var listeningFor: String? = null

    private fun p() = appCtx.getSharedPreferences("team", Context.MODE_PRIVATE)
    private fun db() = FirebaseFirestore.getInstance()
    private fun auth() = FirebaseAuth.getInstance()

    val teamId: String? get() = if (::appCtx.isInitialized) p().getString("team_id", null) else null

    /** The team this phone is in (or asked to join), for screens. */
    private val _joined = MutableStateFlow<String?>(null)
    val joined: StateFlow<String?> = _joined.asStateFlow()

    private fun setTeamId(id: String?) {
        if (id == null) p().edit().remove("team_id").apply() else p().edit().putString("team_id", id).apply()
        _joined.value = id
    }
    val uid: String? get() = runCatching { auth().currentUser?.uid }.getOrNull()

    /** In a team and approved: the team parts of the app show. */
    val activeNow: Boolean get() = _me.value?.active == true && _team.value != null

    @Synchronized
    fun init(c: Context) {
        if (::appCtx.isInitialized) return
        appCtx = c.applicationContext
        runCatching { _user.value = auth().currentUser }
        _joined.value = teamId
    }

    /** Starts listening (the app opened). Safe to call again. */
    fun start(c: Context) {
        init(c)
        CrashLog.guard(appCtx, "Team start") {
            _user.value = auth().currentUser
            listen()
            TeamSync.start(appCtx)
        }
    }

    private fun listen() {
        val u = auth().currentUser ?: return stopListening()
        val t = teamId ?: return stopListening()
        val key = "${u.uid}/$t"
        if (listeningFor == key) return
        stopListening()
        listeningFor = key
        val team = db().collection("teams").document(t)
        listeners = listOf(
            team.addSnapshotListener { s, e -> if (e != null) relisten() else s?.let { _team.value = teamOf(it) } },
            team.collection("members").document(u.uid).addSnapshotListener { s, e ->
                if (e != null) return@addSnapshotListener relisten()
                if (s == null) return@addSnapshotListener
                if (!s.exists()) {
                    // Declined or removed: leave quietly.
                    if (!s.metadata.isFromCache) leaveLocal()
                    return@addSnapshotListener
                }
                val m = named(memberOf(s))
                val was = _me.value
                _me.value = m
                if (m.status == Team.REMOVED) { removed(t); return@addSnapshotListener }
                Seats.applyMine(appCtx, m.uid, m.seatId)
                if (m.active && was?.active != true) {
                    listenMembersAndTasks(t, u.uid)
                    TeamWorker.schedule(appCtx)
                    // v0.48 the owner's late reasons, and own tasks shared for team meetings.
                    LateReasons.refresh(appCtx)
                    OwnShare.changed(appCtx)
                }
            },
        )
    }

    private var inner = listOf<ListenerRegistration>()
    @Volatile private var relistening = false

    /** A listener stopped with an error (no network for long, token expired): attach all again in a few seconds. */
    private fun relisten() {
        if (relistening) return
        relistening = true
        scope.launch {
            kotlinx.coroutines.delay(5_000L)
            relistening = false
            if (!::appCtx.isInitialized) return@launch
            CrashLog.guard(appCtx, "Team relisten") {
                stopListening()
                listen()
            }
        }
    }

    private fun listenMembersAndTasks(t: String, me: String) {
        inner.forEach { it.remove() }
        val team = db().collection("teams").document(t)
        inner = listOf(
            team.collection("members").addSnapshotListener { s, e ->
                if (e != null) return@addSnapshotListener relisten()
                s ?: return@addSnapshotListener
                _members.value = s.documents.map { named(memberOf(it)) }.sortedBy { it.name.lowercase() }
            },
            team.collection("tasks").whereArrayContains("viewers", me).addSnapshotListener { s, e ->
                if (e != null) return@addSnapshotListener relisten()
                s ?: return@addSnapshotListener
                val list = s.documents.map { taskOf(it) }
                _tasks.value = list
                val fromServer = !s.metadata.isFromCache
                scope.launch { CrashLog.guard(appCtx, "Team sync") { TeamSync.apply(appCtx, list, foreground, complete = fromServer) } }
            },
            // v0.53 Tasks he asks help for or helps with. An error (rules not updated yet) is ignored: it must never restart the rest.
            team.collection("helps").whereArrayContains("viewers", me).addSnapshotListener { s, e ->
                if (e != null || s == null) return@addSnapshotListener
                scope.launch { CrashLog.guard(appCtx, "Help sync") { HelpRepo.onSnapshot(appCtx, s.documents) } }
            },
            // His Dossier, as the Owner set it.
            team.collection("dossier").document(me).addSnapshotListener { s, _ ->
                if (s == null || !s.exists()) return@addSnapshotListener
                val blocks = (s.get("blocks") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(TeamBlock::of) } ?: emptyList()
                scope.launch { CrashLog.guard(appCtx, "Team dossier") { TeamDossier.apply(appCtx, blocks) } }
            },
        ) + Seats.listen(appCtx, team) {
            // The seat name is the only name (locked 23 Nov); your timetable is your working hours.
            _members.value = _members.value.map(::named).sortedBy { it.name.lowercase() }
            _me.value?.let { m -> _me.value = named(m); Seats.applyMine(appCtx, m.uid, m.seatId) }
        }
        touch(force = true)
    }

    private fun stopListening() {
        listeners.forEach { it.remove() }
        inner.forEach { it.remove() }
        listeners = emptyList()
        inner = emptyList()
        listeningFor = null
    }

    private var aliveJob: kotlinx.coroutines.Job? = null

    /**
     * The app came to the front (or left it): tasks sent to you count as delivered (Seen only once opened), "last seen" is
     * written at once and every 10 minutes while it stays open, and the tasks are read fresh from the server
     * (a listener can miss changes after a long time in the background).
     */
    fun onForeground(c: Context, on: Boolean) {
        foreground = on
        aliveJob?.cancel()
        if (::appCtx.isInitialized && activeNow) CrashLog.guard(appCtx, "Team presence") { TeamPresence.onForeground(on) }
        if (!on || !::appCtx.isInitialized) return
        CrashLog.guard(appCtx, "Team front") {
            if (auth().currentUser == null || teamId == null) return@guard
            listen()
            touch(force = true)
            aliveJob = scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(10 * 60_000L)
                    if (!foreground) break
                    touch(force = true)
                }
            }
            if (!activeNow) return@guard
            val u = uid ?: return@guard
            _tasks.value.filter { it.toUid == u && it.open && it.deliveredAt == 0L }.forEach { markDelivered(it) }
            TeamSync.pushLocal(appCtx)
            scope.launch { runCatching { fetchOnce(appCtx) }.onFailure { CrashLog.record(appCtx, it, "Team refresh") } }
        }
    }

    /** "Last seen" for the others. [force] writes now; otherwise at most every 10 minutes. Counted only once saved. */
    fun touch(force: Boolean = false) {
        val u = uid ?: return
        val t = teamId ?: return
        val now = System.currentTimeMillis()
        if (!force && now - p().getLong("touched", 0L) < 10 * 60_000L) return
        db().collection("teams").document(t).collection("members").document(u).update(mapOf("lastSeen" to now, "signedOut" to false))
            .addOnSuccessListener { p().edit().putLong("touched", now).apply() }
        // His Google photo for the avatars (web and phone). A write of its own, so older rules never block "last seen".
        val photo = auth().currentUser?.photoUrl?.toString().orEmpty()
        if (photo.isNotEmpty() && photo != _me.value?.photo)
            db().collection("teams").document(t).collection("members").document(u).update("photo", photo)
    }

    // ------------------------------------------------------------ sign-in

    /** Google sign-in. Returns null when it worked, else what went wrong in plain words. */
    suspend fun signIn(activity: Activity): String? = try {
        init(activity)
        val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(activity).getCredential(activity, request)
        val cred = result.credential
        if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val google = GoogleIdTokenCredential.createFrom(cred.data)
            auth().signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
            _user.value = auth().currentUser
            p().edit().remove("signed_out_note").apply()
            findMyTeam()
            start(activity)
            if (teamId == null) autoJoin() else null
        } else "Google sign-in didn't return an account."
    } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
        // Google also reports some real failures as a "cancel" (e.g. "[16] Account reauth failed" when this
        // app's signing key isn't registered in Firebase), so only a cancel by the user reads as cancelled.
        val m = e.message.orEmpty()
        when {
            m.contains("reauth", ignoreCase = true) ->
                "Couldn't sign in: Google didn't accept this copy of the app. Ask your manager to check the app's key in Firebase. ($m)"
            m.isBlank() || m.contains("by the user", ignoreCase = true) -> "Sign-in cancelled."
            else -> "Couldn't sign in: $m"
        }
    } catch (e: Exception) {
        "Couldn't sign in: ${e.message ?: e.javaClass.simpleName}"
    }

    /** After sign-in on a new phone: the team you were already in. */
    private suspend fun findMyTeam() {
        val u = auth().currentUser ?: return
        if (teamId != null) return
        val t = runCatching { db().collection("users").document(u.uid).get().await().getString("teamId") }.getOrNull()
        if (!t.isNullOrEmpty()) setTeamId(t)
    }

    fun signOut(onDone: () -> Unit = {}) {
        runCatching { TeamPresence.onForeground(false) }
        val u = uid
        val t = teamId
        if (u == null || t == null) {
            finishSignOut()
            onDone()
            return
        }
        // Tell his managers first (while he is still signed in); never wait more than a few seconds for it.
        scope.launch {
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(4000L) {
                    db().collection("teams").document(t).collection("members").document(u).update("signedOut", true).await()
                }
            }
            finishSignOut()
            onDone()
        }
    }

    private fun finishSignOut() {
        stopListening()
        runCatching { auth().signOut() }
        clearLocal()
    }

    private fun clearLocal() {
        p().edit().remove("touched").apply()
        setTeamId(null)
        _user.value = runCatching { auth().currentUser }.getOrNull()
        _team.value = null
        _me.value = null
        _members.value = emptyList()
        _tasks.value = emptyList()
        Seats.clear(appCtx)
        TeamWorker.cancel(appCtx)
    }

    /** Removed from the team (or the join was declined): the team parts go; your own tasks stay. */
    private fun leaveLocal() {
        stopListening()
        clearLocal()
    }

    // ------------------------------------------------------------ team

    /** Makes a new team with you as its Owner. Returns null when it worked, else the problem. */
    suspend fun createTeam(name: String, myPhone: String): String? = try {
        val u = auth().currentUser ?: throw IllegalStateException("Sign in first")
        var code = Team.newCode()
        repeat(5) { if (db().collection("codes").document(code).get().await().exists()) code = Team.newCode() }
        val ref = db().collection("teams").document()
        val now = System.currentTimeMillis()
        ref.set(mapOf("name" to name.trim(), "ownerUid" to u.uid, "code" to code, "createdAt" to now)).await()
        ref.collection("members").document(u.uid).set(
            memberMap(u, myPhone, Team.OWNER, "", emptyList(), Team.ACTIVE, now)
        ).await()
        db().collection("codes").document(code).set(mapOf("teamId" to ref.id, "teamName" to name.trim())).await()
        db().collection("users").document(u.uid).set(mapOf("teamId" to ref.id)).await()
        setTeamId(ref.id)
        start(appCtx)
        null
    } catch (e: Exception) {
        "Couldn't make the team: ${e.message ?: e.javaClass.simpleName}"
    }

    /** The company's team ("Rafiq Builders" for the pilot): its id and name, or null when it can't be found. */
    suspend fun findHomeTeam(): Pair<String, String>? = runCatching {
        db().collection("codes").whereEqualTo("teamName", Team.HOME_TEAM).limit(1).get().await().documents.firstOrNull()
            ?.let { d -> d.getString("teamId")?.let { it to (d.getString("teamName") ?: Team.HOME_TEAM) } }
    }.getOrNull()

    /** "Your Gmail isn't added yet …": shown with Try again (locked 23 Nov: no join form, no request). */
    const val NOT_ADDED = "Your Gmail isn't added yet. Ask your manager to add it, then tap Try again."

    /**
     * Signed in with Google (locked 23 Nov): a Gmail that has a seat on the website joins it at once, with nothing typed;
     * the name and official phone come from the seat. Any other Gmail is told to ask the manager. Null = joined.
     */
    suspend fun autoJoin(): String? = try {
        val u = auth().currentUser ?: throw IllegalStateException("Sign in first")
        val mail = u.email?.trim()?.lowercase().orEmpty()
        val t = teamId ?: findHomeTeam()?.first ?: throw IllegalStateException("Couldn't find ${Team.HOME_TEAM}. Check the internet, then tap Try again.")
        val seat = if (mail.isEmpty()) null
            else db().collection("teams").document(t).collection("seats").document(mail).get(Source.SERVER).await().takeIf { it.exists() }
        if (seat == null) NOT_ADDED
        else {
            val now = System.currentTimeMillis()
            val name = seat.getString("name").orEmpty().ifBlank { u.displayName.orEmpty() }
            db().collection("teams").document(t).collection("members").document(u.uid).set(
                memberMap(u, seat.getString("phone").orEmpty(), Team.MEMBER, "", emptyList(), Team.ACTIVE, now, name) + mapOf("seatId" to mail)
            ).await()
            p().edit().putString("welcome", "Welcome, $name" + seat.getString("designation").orEmpty().let { if (it.isBlank()) "" else " · $it" }).apply()
            db().collection("users").document(u.uid).set(mapOf("teamId" to t)).await()
            setTeamId(t)
            start(appCtx)
            null
        }
    } catch (e: Exception) {
        if (e.message?.contains("PERMISSION_DENIED") == true) NOT_ADDED
        else e.message?.takeIf { e is IllegalStateException } ?: "Couldn't join: ${e.message ?: e.javaClass.simpleName}"
    }

    /** "Welcome, Name · Designation": set when he joined his seat. Null = already settled. */
    fun welcome(): String? = if (::appCtx.isInitialized) p().getString("welcome", null) else null
    fun clearWelcome() { if (::appCtx.isInitialized) p().edit().remove("welcome").apply() }

    /** Why this phone was signed out of the team (his Gmail was changed or removed on the website), for the sign-in card. */
    fun signedOutNote(): String? = if (::appCtx.isInitialized) p().getString("signed_out_note", null) else null

    /**
     * Removed on the website. When his seat's Gmail is gone too (the manager changed it to a new Gmail, or removed the seat),
     * he is signed out of Google here and asked to sign in again with the Gmail his manager gave him.
     */
    private fun removed(t: String) {
        val mail = auth().currentUser?.email?.trim()?.lowercase().orEmpty()
        scope.launch {
            val gone = mail.isNotEmpty() && runCatching {
                !db().collection("teams").document(t).collection("seats").document(mail).get(Source.SERVER).await().exists()
            }.getOrDefault(false)
            if (gone) {
                p().edit().putString("signed_out_note", "You're signed out of ${Team.HOME_TEAM}. If your manager gave you a new Gmail, sign in with it.").apply()
                finishSignOut()
            } else leaveLocal()
        }
    }

    /**
     * Pilot: the people who were Admins become Members (only the Owner approves and edits the chart). Once, on the
     * Owner's phone.
     */
    suspend fun adminsToMembers() {
        val me = _me.value ?: return
        if (me.role != Team.OWNER || p().getBoolean("admins_to_members", false)) return
        _members.value.filter { it.active && it.role == Team.ADMIN }.forEach { setChartNow(it, it.reportsTo, Team.MEMBER, MemberNumbers.of(it), it.ownDossier, it.setDossier) }
        p().edit().putBoolean("admins_to_members", true).apply()
    }

    /** Cancels your own join request (or leaves the team). */
    suspend fun leave(): String? = try {
        val u = auth().currentUser
        val t = teamId
        if (u != null && t != null) db().collection("teams").document(t).collection("members").document(u.uid).delete().await()
        if (u != null) runCatching { db().collection("users").document(u.uid).delete().await() }
        leaveLocal()
        null
    } catch (e: Exception) {
        "Couldn't leave: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun memberMap(
        u: FirebaseUser, phone: String, role: String, reportsTo: String, managers: List<String>, status: String,
        now: Long, name: String? = null,
    ) = mapOf(
        "name" to (name?.trim()?.ifBlank { null } ?: u.displayName.orEmpty()),
        "email" to u.email.orEmpty(),
        "phone" to phone.trim(),
        "role" to role,
        "reportsTo" to reportsTo,
        "managers" to managers,
        "status" to status,
        "joinedAt" to now,
        "lastSeen" to now,
    )

    private fun members() = db().collection("teams").document(teamId!!).collection("members")
    private fun tasksRef() = db().collection("teams").document(teamId!!).collection("tasks")

    /** v0.53 The help threads (@mentions): teams/{team}/helps. */
    internal fun helpsRef() = teamId?.let { db().collection("teams").document(it).collection("helps") }

    /** Everyone above [reportsTo] including them, nearest first (stops at loops). */
    fun chainFrom(reportsTo: String): List<String> {
        val byId = _members.value.associateBy { it.uid }
        val out = mutableListOf<String>()
        var cur = reportsTo
        while (cur.isNotEmpty() && cur !in out && out.size < 20) {
            out += cur
            cur = byId[cur]?.reportsTo.orEmpty()
        }
        return out
    }

    /** The Owner lets someone in: his numbers (⭐ Main, others, 🟢 WhatsApp), who he reports to and his role. */
    suspend fun approve(c: Context, m: TeamMember, nums: MemberNumbers, reportsTo: String, role: String): String? = try {
        val phone = nums.main.trim()
        val more = nums.ticked.map { it.trim() }.filter { it != phone }
        members().document(m.uid).update(
            mapOf(
                "status" to Team.ACTIVE, "phone" to phone, "more" to more, "wa" to nums.wa.trim(),
                "reportsTo" to reportsTo, "role" to role, "managers" to chainFrom(reportsTo),
            )
        ).await()
        (listOf(phone) + more).forEach { TeamDossier.stopDailyList(c, it) }
        val moved = TeamSync.moveOpenFollowUps(c, m.copy(phone = phone, more = more, wa = nums.wa.trim(), reportsTo = reportsTo,
            managers = chainFrom(reportsTo), status = Team.ACTIVE))
        if (moved > 0) "Moved $moved open follow-up${if (moved == 1) "" else "s"} to ${m.first}'s app" else null
    } catch (e: Exception) {
        "Couldn't approve: ${e.message ?: e.javaClass.simpleName}"
    }

    suspend fun decline(m: TeamMember): String? = try {
        members().document(m.uid).delete().await()
        null
    } catch (e: Exception) {
        "Couldn't decline: ${e.message ?: e.javaClass.simpleName}"
    }

    /**
     * Chart, role and number changes; everyone under [m] gets their manager list redone too. Open follow-ups under
     * a newly ticked number move to his app, the same as on Approve.
     */
    suspend fun setChart(
        c: Context, m: TeamMember, reportsTo: String, role: String, nums: MemberNumbers, ownDossier: Boolean, setDossier: Boolean,
    ): String? {
        if (reportsTo == m.uid || m.uid in chainFrom(reportsTo)) return "${m.first} can't report to someone under them."
        setChartNow(m, reportsTo, role, nums, ownDossier, setDossier)?.let { return it }
        val now = _members.value.firstOrNull { it.uid == m.uid } ?: return null
        if (!now.active) return null
        now.numbers.forEach { TeamDossier.stopDailyList(c, it) }
        val moved = TeamSync.moveOpenFollowUps(c, now)
        return if (moved > 0) "Moved $moved open follow-up${if (moved == 1) "" else "s"} to ${now.first}'s app" else null
    }

    private suspend fun setChartNow(
        m: TeamMember, reportsTo: String, role: String, nums: MemberNumbers, ownDossier: Boolean, setDossier: Boolean,
    ): String? = try {
        val phone = nums.main.trim()
        val more = nums.ticked.map { it.trim() }.filter { it != phone }
        val wa = nums.wa.trim()
        members().document(m.uid).update(
            mapOf("reportsTo" to reportsTo, "role" to role, "phone" to phone, "more" to more, "wa" to wa, "managers" to chainFrom(reportsTo),
                "ownDossier" to ownDossier, "setDossier" to setDossier)
        ).await()
        // Local copy first, so the people under them get the new chain.
        _members.value = _members.value.map {
            if (it.uid == m.uid) it.copy(reportsTo = reportsTo, role = role, phone = phone, more = more, wa = wa, managers = chainFrom(reportsTo),
                ownDossier = ownDossier, setDossier = setDossier) else it
        }
        _members.value.filter { m.uid in it.managers }.forEach { under ->
            members().document(under.uid).update("managers", chainFrom(under.reportsTo)).await()
        }
        null
    } catch (e: Exception) {
        "Couldn't save: ${e.message ?: e.javaClass.simpleName}"
    }

    /**
     * Remove member: access ends now. His open tasks from you go to [giveTo] (same task, Timeline and deadline), or
     * with null come back to your To schedule. Tasks others gave him go back to them.
     */
    suspend fun remove(c: Context, m: TeamMember, giveTo: TeamMember? = null): String? = try {
        members().document(m.uid).update("status", Team.REMOVED).await()
        _tasks.value.filter { it.toUid == m.uid && it.open }.forEach { t ->
            if (giveTo != null && giveTo.uid != m.uid && t.fromUid == uid) {
                reassign(t, giveTo)
                TeamSync.relinkPerson(c, t, giveTo)
            } else {
                tasksRef().document(t.id).update(mapOf("status" to Team.RETURNED, "events" to FieldValue.arrayUnion(ev("Returned: ${m.first} left the team")))).await()
                if (t.fromUid == uid) TeamSync.returnToMe(c, t)
            }
        }
        null
    } catch (e: Exception) {
        "Couldn't remove: ${e.message ?: e.javaClass.simpleName}"
    }

    /** Active people below you in the chart: the ones you can give tasks to. */
    fun belowMe(): List<TeamMember> {
        val u = uid ?: return emptyList()
        return _members.value.filter { it.active && it.uid != u && u in it.managers }
    }

    /** Everyone you can give a task to: the whole team but you, the people below you first. */
    fun canGiveTo(): List<TeamMember> {
        val u = uid ?: return emptyList()
        // v0.49 the chart in the portal decides: down his branch, sideways, and the extra givers the owner added.
        val allowed = _me.value?.giveTo
        return _members.value.filter { it.active && it.uid != u && (allowed == null || it.uid in allowed) }.sortedBy { if (u in it.managers) 0 else 1 }
    }

    /** The team member with this phone number, if any. */
    fun memberByNumber(number: String): TeamMember? {
        if (number.isBlank() || !activeNow) return null
        return canGiveTo().firstOrNull { m -> m.numbers.any { SmsStore.sameNumber(it, number) } }
    }

    /** People tab: any of a member's numbers counts as his ⭐ Main one, so SIM 1 and SIM 2 are one person. */
    fun mainKey(number: String): String? {
        if (number.isBlank() || !activeNow) return null
        val k = RecentPeople.key(number)
        val list = _members.value
        if (list !== keyedFor) {
            keyed = list.filter { it.active && it.uid != uid && it.phone.isNotBlank() }
                .flatMap { m -> m.numbers.map { RecentPeople.key(it) to m } }.toMap()
            keyedFor = list
        }
        return keyed[k]?.let { RecentPeople.key(it.phone) }
    }

    /** The member whose ⭐ Main number has this People key. */
    fun memberByKey(key: String): TeamMember? = keyed.values.firstOrNull { RecentPeople.key(it.phone) == key }

    @Volatile private var keyed: Map<String, TeamMember> = emptyMap()
    @Volatile private var keyedFor: List<TeamMember>? = null

    /** All numbers of the team member with this number (empty when it is nobody in the team). */
    fun numbersOf(number: String): List<String> {
        if (number.isBlank() || !activeNow) return emptyList()
        return _members.value.firstOrNull { m -> m.numbers.any { SmsStore.sameNumber(it, number) } }?.numbers ?: emptyList()
    }

    fun member(uid: String): TeamMember? = _members.value.firstOrNull { it.uid == uid }

    /** Someone came online or went offline: their "last seen" moves, so the green dots redraw. */
    fun presenceChanged() {
        _members.value = _members.value.map { m ->
            val at = TeamPresence.lastAt(m.uid)
            if (at > m.lastSeen) m.copy(lastSeen = at) else m
        }
    }

    // ------------------------------------------------------------ tasks

    private fun ev(text: String) = mapOf("at" to System.currentTimeMillis(), "text" to text)

    /** Gives [to] a task. Returns its id (null when it couldn't be saved, e.g. signed out). */
    fun give(
        to: TeamMember, title: String, deadline: Long, urgent: Boolean, giverDue: Long?, photoProof: Boolean = false,
        brief: List<TeamAttach> = emptyList(),
    ): String? {
        val u = auth().currentUser ?: return null
        val me = _me.value ?: return null
        if (teamId == null) return null
        val ref = tasksRef().document()
        val now = System.currentTimeMillis()
        val viewers = (listOf(u.uid, to.uid) + to.managers + me.managers).distinct()
        val fixed = giverDue != null && giverDue > 0L
        ref.set(
            mapOf(
                "title" to title.trim(),
                "fromUid" to u.uid, "fromName" to me.name,
                "toUid" to to.uid, "toName" to to.name, "toPhone" to to.phone,
                "viewers" to viewers,
                "createdAt" to now, "updatedAt" to now,
                "deadline" to deadline, "urgent" to urgent,
                "dateBy" to if (fixed) Team.GIVER_DATE else Team.HIS_DATE,
                "due" to (giverDue ?: 0L), "firstDue" to (giverDue ?: 0L),
                "status" to if (fixed) Team.DATED else Team.SENT,
                "seenAt" to 0L, "doneAt" to 0L, "checkedAt" to 0L,
                "moreDue" to 0L, "moreReason" to "", "moreAt" to 0L,
                "reason" to "", "passedTo" to "",
                "askAt" to 0L, "stuckAt" to 0L, "stuckText" to "",
                "lastMsgAt" to (if (brief.isEmpty()) 0L else now), "lastMsgBy" to (if (brief.isEmpty()) "" else u.uid),
                "lastMsgText" to (brief.lastOrNull()?.let { if (it.kind == Team.VOICE) "🎤 Voice note" else "📷 Photo" } ?: ""),
                "photoProof" to photoProof, "proofAt" to 0L, "deliveredAt" to 0L,
                "locked" to TeamRules.deadlineLocked(appCtx), "freeExt" to TeamRules.freeExt(appCtx), "extCount" to 0,
                "reminderAt" to 0L, "nPhoto" to brief.count { it.kind == Team.PHOTO }, "nVoice" to brief.count { it.kind == Team.VOICE },
                "nBy" to (if (brief.isEmpty()) emptyMap() else mapOf(u.uid to brief.size.toLong())),
                "events" to listOf(ev(
                    "Given by ${me.first}" + (if (fixed) " · 📅 Deadline ${TeamUi.shortWhen(giverDue!!)}" else "") +
                        (if (deadline > 0L && !fixed) " · Latest ${TeamUi.shortWhen(deadline)}" else "")
                )),
            )
        )
        // The photos and voice notes added in New follow-up: the first messages on the Timeline.
        brief.forEachIndexed { i, a ->
            ref.collection("msgs").document().set(
                mapOf(
                    "at" to now + i, "fromUid" to u.uid, "fromName" to me.name, "kind" to a.kind, "tag" to Team.BRIEF,
                    "text" to "", "durMs" to a.durMs, "viewers" to viewers,
                    "data" to com.google.firebase.firestore.Blob.fromBytes(a.data),
                    "wave" to if (a.kind == Team.VOICE) TeamMedia.waveFor(a.durMs) else "",
                )
            )
        }
        if (brief.isNotEmpty()) TeamSync.markRead(appCtx, ref.id, now + brief.size)
        return ref.id
    }

    private fun update(t: TeamTask, fields: Map<String, Any>, event: String?) {
        if (teamId == null) return
        val all = fields.toMutableMap()
        all["updatedAt"] = System.currentTimeMillis()
        // The first deadline ever set is kept for the on-time mark; later moves only count as extensions.
        val due = fields["due"] as? Long ?: 0L
        if (t.firstDue == 0L && due > 0L && "firstDue" !in all) all["firstDue"] = due
        if (event != null) all["events"] = FieldValue.arrayUnion(ev(event))
        tasksRef().document(t.id).update(all)
        // Shown at once, before the server answers.
        _tasks.value = _tasks.value.map { if (it.id == t.id) applyLocal(it, all, event) else it }
    }

    private fun applyLocal(t: TeamTask, f: Map<String, Any>, event: String?): TeamTask {
        var x = t
        (f["status"] as? String)?.let { x = x.copy(status = it) }
        (f["due"] as? Long)?.let { x = x.copy(due = it) }
        (f["moreAt"] as? Long)?.let { x = x.copy(moreAt = it) }
        (f["moreDue"] as? Long)?.let { x = x.copy(moreDue = it) }
        (f["moreReason"] as? String)?.let { x = x.copy(moreReason = it) }
        (f["reason"] as? String)?.let { x = x.copy(reason = it) }
        (f["seenAt"] as? Long)?.let { x = x.copy(seenAt = it) }
        (f["doneAt"] as? Long)?.let { x = x.copy(doneAt = it) }
        (f["passedTo"] as? String)?.let { x = x.copy(passedTo = it) }
        (f["partFrom"] as? String)?.let { x = x.copy(partFrom = it) }
        (f["askAt"] as? Long)?.let { x = x.copy(askAt = it) }
        (f["stuckAt"] as? Long)?.let { x = x.copy(stuckAt = it) }
        (f["stuckText"] as? String)?.let { x = x.copy(stuckText = it) }
        (f["lastMsgAt"] as? Long)?.let { x = x.copy(lastMsgAt = it) }
        (f["lastMsgBy"] as? String)?.let { x = x.copy(lastMsgBy = it) }
        (f["lastMsgText"] as? String)?.let { x = x.copy(lastMsgText = it) }
        (f["proofAt"] as? Long)?.let { x = x.copy(proofAt = it) }
        (f["deliveredAt"] as? Long)?.let { x = x.copy(deliveredAt = it) }
        (f["toUid"] as? String)?.let { x = x.copy(toUid = it) }
        (f["toName"] as? String)?.let { x = x.copy(toName = it) }
        (f["toPhone"] as? String)?.let { x = x.copy(toPhone = it) }
        @Suppress("UNCHECKED_CAST")
        (f["viewers"] as? List<String>)?.let { x = x.copy(viewers = it) }
        (f["createdAt"] as? Long)?.let { x = x.copy(createdAt = it) }
        (f["deadline"] as? Long)?.let { x = x.copy(deadline = it) }
        (f["dateBy"] as? String)?.let { x = x.copy(dateBy = it) }
        (f["extCount"] as? Int)?.let { x = x.copy(extCount = it) }
        (f["firstDue"] as? Long)?.let { x = x.copy(firstDue = it) }
        (f["reminderAt"] as? Long)?.let { x = x.copy(reminderAt = it) }
        // Plot files: a tick or untick ("ticks.N" set or deleted) shows at once too, not after the cloud answers.
        val tk = f.filterKeys { it.startsWith("ticks.") }
        if (tk.isNotEmpty()) x = x.copy(ticks = x.ticks.toMutableMap().apply {
            tk.forEach { (k, v) ->
                val no = k.removePrefix("ticks.")
                val m = v as? Map<*, *>
                if (m == null) remove(no) else put(no, PlotTick((m["at"] as? Number)?.toLong() ?: 0L, m["name"]?.toString().orEmpty(), m["value"]?.toString().orEmpty()))
            }
        })
        if (event != null) x = x.copy(events = x.events + TaskEvent(System.currentTimeMillis(), event))
        return x
    }

    private val seenWritten = mutableMapOf<String, Long>()

    /** Plot files: any change to a plot step (ticks, Done, Return, waiting), with its Timeline line. */
    fun plotUpdate(t: TeamTask, fields: Map<String, Any>, event: String?) = update(t, fields, event)

    /** Plot files: the step's due time, set on the doer's phone from his own working hours. */
    fun setPlotDue(t: TeamTask, due: Long) = update(t, mapOf("due" to due, "firstDue" to due, "deadline" to due, "dueSet" to true), null)

    fun markSeen(t: TeamTask) {
        if (t.seenAt > 0L || (t.status != Team.SENT && t.status != Team.DATED)) return
        val now = System.currentTimeMillis()
        // A fixed date (Dated) stays Dated: only the time he saw it is added.
        val f = mutableMapOf<String, Any>("seenAt" to now)
        if (t.status == Team.SENT) f["status"] = Team.SEEN
        if (t.deliveredAt == 0L) f["deliveredAt"] = now
        // Two opens before the first write comes back must not write it twice.
        val last = seenWritten[t.id] ?: 0L
        if (now - last < 60_000L) return
        seenWritten[t.id] = now
        update(t, f, "First seen by ${t.toFirst}")
    }

    /** His phone has the task (two grey ticks on the giver's card). Quiet: no Timeline line. */
    fun markDelivered(t: TeamTask) {
        if (t.deliveredAt > 0L || t.toUid != uid) return
        update(t, mapOf("deliveredAt" to System.currentTimeMillis()), null)
    }

    /** "Mon 4 pm" for a new date, "Mon 4 pm → Tue 5 pm" for a change. */
    private fun change(old: Long, new: Long): String =
        if (old > 0L && old != new) "${TeamUi.shortWhen(old)} → ${TeamUi.shortWhen(new)}" else TeamUi.shortWhen(new)

    private fun because(reason: String) = reason.trim().takeIf { it.isNotEmpty() }?.let { " · “$it”" } ?: ""

    /** v0.48 his "why" for a slip: kept on the task for the team meeting, and a line on the Timeline. */
    fun addLateReason(taskId: String, r: LateReason) {
        val t = _tasks.value.firstOrNull { it.id == taskId } ?: return
        val line = "🏷 Why: ${r.reason}" + (if (r.note.isNotBlank()) " · “${r.note}”" else "") + if (r.outside) " (outside)" else ""
        update(t, mapOf("lateReasons" to FieldValue.arrayUnion(mapOf(
            "at" to r.at, "r" to r.reason, "k" to r.kind, "o" to r.outside, "n" to r.note, "by" to (uid ?: ""),
        ))), line)
    }

    /** He set his deadline (or moved it, while it isn't locked). */
    fun setHisDate(t: TeamTask, due: Long) {
        if (t.due > 0L && due > t.due) ReasonAsk.askTeamTask(t, ReasonAsk.MOVE, due)
        update(t, mapOf("due" to due, "status" to Team.DATED, "seenAt" to (t.seenAt.takeIf { it > 0L } ?: System.currentTimeMillis())),
            if (t.due > 0L && t.due != due) "📅 Deadline ${change(t.due, due)}" else "📅 Deadline set · ${TeamUi.shortWhen(due)}")
    }

    /** The giver sets the deadline himself (Urgent with no date, I pick, Not done yet). */
    fun setGiverDate(t: TeamTask, due: Long) {
        update(t, mapOf("due" to due, "dateBy" to Team.GIVER_DATE, "status" to Team.DATED, "moreAt" to 0L),
            "📅 ${t.fromFirst} set the deadline · ${change(t.due, due)}")
    }

    /** His own reminder changed (0 = it rings at the deadline): a line on the Timeline only, the deadline stays. */
    fun setReminder(t: TeamTask, at: Long) {
        if (at == t.reminderAt) return
        update(t, mapOf("reminderAt" to at), when {
            at <= 0L -> "⏰ His reminder at the deadline"
            t.reminderAt > 0L -> "⏰ His reminder · ${change(t.reminderAt, at)}"
            else -> "⏰ His reminder · ${TeamUi.shortWhen(at)}"
        })
    }

    fun askMoreTime(t: TeamTask, due: Long, reason: String) {
        update(t, mapOf("moreDue" to due, "moreReason" to reason.trim(), "moreAt" to System.currentTimeMillis()),
            "📅 ${t.toFirst} asks to extend · ${change(t.due, due)}${because(reason)}")
    }

    /**
     * 📅 Extend deadline: at once while he has free pushes left (Date rules › Extend without asking), else a request
     * the giver approves. Returns true when it moved at once.
     */
    fun extend(t: TeamTask, due: Long, reason: String): Boolean {
        ReasonAsk.askTeamTask(t, ReasonAsk.EXT, due)
        if (t.extCount < t.freeExt) {
            update(t, mapOf("due" to due, "deadline" to maxOf(t.deadline, due), "status" to Team.DATED, "moreAt" to 0L, "extCount" to t.extCount + 1),
                "📅 Deadline extended · ${change(t.due, due)}${because(reason)}")
            return true
        }
        askMoreTime(t, due, reason)
        return false
    }

    /** He takes back his request before it is answered. */
    fun cancelMore(t: TeamTask) {
        if (t.moreAt == 0L) return
        update(t, mapOf("moreAt" to 0L, "moreDue" to 0L, "moreReason" to ""), "📅 Extension request taken back")
    }

    fun answerMoreTime(t: TeamTask, ok: Boolean) {
        // v0.51 Answering a request (yes or no) settles "Extended N×" in Needs you › Team until he extends again.
        if (::appCtx.isInitialized) TeamUi.extensionSeen(appCtx, t.id, if (ok) t.extCount + 1 else t.extCount)
        if (ok) update(t, mapOf("due" to t.moreDue, "deadline" to maxOf(t.deadline, t.moreDue), "status" to Team.DATED, "moreAt" to 0L, "extCount" to t.extCount + 1),
            "📅 ${t.fromFirst} approved · Deadline ${change(t.due, t.moreDue)}")
        else update(t, mapOf("moreAt" to 0L), "📅 ${t.fromFirst} kept the deadline · ${TeamUi.shortWhen(t.due)}")
    }

    /** Undo (10 s) of an extension, a request or an answer: the deadline and the request as they were. */
    fun undoDeadline(before: TeamTask) {
        update(before, mapOf(
            "due" to before.due, "deadline" to before.deadline, "status" to before.status, "moreAt" to before.moreAt,
            "moreDue" to before.moreDue, "moreReason" to before.moreReason, "extCount" to before.extCount,
            "firstDue" to before.firstDue,
        ), "↩ Undone")
    }

    fun markDone(t: TeamTask) {
        val now = System.currentTimeMillis()
        // The on-time mark goes on the Done line too, with the first deadline it was measured against.
        val first = OnTime.promise(t)
        val mark = OnTime.label(t, now)?.let { m -> " · $m" + if (first > 0L && m != "No date") " · first deadline ${TeamUi.shortWhen(first)}" else "" }.orEmpty()
        // v0.48.1 A meeting action the owner gave himself: nobody else to check it, so Done closes it.
        // Plot files: nobody checks a plot step; Done closes it and the cloud opens the next step(s).
        val self = t.fromUid == t.toUid || t.plot
        update(t, mapOf("status" to (if (self) Team.CHECKED else Team.DONE), "doneAt" to now, "askAt" to 0L, "stuckAt" to 0L) +
            (if (self) mapOf("checkedAt" to now) else emptyMap()),
            (if (t.proofAt > 0L) "✅ Done with photo" else "✅ Done by ${t.toFirst}") + mark)
        // v0.48 Done after the first deadline: one tap on why it was late.
        if (first > 0L && now - first > 60_000L && !t.plot) ReasonAsk.askTeamTask(t, ReasonAsk.LATE)
    }

    /** Received, thanks ✓: closes it and thanks him. */
    fun check(t: TeamTask) {
        update(t, mapOf("status" to Team.CHECKED, "checkedAt" to System.currentTimeMillis()), "${t.fromFirst}: Received, thanks ✓")
    }

    /**
     * Not done yet: his task opens again with your reason. [newDue] [KEEP] keeps the deadline, 0 = he picks a new one
     * (his task goes back to his To schedule), else the one you picked.
     */
    fun notDoneYet(t: TeamTask, reason: String, newDue: Long = KEEP) {
        val why = reason.trim()
        val f = mutableMapOf<String, Any>("status" to Team.REOPENED, "reason" to why, "doneAt" to 0L, "moreAt" to 0L, "proofAt" to 0L)
        val tail = when {
            newDue == KEEP -> ""
            newDue <= 0L -> { f["due"] = 0L; f["dateBy"] = Team.HIS_DATE; " · ${t.toFirst} picks a new deadline" }
            else -> { f["due"] = newDue; f["dateBy"] = Team.GIVER_DATE; " · new deadline ${TeamUi.shortWhen(newDue)}" }
        }
        update(t, f, "↩ ${t.fromFirst}: Not done yet${because(why)}$tail")
    }

    const val KEEP = -1L

    /** He moved it to his Follow-ups for someone outside the team: the same task, now with his call to them. */
    fun passedDown(t: TeamTask, to: String, callAt: Long = 0L, inTeam: Boolean = false) {
        update(t, mapOf("passedTo" to to),
            "↪ ${t.toFirst} passed it to $to" + (if (inTeam) "" else " (outside team)") + if (callAt > 0L) " · his call ${TeamUi.shortWhen(callAt)}" else "")
    }

    /**
     * Lines written before a task passed to a team member picked from the phone book was recognised say "(outside
     * team)": it is dropped when that name is a member of the team.
     */
    fun withoutWrongOutside(text: String): String {
        if (!text.contains(" (outside team)")) return text
        val to = Regex("passed it to (.+?) \\(outside team\\)").find(text)?.groupValues?.get(1)?.trim() ?: return text
        val isMember = _members.value.any { it.active && (it.first.equals(to, true) || it.name.equals(to, true)) }
        return if (isMember) text.replace(" (outside team)", "") else text
    }

    /** A line in the task's history only (e.g. "text sent"). */
    fun note(t: TeamTask, text: String) {
        update(t, emptyMap(), text)
    }

    /**
     * You changed the person in Edit: the task leaves his app and goes to [to], with the same Timeline (photos,
     * voice, talk). His date goes; the new person picks his own (a date you fixed stays).
     */
    fun reassign(t: TeamTask, to: TeamMember) {
        val u = uid ?: return
        val me = _me.value ?: return
        if (teamId == null || t.fromUid != u || to.uid == t.toUid) return
        val viewers = (listOf(u, to.uid) + to.managers + me.managers).distinct()
        val f = mutableMapOf<String, Any>(
            "toUid" to to.uid, "toName" to to.name, "toPhone" to to.phone, "viewers" to viewers,
            "status" to if (t.hisDate || t.due == 0L) Team.SENT else Team.DATED,
            "seenAt" to 0L, "deliveredAt" to 0L, "doneAt" to 0L, "passedTo" to "", "askAt" to 0L, "stuckAt" to 0L,
            "createdAt" to System.currentTimeMillis(),
        )
        if (t.hisDate) f["due"] = 0L
        update(t, f, "Given to ${to.first} instead of ${t.toFirst}")
        scope.launch {
            runCatching {
                msgs(t.id).whereArrayContains("viewers", u).get().await().documents.forEach { d ->
                    d.reference.update("viewers", viewers)
                }
            }.onFailure { CrashLog.record(appCtx, it, "Team reassign") }
        }
    }

    /**
     * v0.44 ✓ Done, ➜ next part: his part is received with thanks (it stays done in his history, and he keeps seeing
     * the task, view only) and the SAME task with its Timeline goes on to [to]. Null: you do the next part yourself.
     */
    fun nextPart(t: TeamTask, to: TeamMember?, left: String, outsider: String = "") {
        val u = uid ?: return
        val me = _me.value ?: return
        if (teamId == null || t.fromUid != u) return
        val now = System.currentTimeMillis()
        val tail = left.trim().takeIf { it.isNotEmpty() }?.let { " · “$it”" }.orEmpty()
        if (to == null || to.uid == u) {
            update(t, mapOf("status" to Team.CHECKED, "checkedAt" to now, "partFrom" to t.toUid),
                "✓ Received from ${t.toFirst} · next part with ${outsider.ifBlank { me.first }}$tail")
            return
        }
        val viewers = (t.viewers + listOf(u, to.uid) + to.managers + me.managers).distinct()
        val f = mutableMapOf<String, Any>(
            "toUid" to to.uid, "toName" to to.name, "toPhone" to to.phone, "viewers" to viewers, "partFrom" to t.toUid,
            "status" to if (t.hisDate || t.due == 0L) Team.SENT else Team.DATED, "checkedAt" to now,
            "seenAt" to 0L, "deliveredAt" to 0L, "doneAt" to 0L, "passedTo" to "", "askAt" to 0L, "stuckAt" to 0L,
            "createdAt" to now,
        )
        if (t.hisDate) f["due"] = 0L
        update(t, f, "✓ Received from ${t.toFirst} · next part with ${to.first}$tail")
        scope.launch {
            runCatching {
                msgs(t.id).whereArrayContains("viewers", u).get().await().documents.forEach { d ->
                    d.reference.update("viewers", viewers)
                }
            }.onFailure { CrashLog.record(appCtx, it, "Team next part") }
        }
    }

    fun cancel(t: TeamTask) {
        update(t, mapOf("status" to Team.CANCELLED), "Cancelled by ${t.fromFirst}")
    }

    // ------------------------------------------------------------ talking on a task

    private fun msgs(taskId: String) = tasksRef().document(taskId).collection("msgs")

    /**
     * Sends a message on [t]'s Timeline (it shows at once and goes up when data is on). [tag] ❓ Ask pauses his
     * no-date timer until the giver answers; ⚠ Stuck shows red. Any message from the giver answers both.
     */
    /** The id of the message [send] wrote last (for its 5-second Undo). */
    var lastSentId: String? = null
        private set

    /**
     * Takes one of your messages back: its text and media go, a "deleted" mark stays so the Timeline stays honest.
     * Only the text and data fields change, which the Firestore rules already allow, so nothing new to publish.
     */
    fun deleteMsg(t: TeamTask, m: TeamMsg) {
        if (Team.canUndo(m, uid)) deleteMsgId(t, m.id)
    }

    /** The same by id: for the 5-second Undo right after sending (the message is yours by definition). */
    fun deleteMsgId(t: TeamTask, id: String) {
        if (teamId == null) return
        msgs(t.id).document(id).update(mapOf("text" to Team.DELETED, "data" to FieldValue.delete()))
            .addOnFailureListener { logFail(it, "Undo message") }
    }

    fun send(t: TeamTask, kind: String, text: String, tag: String = "", data: ByteArray? = null, durMs: Long = 0L): Boolean {
        val u = auth().currentUser ?: return false
        val me = _me.value ?: return false
        if (teamId == null) return false
        val now = System.currentTimeMillis()
        val doc = mutableMapOf<String, Any>(
            "at" to now, "fromUid" to u.uid, "fromName" to me.name, "kind" to kind, "tag" to tag,
            "text" to text.trim(), "durMs" to durMs, "viewers" to t.viewers,
        )
        if (data != null) doc["data"] = com.google.firebase.firestore.Blob.fromBytes(data)
        if (kind == Team.VOICE) TeamMedia.waveFor(durMs).takeIf { it.isNotEmpty() }?.let { doc["wave"] = it }
        val ref = msgs(t.id).document()
        lastSentId = ref.id
        ref.set(doc).addOnFailureListener { if (::appCtx.isInitialized) CrashLog.record(appCtx, it, "Timeline · Send") }
        val preview = when (kind) {
            Team.VOICE -> "🎤 Voice note"
            Team.PHOTO -> "📷 Photo" + if (text.isNotBlank()) ": ${text.trim()}" else ""
            else -> text.trim()
        }.take(120)
        val f = mutableMapOf<String, Any>("lastMsgAt" to now, "lastMsgBy" to u.uid, "lastMsgText" to preview)
        // Counts for "3 new on Timeline" and "Timeline · 📷 2 · 🎤 1".
        f["nBy.${u.uid}"] = FieldValue.increment(1)
        if (kind == Team.PHOTO) f["nPhoto"] = FieldValue.increment(1)
        if (kind == Team.VOICE) f["nVoice"] = FieldValue.increment(1)
        var event: String? = null
        when (tag) {
            Team.ASK -> f["askAt"] = now
            Team.STUCK -> { f["stuckAt"] = now; f["stuckText"] = if (kind == Team.VOICE) "🎤 Voice note" else text.trim().take(120) }
            Team.PROOF -> f["proofAt"] = now
        }
        if (u.uid == t.fromUid && tag.isEmpty() && (t.askAt > 0L || t.stuckAt > 0L)) {
            f["askAt"] = 0L
            f["stuckAt"] = 0L
            event = if (t.askAt > 0L) "❓ Answered · date timer running again" else "⚠ ${t.fromFirst} answered"
        }
        update(t, f, event)
        TeamSync.markRead(appCtx, t.id, now)
        return true
    }

    /**
     * Messages from others you now see on the Timeline: their ticks go blue for the sender. Saved on the task itself
     * ("seen up to"), a small write that goes through for every kind of message; the old per-message mark is kept
     * for phones still on an older version. Failures go to the crash log.
     */
    fun markMsgsSeen(taskId: String, list: List<TeamMsg>) {
        val u = uid ?: return
        if (teamId == null) return
        val t = _tasks.value.firstOrNull { it.id == taskId }
        val now = System.currentTimeMillis()
        val others = list.filter { it.fromUid != u && !it.pending }
        val newest = others.maxOfOrNull { it.at } ?: 0L
        if (newest > 0L && (t == null || newest > (t.msgSeen[u] ?: 0L))) {
            tasksRef().document(taskId).update("msgSeen.$u", newest).addOnFailureListener { logFail(it, "Seen") }
            if (t != null) _tasks.value = _tasks.value.map { if (it.id == taskId) it.copy(msgSeen = it.msgSeen + (u to newest)) else it }
        }
        others.filter { u !in it.seenBy }.forEach { m ->
            msgs(taskId).document(m.id).update("seenBy.$u", now).addOnFailureListener { logFail(it, "Seen (message)") }
        }
        flushPlayed(taskId)
    }

    /**
     * A voice note played, or a photo opened full screen (🎙 / ticks blue for the sender once everyone has). Kept on
     * this phone until the server has it, and sent again on the next Timeline open if it failed.
     */
    fun markPlayed(taskId: String, m: TeamMsg) {
        val u = uid ?: return
        if (teamId == null || m.fromUid == u || m.pending) return
        val t = _tasks.value.firstOrNull { it.id == taskId }
        if (t != null && t.playedBy(m, u)) return
        pendingPlayed(taskId, add = m.id)
        flushPlayed(taskId)
        if (u !in m.playedBy) msgs(taskId).document(m.id).update("playedBy.$u", System.currentTimeMillis())
            .addOnFailureListener { logFail(it, "Played (message)") }
    }

    private fun prefsPlayed() = appCtx.getSharedPreferences("team_played", Context.MODE_PRIVATE)

    /** The played marks of [taskId] not yet on the server (optionally adding [add]). */
    private fun pendingPlayed(taskId: String, add: String? = null): Set<String> {
        if (!::appCtx.isInitialized) return emptySet()
        val now = prefsPlayed().getStringSet(taskId, emptySet()).orEmpty() + listOfNotNull(add)
        if (add != null) prefsPlayed().edit().putStringSet(taskId, now).apply()
        return now
    }

    private fun flushPlayed(taskId: String) {
        val u = uid ?: return
        val ids = pendingPlayed(taskId)
        if (ids.isEmpty() || teamId == null) return
        tasksRef().document(taskId).update("played.$u", FieldValue.arrayUnion(*ids.toTypedArray()))
            .addOnSuccessListener {
                val left = prefsPlayed().getStringSet(taskId, emptySet()).orEmpty() - ids
                prefsPlayed().edit().apply { if (left.isEmpty()) remove(taskId) else putStringSet(taskId, left) }.apply()
            }
            .addOnFailureListener { logFail(it, "Played") }
        _tasks.value = _tasks.value.map { if (it.id == taskId) it.copy(played = it.played + (u to (it.played[u].orEmpty() + ids).distinct())) else it }
    }

    private fun logFail(e: Exception, what: String) {
        if (::appCtx.isInitialized) CrashLog.record(appCtx, e, "Team ticks · $what")
    }

    /** This phone has the newest message (two grey ticks for the sender). Quiet: no Timeline line. */
    fun markMsgDelivered(t: TeamTask) {
        val u = uid ?: return
        if (teamId == null || t.lastMsgAt <= (t.msgDel[u] ?: 0L) || t.lastMsgBy == u) return
        tasksRef().document(t.id).update("msgDel.$u", t.lastMsgAt)
    }

    private const val STUCK_MS = 10_000L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectAt = 0L

    /** The connection to the server can stall (messages stay on 🕓 until the app restarts): restart it, at most every 10 s. */
    private fun reconnect() {
        val now = System.currentTimeMillis()
        if (now - reconnectAt < STUCK_MS) return
        reconnectAt = now
        db().disableNetwork().addOnCompleteListener { db().enableNetwork() }
    }

    /** The Timeline's messages, oldest first (null until the first read). */
    fun watchMsgs(taskId: String, onChange: (List<TeamMsg>) -> Unit): ListenerRegistration? {
        if (teamId == null) return null
        val u = uid ?: return null
        // Metadata changes too: the 🕓 turns into ticks as soon as the server has the message, not on the next message.
        var waiting = false
        var timer = false
        return msgs(taskId).whereArrayContains("viewers", u).addSnapshotListener(MetadataChanges.INCLUDE) { s, _ ->
            s ?: return@addSnapshotListener
            // A message of mine still on 🕓: if it is over 10 s old (or stays so for 10 s), reconnect so it sends by itself.
            val mine = s.documents.filter { it.metadata.hasPendingWrites() && it.getString("fromUid") == u }
            waiting = mine.isNotEmpty()
            if (mine.any { System.currentTimeMillis() - (it.getLong("at") ?: 0L) > STUCK_MS }) reconnect()
            else if (waiting && !timer) {
                timer = true
                main.postDelayed({ timer = false; if (waiting) reconnect() }, STUCK_MS)
            }
            onChange(s.documents.map { d ->
                TeamMsg(
                    id = d.id,
                    at = d.getLong("at") ?: 0L,
                    fromUid = d.getString("fromUid").orEmpty(),
                    fromName = d.getString("fromName").orEmpty(),
                    kind = d.getString("kind") ?: Team.TEXT,
                    tag = d.getString("tag").orEmpty(),
                    text = d.getString("text").orEmpty(),
                    data = d.getBlob("data")?.toBytes(),
                    durMs = d.getLong("durMs") ?: 0L,
                    seenBy = longs(d.get("seenBy")),
                    playedBy = longs(d.get("playedBy")),
                    pending = d.metadata.hasPendingWrites(),
                    wave = d.getString("wave").orEmpty(),
                )
            }.sortedBy { it.at })
        }
    }

    /** Photos and voice notes of tasks you gave that closed over [Team.MEDIA_DAYS] days ago are removed (text stays). */
    suspend fun clearOldMedia() {
        val u = uid ?: return
        if (teamId == null) return
        val cutoff = System.currentTimeMillis() - Team.MEDIA_DAYS * 24 * 3_600_000L
        _tasks.value.filter { it.fromUid == u && !it.open && it.status != Team.DONE && !it.mediaCleared && (it.events.lastOrNull()?.at ?: it.createdAt) < cutoff }
            .forEach { t ->
                runCatching {
                    msgs(t.id).whereArrayContains("viewers", u).get().await().documents
                        .filter { it.getString("kind") != Team.TEXT }
                        .forEach { it.reference.update(mapOf("data" to FieldValue.delete(), "text" to (it.getString("text").orEmpty() + " (removed after 90 days)"))) }
                    tasksRef().document(t.id).update("mediaCleared", true).await()
                }
            }
    }

    // ------------------------------------------------------------ his day

    /** Your day for the people above you (Basharat's day on your manager's phone). */
    fun uploadDay(c: Context) {
        val m = _me.value ?: return
        val t = teamId ?: return
        // v0.59.1 every active member sends his day (an owner has no managers but Team Performance still shows him).
        if (!m.active) return
        val map = TeamSync.dayMap(c, m.managers)
        db().collection("teams").document(t).collection("days").document(m.uid).set(map)
    }

    /** Someone's day as last sent by their phone (null until they open the app once). */
    fun watchDay(uidOf: String, onChange: (Map<String, Any?>?) -> Unit): ListenerRegistration? {
        val t = teamId ?: return null
        return db().collection("teams").document(t).collection("days").document(uidOf)
            .addSnapshotListener { s, _ -> onChange(s?.data) }
    }

    // ------------------------------------------------------------ background

    /** One check without listeners (the 15-minute background run). */
    suspend fun fetchOnce(c: Context) {
        init(c)
        val u = auth().currentUser ?: return
        findMyTeam()
        val t = teamId ?: return
        val team = db().collection("teams").document(t)
        val meDoc = runCatching { team.collection("members").document(u.uid).get(Source.SERVER).await() }.getOrNull() ?: return
        if (!meDoc.exists()) return leaveLocal()
        val me = memberOf(meDoc)
        _me.value = me
        if (me.status == Team.REMOVED) return leaveLocal()
        if (!me.active) return
        runCatching { _team.value = teamOf(team.get().await()) }
        runCatching { _members.value = team.collection("members").get().await().documents.map { memberOf(it) } }
        val snap = team.collection("tasks").whereArrayContains("viewers", u.uid).get().await()
        val list = snap.documents.map { taskOf(it) }
        _tasks.value = list
        TeamSync.apply(c, list, foreground, complete = !snap.metadata.isFromCache)
        runCatching {
            val d = team.collection("dossier").document(u.uid).get().await()
            if (d.exists()) TeamDossier.apply(c, (d.get("blocks") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(TeamBlock::of) } ?: emptyList())
        }
        TeamSync.pushLocal(c)
        uploadDay(c)
        clearOldMedia()
    }

    // ------------------------------------------------------------ reading documents

    private fun teamOf(s: DocumentSnapshot) = TeamInfo(s.id, s.getString("name").orEmpty(), s.getString("ownerUid").orEmpty(), s.getString("code").orEmpty())

    private fun strings(v: Any?): List<String> = (v as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    private fun longs(v: Any?): Map<String, Long> =
        (v as? Map<*, *>)?.entries?.mapNotNull { (k, n) -> (k as? String)?.let { it to ((n as? Number)?.toLong() ?: 0L) } }?.toMap() ?: emptyMap()

    /** The one name (locked 23 Nov): the name on his seat on the website, the same on every phone. No contact or typed names. */
    private fun named(m: TeamMember): TeamMember =
        Seats.of(m.uid, m.seatId)?.name?.takeIf { it.isNotBlank() }?.let { m.copy(name = it) } ?: m

    private fun memberOf(s: DocumentSnapshot) = TeamMember(
        uid = s.id,
        name = s.getString("name").orEmpty(),
        typed = s.getString("name").orEmpty(),
        email = s.getString("email").orEmpty(),
        phone = s.getString("phone").orEmpty(),
        role = s.getString("role") ?: Team.MEMBER,
        reportsTo = s.getString("reportsTo").orEmpty(),
        managers = strings(s.get("managers")),
        status = s.getString("status") ?: Team.PENDING,
        joinedAt = s.getLong("joinedAt") ?: 0L,
        lastSeen = s.getLong("lastSeen") ?: 0L,
        more = strings(s.get("more")),
        signedOut = s.getBoolean("signedOut") == true,
        wa = s.getString("wa").orEmpty(),
        ownDossier = s.getBoolean("ownDossier") == true,
        setDossier = s.getBoolean("setDossier") == true,
        seatId = s.getString("seatId").orEmpty(),
        photo = s.getString("photo").orEmpty(),
        designation = s.getString("designation").orEmpty(),
        also = strings(s.get("also")),
        appPerms = ((s.get("perms") as? Map<*, *>)?.get("app") as? Map<*, *>)?.entries
            ?.mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? Boolean)?.let { key to it } } }?.toMap(),
        giveTo = (s.get("canGiveTo") as? List<*>)?.mapNotNull { it as? String },
    )

    private fun taskOf(s: DocumentSnapshot) = TeamTask(
        id = s.id,
        title = s.getString("title").orEmpty(),
        fromUid = s.getString("fromUid").orEmpty(),
        fromName = s.getString("fromName").orEmpty(),
        toUid = s.getString("toUid").orEmpty(),
        toName = s.getString("toName").orEmpty(),
        toPhone = s.getString("toPhone").orEmpty(),
        viewers = strings(s.get("viewers")),
        createdAt = s.getLong("createdAt") ?: 0L,
        deadline = s.getLong("deadline") ?: 0L,
        urgent = s.getBoolean("urgent") ?: false,
        dateBy = s.getString("dateBy") ?: Team.HIS_DATE,
        due = s.getLong("due") ?: 0L,
        status = s.getString("status") ?: Team.SENT,
        seenAt = s.getLong("seenAt") ?: 0L,
        doneAt = s.getLong("doneAt") ?: 0L,
        checkedAt = s.getLong("checkedAt") ?: 0L,
        moreDue = s.getLong("moreDue") ?: 0L,
        moreReason = s.getString("moreReason").orEmpty(),
        moreAt = s.getLong("moreAt") ?: 0L,
        reason = s.getString("reason").orEmpty(),
        passedTo = s.getString("passedTo").orEmpty(),
        partFrom = s.getString("partFrom").orEmpty(),
        events = (s.get("events") as? List<*>)?.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            TaskEvent((m["at"] as? Number)?.toLong() ?: 0L, m["text"] as? String ?: "")
        }?.sortedBy { it.at } ?: emptyList(),
        askAt = s.getLong("askAt") ?: 0L,
        stuckAt = s.getLong("stuckAt") ?: 0L,
        stuckText = s.getString("stuckText").orEmpty(),
        lastMsgAt = s.getLong("lastMsgAt") ?: 0L,
        lastMsgBy = s.getString("lastMsgBy").orEmpty(),
        lastMsgText = s.getString("lastMsgText").orEmpty(),
        photoProof = s.getBoolean("photoProof") ?: false,
        proofAt = s.getLong("proofAt") ?: 0L,
        mediaCleared = s.getBoolean("mediaCleared") ?: false,
        deliveredAt = s.getLong("deliveredAt") ?: 0L,
        locked = s.getBoolean("locked") ?: false,
        freeExt = (s.getLong("freeExt") ?: 0L).toInt(),
        extCount = (s.getLong("extCount") ?: 0L).toInt(),
        firstDue = s.getLong("firstDue") ?: 0L,
        reminderAt = s.getLong("reminderAt") ?: 0L,
        nBy = longs(s.get("nBy")),
        nPhoto = (s.getLong("nPhoto") ?: 0L).toInt(),
        nVoice = (s.getLong("nVoice") ?: 0L).toInt(),
        msgDel = longs(s.get("msgDel")),
        msgSeen = longs(s.get("msgSeen")),
        played = (s.get("played") as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to strings(v) } }?.toMap() ?: emptyMap(),
        meeting = s.getBoolean("meeting") ?: false,
        plot = s.getBoolean("plot") ?: false,
        timer = s.getString("timer").orEmpty(),
        days = s.getLong("days")?.toInt(),
        hours = s.getLong("hours")?.toInt(),
        dueSet = s.getBoolean("dueSet") ?: true,
        plotId = s.getString("plotId").orEmpty(),
        plotNo = s.get("plotNo")?.toString().orEmpty(),
        step = s.getString("step").orEmpty(),
        role = s.getString("role").orEmpty(),
        test = s.getBoolean("test") ?: false,
        ticks = (s.get("ticks") as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
            val m = v as? Map<*, *> ?: return@mapNotNull null
            (k as? String)?.let { it to PlotTick((m["at"] as? Number)?.toLong() ?: 0L, m["name"]?.toString().orEmpty(), m["value"]?.toString().orEmpty()) }
        }?.toMap() ?: emptyMap(),
        waitAt = s.getLong("waitAt") ?: 0L,
        fromRole = s.getString("fromRole").orEmpty(),
        ownerName = s.getString("ownerName").orEmpty(),
        ownerWa = s.getString("ownerWa").orEmpty(),
        ownerSim2 = s.getString("ownerSim2").orEmpty(),
        picks = (s.get("picks") as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to v.toString() } }?.toMap() ?: emptyMap(),
        cc = (s.get("cc") as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to v.toString() } }?.toMap() ?: emptyMap(),
    )
}
