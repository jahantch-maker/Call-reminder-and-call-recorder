package com.callbackreminder.app

import android.content.Context
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ListenerRegistration
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One seat of the website's Team section (the document id is the official Gmail). Only [filled] seats have a person. */
data class Seat(
    val id: String, val name: String, val phone: String, val designation: String, val dept: String,
    val reportsTo: String, val timetable: String, val uid: String, val filled: Boolean,
)

/** One timetable of the website's Team › Timetables. [start]/[end] are minutes of the day; [off] uses 0 = Sunday … 6 = Saturday. */
data class Timetable(val id: String, val name: String, val start: Int, val end: Int, val off: List<Int>)

/**
 * The team as the website sets it (locked 23 Nov): seats (the one name, official phone, chart), timetables and the company
 * holidays. Firestore keeps them on the phone, so it all works without internet and refreshes once online. Your own
 * timetable becomes your working hours and weekly off day (read only: "Set by your manager").
 */
object Seats {
    /** Nobody on a timetable yet: 11 AM - 9 PM, Friday off (same as the website and the cloud). */
    val FALLBACK = Timetable("", "", 11 * 60, 21 * 60, listOf(5))
    /** The website's designation colours (lil, blu, yel, peach), in the order of Departments & designations. */
    val CHIP_COLORS = listOf(0xFFE2D3EC, 0xFFCBDDEA, 0xFFF7D774, 0xFFF2C7A5)
    private val SEED_DESIG = listOf("GM", "Site Manager", "Admin", "Surveyor", "Store Keeper")
    const val OWNERS = "@owners"

    private val _seats = MutableStateFlow<List<Seat>>(emptyList())
    val seats: StateFlow<List<Seat>> = _seats.asStateFlow()
    private val _timetables = MutableStateFlow<List<Timetable>>(emptyList())
    val timetables: StateFlow<List<Timetable>> = _timetables.asStateFlow()
    private val _desigs = MutableStateFlow<List<String>>(emptyList())
    private var seatsIn = false
    private var desigIn = false
    @Volatile private var holidays: Set<String>? = null

    private fun p(c: Context) = c.getSharedPreferences("team_cal", Context.MODE_PRIVATE)

    /** Seats, timetables and holidays of [team]; [changed] runs when the seats or timetables change. */
    fun listen(c: Context, team: DocumentReference, changed: () -> Unit): List<ListenerRegistration> {
        val ctx = c.applicationContext
        return listOf(
            team.collection("seats").addSnapshotListener { s, _ ->
                s ?: return@addSnapshotListener
                _seats.value = s.documents.map(::seatOf)
                seatsIn = true
                changed()
            },
            team.collection("config").document("designations").addSnapshotListener { s, _ ->
                s ?: return@addSnapshotListener
                _timetables.value = (s.get("timetables") as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::timetableOf) }
                _desigs.value = (s.get("list") as? List<*>).orEmpty().mapNotNull { ((it as? Map<*, *>)?.get("name") as? String) }
                desigIn = true
                changed()
            },
            team.collection("config").document("holidays").addSnapshotListener { s, _ ->
                s ?: return@addSnapshotListener
                val days = (s.get("dates") as? List<*>).orEmpty().mapNotNull { ((it as? Map<*, *>)?.get("d") as? String) }.toSet()
                holidays = days
                p(ctx).edit().putString("holidays", days.joinToString(",")).apply()
            },
        )
    }

    /** Signed out or left: the team's hours no longer apply (yours stay as they were, editable again). */
    fun clear(c: Context) {
        _seats.value = emptyList()
        _timetables.value = emptyList()
        seatsIn = false
        desigIn = false
        p(c).edit().remove("managed").remove("tt").apply()
    }

    private fun seatOf(d: DocumentSnapshot) = Seat(
        id = d.id, name = d.getString("name").orEmpty(), phone = d.getString("phone").orEmpty(),
        designation = d.getString("designation").orEmpty(), dept = d.getString("dept").orEmpty(),
        reportsTo = d.getString("reportsTo").orEmpty(), timetable = d.getString("timetable").orEmpty(),
        uid = d.getString("uid").orEmpty(), filled = d.getString("status") == "filled" && !d.getString("uid").isNullOrBlank(),
    )

    private fun timetableOf(m: Map<*, *>): Timetable? {
        val id = m["id"] as? String ?: return null
        return Timetable(id, m["name"] as? String ?: "", (m["start"] as? Number)?.toInt() ?: FALLBACK.start,
            (m["end"] as? Number)?.toInt() ?: FALLBACK.end, (m["off"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() })
    }

    /** The seat of this person (by uid, else by seat id). */
    fun of(uid: String, seatId: String = ""): Seat? = _seats.value.firstOrNull { it.filled && it.uid == uid }
        ?: seatId.takeIf { it.isNotBlank() }?.let { id -> _seats.value.firstOrNull { it.id == id } }

    fun seat(id: String): Seat? = _seats.value.firstOrNull { it.id == id }

    fun timetableOf(s: Seat?): Timetable? = s?.let { x -> _timetables.value.firstOrNull { it.id == x.timetable } }

    /** A timetable's name is its hours and days off, as on the website (locked 23 Nov): "8 AM to 5 PM · Fri off". */
    fun ttLabel(t: Timetable?): String = if (t == null) "Not set yet · 11 AM to 9 PM · Fri off for now" else ttName(t)

    fun ttName(t: Timetable): String {
        fun hs(min: Int): String { val h = (min / 60) % 24; val m = min % 60
            return "${if (h % 12 == 0) 12 else h % 12}${if (m > 0) ":" + m.toString().padStart(2, '0') else ""} ${if (h < 12) "AM" else "PM"}" }
        val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val off = listOf(1, 2, 3, 4, 5, 6, 0).filter { it in t.off }
        return "${hs(t.start)} to ${hs(t.end)} · " + if (off.isEmpty()) "No day off" else off.joinToString(", ") { names[it] } + " off"
    }

    /** Website week days (0 = Sunday) as the phone's mask (bit 0 = Monday … bit 6 = Sunday). */
    private fun maskOf(off: List<Int>): Int = off.fold(0) { m, d -> m or (1 shl ((if (d == 0) 7 else d) - 1)) }

    /** The chip colour of a designation, as on the website. */
    fun chipColor(designation: String): Long {
        val list = _desigs.value.ifEmpty { SEED_DESIG }.let { if ("Owner" in it) it else it + "Owner" }
        return CHIP_COLORS[list.indexOf(designation).coerceAtLeast(0) % CHIP_COLORS.size]
    }

    /** Your working hours and weekly off day come from your seat's timetable (read only on the phone). */
    fun managed(c: Context): Boolean = p(c).getBoolean("managed", false)

    /** Your timetable's name ("" before one is set). */
    fun myTimetable(c: Context): String = p(c).getString("tt", "").orEmpty()

    /** Copies your seat's timetable into Working hours and Weekly off day, once both lists have arrived. */
    fun applyMine(c: Context, uid: String, seatId: String) {
        if (!seatsIn || !desigIn) return
        val s = of(uid, seatId) ?: return
        val t = timetableOf(s)
        val use = t ?: FALLBACK
        if (!WorkHours.isSet(c) || WorkHours.start(c) != use.start || WorkHours.end(c) != use.end) WorkHours.set(c, use.start, use.end)
        val mask = maskOf(use.off)
        OffDays.init(c)
        if (OffDays.mask.value != mask) OffDays.set(c, mask)
        p(c).edit().putBoolean("managed", true).putString("tt", t?.let(::ttName).orEmpty()).apply()
    }

    /** A company holiday (website Settings › Holidays): no working hours that day. */
    fun isHoliday(c: Context, d: LocalDate): Boolean {
        val set = holidays ?: p(c).getString("holidays", "").orEmpty().split(',').filter { it.isNotBlank() }.toSet().also { holidays = it }
        return d.toString() in set
    }

    /**
     * The website chart as one list: the company owners first, then everyone under their heads, in the same order.
     * Only people who have joined; an empty seat's people move up to its place. Pairs of (seat, depth).
     */
    fun tree(): List<Pair<Seat, Int>> {
        val all = _seats.value
        val ids = all.map { it.id }.toSet()
        val board = all.filter { it.designation == "Owner" && (it.reportsTo.isBlank() || (it.reportsTo != OWNERS && it.reportsTo !in ids)) }
        val boardIds = board.map { it.id }.toSet()
        val out = mutableListOf<Pair<Seat, Int>>()
        val seen = HashSet<String>()
        fun walk(s: Seat, depth: Int) {
            if (!seen.add(s.id)) return
            if (s.filled) out.add(s to depth)
            all.filter { it.reportsTo == s.id && it.id !in boardIds }.forEach { walk(it, if (s.filled) depth + 1 else depth) }
        }
        board.forEach { walk(it, 0) }
        all.filter { it.reportsTo == OWNERS && it.id !in boardIds }.forEach { walk(it, if (board.any { b -> b.filled }) 1 else 0) }
        all.filter { it.id !in boardIds && it.reportsTo != OWNERS && (it.reportsTo.isBlank() || it.reportsTo !in ids || it.reportsTo == it.id) }.forEach { walk(it, 0) }
        return out
    }
}
