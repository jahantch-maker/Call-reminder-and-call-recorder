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
) {
    val active: Boolean get() = status == Team.ACTIVE
    val pending: Boolean get() = status == Team.PENDING
    val first: String get() = Names.first(name) ?: name.ifBlank { "Member" }
    val roleLabel: String get() = role.replaceFirstChar { it.uppercase() }

    /** Had the app open in the last 15 minutes ("🟢 Online now"). */
    fun onlineNow(now: Long = System.currentTimeMillis()): Boolean = lastSeen > 0L && now - lastSeen < 15 * 60_000L

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
) {
    val asks: Boolean get() = open && askAt > 0L
    val stuck: Boolean get() = open && stuckAt > 0L
    val open: Boolean get() = status in Team.OPEN_STATES
    val hisDate: Boolean get() = dateBy == Team.HIS_DATE
    /** He asked for more time and you haven't answered yet. */
    val asking: Boolean get() = open && moreAt > 0L
    val doneCheck: Boolean get() = status == Team.DONE
    val toFirst: String get() = Names.first(toName) ?: toName
    val fromFirst: String get() = Names.first(fromName) ?: fromName
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
}

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
) {
    val fromFirst: String get() = Names.first(fromName) ?: fromName
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
    fun smsMin(c: Context) = p(c).getInt("sms", 120)
    fun setSmsMin(c: Context, m: Int) = p(c).edit().putInt("sms", m).apply()
    fun workingOnly(c: Context) = p(c).getBoolean("working_only", true)
    fun setWorkingOnly(c: Context, on: Boolean) = p(c).edit().putBoolean("working_only", on).apply()

    fun label(m: Int): String = when {
        m <= 0 -> "Off"
        m >= 24 * 60 -> "1 day"
        m % 60 == 0 -> "${m / 60}h"
        else -> "${m}m"
    }

    /** Minutes from [from] to [to], counting only working hours (weekly off day skipped) when that's on. */
    fun minutesSince(c: Context, from: Long, to: Long = System.currentTimeMillis()): Long {
        if (to <= from) return 0L
        if (!workingOnly(c)) return (to - from) / 60_000L
        val zone = java.time.ZoneId.systemDefault()
        var day = java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val last = java.time.Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        var total = 0L
        var guard = 0
        while (!day.isAfter(last) && guard++ < 60) {
            if (!OffDays.isOff(day)) {
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
                val m = memberOf(s)
                val was = _me.value
                _me.value = m
                if (m.status == Team.REMOVED) { leaveLocal(); return@addSnapshotListener }
                if (m.active && was?.active != true) {
                    listenMembersAndTasks(t, u.uid)
                    TeamWorker.schedule(appCtx)
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
                _members.value = s.documents.map { memberOf(it) }.sortedBy { it.name.lowercase() }
            },
            team.collection("tasks").whereArrayContains("viewers", me).addSnapshotListener { s, e ->
                if (e != null) return@addSnapshotListener relisten()
                s ?: return@addSnapshotListener
                val list = s.documents.map { taskOf(it) }
                _tasks.value = list
                val fromServer = !s.metadata.isFromCache
                scope.launch { CrashLog.guard(appCtx, "Team sync") { TeamSync.apply(appCtx, list, foreground, complete = fromServer) } }
            },
            // His Dossier, as the Owner set it.
            team.collection("dossier").document(me).addSnapshotListener { s, _ ->
                if (s == null || !s.exists()) return@addSnapshotListener
                val blocks = (s.get("blocks") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(TeamBlock::of) } ?: emptyList()
                scope.launch { CrashLog.guard(appCtx, "Team dossier") { TeamDossier.apply(appCtx, blocks) } }
            },
        )
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
     * The app came to the front (or left it): tasks sent to you count as Seen while it's open, "last seen" is
     * written at once and every 10 minutes while it stays open, and the tasks are read fresh from the server
     * (a listener can miss changes after a long time in the background).
     */
    fun onForeground(c: Context, on: Boolean) {
        foreground = on
        aliveJob?.cancel()
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
            _tasks.value.filter { it.toUid == u && it.status == Team.SENT }.forEach { markSeen(it) }
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
        db().collection("teams").document(t).collection("members").document(u).update("lastSeen", now)
            .addOnSuccessListener { p().edit().putLong("touched", now).apply() }
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
            findMyTeam()
            start(activity)
            null
        } else "Google sign-in didn't return an account."
    } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
        "Sign-in cancelled."
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

    fun signOut() {
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

    /** Asks to join with the 6-letter code; the Owner approves with one tap. */
    /** The company's team ("Rafiq Builders" for the pilot): its id and name, or null when it can't be found. */
    suspend fun findHomeTeam(): Pair<String, String>? = runCatching {
        db().collection("codes").whereEqualTo("teamName", Team.HOME_TEAM).limit(1).get().await().documents.firstOrNull()
            ?.let { d -> d.getString("teamId")?.let { it to (d.getString("teamName") ?: Team.HOME_TEAM) } }
    }.getOrNull()

    /** Ask to join the company's team (no code): the Owner approves. */
    suspend fun joinTeam(teamId: String, myName: String, myPhone: String): String? = try {
        val u = auth().currentUser ?: throw IllegalStateException("Sign in first")
        joinNow(u, teamId, myName, myPhone)
    } catch (e: Exception) {
        "Couldn't join: ${e.message ?: e.javaClass.simpleName}"
    }

    private suspend fun joinNow(u: FirebaseUser, t: String, myName: String, myPhone: String): String? {
        db().collection("teams").document(t).collection("members").document(u.uid).set(
            memberMap(u, myPhone, Team.MEMBER, "", emptyList(), Team.PENDING, System.currentTimeMillis(), myName)
        ).await()
        db().collection("users").document(u.uid).set(mapOf("teamId" to t)).await()
        setTeamId(t)
        start(appCtx)
        return null
    }

    /**
     * Pilot: the people who were Admins become Members (only the Owner approves and edits the chart). Once, on the
     * Owner's phone.
     */
    suspend fun adminsToMembers() {
        val me = _me.value ?: return
        if (me.role != Team.OWNER || p().getBoolean("admins_to_members", false)) return
        _members.value.filter { it.active && it.role == Team.ADMIN }.forEach { setChartNow(it, it.reportsTo, Team.MEMBER, it.phone) }
        p().edit().putBoolean("admins_to_members", true).apply()
    }

    suspend fun join(code: String, myName: String, myPhone: String): String? = try {
        val u = auth().currentUser ?: throw IllegalStateException("Sign in first")
        val c = code.trim().uppercase()
        val doc = db().collection("codes").document(c).get().await()
        val t = doc.getString("teamId") ?: throw IllegalStateException("No team has the code $c. Check the letters with your manager.")
        db().collection("teams").document(t).collection("members").document(u.uid).set(
            memberMap(u, myPhone, Team.MEMBER, "", emptyList(), Team.PENDING, System.currentTimeMillis(), myName)
        ).await()
        db().collection("users").document(u.uid).set(mapOf("teamId" to t)).await()
        setTeamId(t)
        start(appCtx)
        null
    } catch (e: Exception) {
        "Couldn't join: ${e.message ?: e.javaClass.simpleName}"
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

    /** The Owner lets someone in: their saved number, who they report to and their role. */
    suspend fun approve(c: Context, m: TeamMember, phone: String, reportsTo: String, role: String): String? = try {
        members().document(m.uid).update(
            mapOf(
                "status" to Team.ACTIVE, "phone" to phone.trim(), "reportsTo" to reportsTo, "role" to role,
                "managers" to chainFrom(reportsTo),
            )
        ).await()
        TeamDossier.stopDailyList(c, phone.trim())
        val moved = TeamSync.moveOpenFollowUps(c, m.copy(phone = phone.trim(), reportsTo = reportsTo, managers = chainFrom(reportsTo), status = Team.ACTIVE))
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

    /** Chart and role changes; everyone under [m] gets their manager list redone too. */
    suspend fun setChart(m: TeamMember, reportsTo: String, role: String, phone: String): String? {
        if (reportsTo == m.uid || m.uid in chainFrom(reportsTo)) return "${m.first} can't report to someone under them."
        return setChartNow(m, reportsTo, role, phone)
    }

    private suspend fun setChartNow(m: TeamMember, reportsTo: String, role: String, phone: String): String? = try {
        members().document(m.uid).update(
            mapOf("reportsTo" to reportsTo, "role" to role, "phone" to phone.trim(), "managers" to chainFrom(reportsTo))
        ).await()
        // Local copy first, so the people under them get the new chain.
        _members.value = _members.value.map { if (it.uid == m.uid) it.copy(reportsTo = reportsTo, role = role, phone = phone.trim(), managers = chainFrom(reportsTo)) else it }
        _members.value.filter { m.uid in it.managers }.forEach { under ->
            members().document(under.uid).update("managers", chainFrom(under.reportsTo)).await()
        }
        null
    } catch (e: Exception) {
        "Couldn't save: ${e.message ?: e.javaClass.simpleName}"
    }

    /** Remove member: access ends now; their open tasks from you come back to To schedule. */
    suspend fun remove(c: Context, m: TeamMember): String? = try {
        members().document(m.uid).update("status", Team.REMOVED).await()
        _tasks.value.filter { it.toUid == m.uid && it.open }.forEach { t ->
            tasksRef().document(t.id).update(mapOf("status" to Team.RETURNED, "events" to FieldValue.arrayUnion(ev("Returned: ${m.first} left the team")))).await()
            if (t.fromUid == uid) TeamSync.returnToMe(c, t)
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

    /** The team member with this phone number (below you), if any. */
    fun memberByNumber(number: String): TeamMember? {
        if (number.isBlank() || !activeNow) return null
        return belowMe().firstOrNull { it.phone.isNotBlank() && SmsStore.sameNumber(it.phone, number) }
    }

    fun member(uid: String): TeamMember? = _members.value.firstOrNull { it.uid == uid }

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
                "due" to (giverDue ?: 0L),
                "status" to if (fixed) Team.DATED else Team.SENT,
                "seenAt" to 0L, "doneAt" to 0L, "checkedAt" to 0L,
                "moreDue" to 0L, "moreReason" to "", "moreAt" to 0L,
                "reason" to "", "passedTo" to "",
                "askAt" to 0L, "stuckAt" to 0L, "stuckText" to "",
                "lastMsgAt" to (if (brief.isEmpty()) 0L else now), "lastMsgBy" to (if (brief.isEmpty()) "" else u.uid),
                "lastMsgText" to (brief.lastOrNull()?.let { if (it.kind == Team.VOICE) "🎤 Voice note" else "📷 Photo" } ?: ""),
                "photoProof" to photoProof, "proofAt" to 0L, "deliveredAt" to 0L,
                "events" to listOf(ev(if (fixed) "Given by ${me.first} · date ${Fmt.dueLabel(giverDue!!)}" else "Given by ${me.first}")),
            )
        )
        // The photos and voice notes added in New follow-up: the first messages on the Timeline.
        brief.forEachIndexed { i, a ->
            ref.collection("msgs").document().set(
                mapOf(
                    "at" to now + i, "fromUid" to u.uid, "fromName" to me.name, "kind" to a.kind, "tag" to Team.BRIEF,
                    "text" to "", "durMs" to a.durMs, "viewers" to viewers,
                    "data" to com.google.firebase.firestore.Blob.fromBytes(a.data),
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
        if (event != null) all["events"] = FieldValue.arrayUnion(ev(event))
        tasksRef().document(t.id).update(all)
        // Shown at once, before the server answers.
        _tasks.value = _tasks.value.map { if (it.id == t.id) applyLocal(it, fields, event) else it }
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
        if (event != null) x = x.copy(events = x.events + TaskEvent(System.currentTimeMillis(), event))
        return x
    }

    fun markSeen(t: TeamTask) {
        if (t.status != Team.SENT) return
        val now = System.currentTimeMillis()
        val f = mutableMapOf<String, Any>("status" to Team.SEEN, "seenAt" to now)
        if (t.deliveredAt == 0L) f["deliveredAt"] = now
        update(t, f, "Seen by ${t.toFirst}")
    }

    /** His phone has the task (two grey ticks on the giver's card). Quiet: no Timeline line. */
    fun markDelivered(t: TeamTask) {
        if (t.deliveredAt > 0L || t.toUid != uid) return
        update(t, mapOf("deliveredAt" to System.currentTimeMillis()), null)
    }

    /** He picked (or moved) his date. */
    fun setHisDate(t: TeamTask, due: Long) {
        update(t, mapOf("due" to due, "status" to Team.DATED, "seenAt" to (t.seenAt.takeIf { it > 0L } ?: System.currentTimeMillis())),
            "${t.toFirst} picked ${Fmt.dueLabel(due)}")
    }

    /** The giver sets the date himself (Urgent with no date, or "Set date myself"). */
    fun setGiverDate(t: TeamTask, due: Long) {
        update(t, mapOf("due" to due, "dateBy" to Team.GIVER_DATE, "status" to Team.DATED, "moreAt" to 0L), "${t.fromFirst} set the date ${Fmt.dueLabel(due)}")
    }

    fun askMoreTime(t: TeamTask, due: Long, reason: String) {
        update(t, mapOf("moreDue" to due, "moreReason" to reason.trim(), "moreAt" to System.currentTimeMillis()),
            "${t.toFirst} asked for more time: ${Fmt.dueLabel(due)} · “${reason.trim()}”")
    }

    fun answerMoreTime(t: TeamTask, ok: Boolean) {
        if (ok) update(t, mapOf("due" to t.moreDue, "deadline" to maxOf(t.deadline, t.moreDue), "status" to Team.DATED, "moreAt" to 0L),
            "${t.fromFirst} gave more time: ${Fmt.dueLabel(t.moreDue)}")
        else update(t, mapOf("moreAt" to 0L), "${t.fromFirst} kept the date")
    }

    fun markDone(t: TeamTask) {
        update(t, mapOf("status" to Team.DONE, "doneAt" to System.currentTimeMillis(), "askAt" to 0L, "stuckAt" to 0L),
            if (t.proofAt > 0L) "✅ Done with photo" else "✅ Done by ${t.toFirst}")
    }

    /** Received, thanks ✓: closes it and thanks him. */
    fun check(t: TeamTask) {
        update(t, mapOf("status" to Team.CHECKED, "checkedAt" to System.currentTimeMillis()), "${t.fromFirst}: Received, thanks ✓")
    }

    /** Not done yet: his task opens again with your reason. */
    fun notDoneYet(t: TeamTask, reason: String) {
        val why = reason.trim()
        update(t, mapOf("status" to Team.REOPENED, "reason" to why, "doneAt" to 0L), "${t.fromFirst}: Not done yet" + if (why.isEmpty()) "" else " · “$why”")
    }

    fun passedDown(t: TeamTask, to: String) {
        update(t, mapOf("passedTo" to to), "${t.toFirst} passed it to $to")
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

    fun cancel(t: TeamTask) {
        update(t, mapOf("status" to Team.CANCELLED), "Cancelled by ${t.fromFirst}")
    }

    // ------------------------------------------------------------ talking on a task

    private fun msgs(taskId: String) = tasksRef().document(taskId).collection("msgs")

    /**
     * Sends a message on [t]'s Timeline (it shows at once and goes up when data is on). [tag] ❓ Ask pauses his
     * no-date timer until the giver answers; ⚠ Stuck shows red. Any message from the giver answers both.
     */
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
        msgs(t.id).document().set(doc)
        val preview = when (kind) {
            Team.VOICE -> "🎤 Voice note"
            Team.PHOTO -> "📷 Photo" + if (text.isNotBlank()) ": ${text.trim()}" else ""
            else -> text.trim()
        }.take(120)
        val f = mutableMapOf<String, Any>("lastMsgAt" to now, "lastMsgBy" to u.uid, "lastMsgText" to preview)
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

    /** The Timeline's messages, oldest first (null until the first read). */
    fun watchMsgs(taskId: String, onChange: (List<TeamMsg>) -> Unit): ListenerRegistration? {
        if (teamId == null) return null
        val u = uid ?: return null
        return msgs(taskId).whereArrayContains("viewers", u).addSnapshotListener { s, _ ->
            s ?: return@addSnapshotListener
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
        if (!m.active || m.managers.isEmpty()) return
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

    private fun memberOf(s: DocumentSnapshot) = TeamMember(
        uid = s.id,
        name = s.getString("name").orEmpty(),
        email = s.getString("email").orEmpty(),
        phone = s.getString("phone").orEmpty(),
        role = s.getString("role") ?: Team.MEMBER,
        reportsTo = s.getString("reportsTo").orEmpty(),
        managers = strings(s.get("managers")),
        status = s.getString("status") ?: Team.PENDING,
        joinedAt = s.getLong("joinedAt") ?: 0L,
        lastSeen = s.getLong("lastSeen") ?: 0L,
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
    )
}
