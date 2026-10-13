package com.callbackreminder.app

import androidx.compose.runtime.mutableStateOf
import java.time.LocalDate

/** One open task of any kind, for Home's lists, the person page and the morning summary. */
data class WorkItem(
    val kind: Kind,
    val id: Long,
    /** When it is due (a text in To review: when it arrived there). */
    val at: Long,
    val createdAt: Long,
    /** The person (calls, texts, My tasks with a person) or the task title. */
    val title: String,
    val detail: String,
    val personKey: String,
    val personName: String,
    val number: String,
    val overdue: Boolean,
    /** A text in To review, or a call in To review. */
    val inReview: Boolean = false,
) {
    enum class Kind(val label: String) { CALL("Calls"), TEXT("Texts"), MY_TASK("My tasks"), DOSSIER("Dossier") }

    val key: String get() = "${kind.name}-$id"
}

/** A person with work waiting, for the People block, the People page and the person page. */
data class PersonSummary(
    val key: String,
    val name: String,
    val number: String,
    val pending: Int,
    val overdue: Int,
    /** Times this person let a task slip: "Why not done?" moves, Not done, and texts with no reply. Your own snoozes don't count. */
    val delayed: Int,
    val done: Int,
    /** When the oldest pending task was made. */
    val oldestAt: Long,
    val noReply: Int,
) {
    val title: String get() = name.ifBlank { number }.ifBlank { "Unknown" }
}

/** What is selected on the People and person pages, kept while the app is open. */
object PeopleUi {
    /** The person page shows this person (key from [RecentPeople.key]). */
    val current = mutableStateOf<String?>(null)
}

object Work {
    private const val DAY = 86_400_000L

    fun personKey(number: String) = RecentPeople.key(number)

    /** Everything still open: calls (waiting or in To review), texts (scheduled or in To review), My tasks, and today's unticked dossier. */
    fun open(
        now: Long,
        calls: List<Reminder>,
        texts: List<SmsReminder>,
        myTasks: List<MyTask>,
        dossier: List<DossierTask>,
        records: Map<String, DossierRecord>,
        includeDossier: Boolean = true,
    ): List<WorkItem> {
        val out = mutableListOf<WorkItem>()
        calls.filter { !it.done || it.review }.forEach { r ->
            out += WorkItem(
                kind = WorkItem.Kind.CALL,
                id = r.id,
                at = if (r.done) r.doneAt else r.dueAt,
                createdAt = r.createdAt,
                title = r.title,
                detail = listOfNotNull(
                    if (r.review) "Call · to review" else "Call back",
                    r.note.takeIf { it.isNotBlank() },
                    r.delays.size.takeIf { it > 0 }?.let { "delayed ${it}×" },
                ).joinToString(" · "),
                personKey = personKey(r.number),
                personName = r.name,
                number = r.number,
                overdue = !r.done && r.dueAt <= now,
                inReview = r.review,
            )
        }
        texts.filter { !it.done }.forEach { t ->
            val review = t.review != null
            out += WorkItem(
                kind = WorkItem.Kind.TEXT,
                id = t.id,
                at = if (review) t.reviewAt else t.alarmAt,
                createdAt = t.createdAt,
                title = t.title,
                detail = listOfNotNull(
                    if (t.code.isNotEmpty()) "Text ${t.code}" else "Text",
                    if (review) "to review" else null,
                    t.message.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                personKey = personKey(t.number),
                personName = t.name,
                number = t.number,
                overdue = t.review == SmsReminder.Review.NO_REPLY || t.review == SmsReminder.Review.NOT_SENT,
                inReview = review,
            )
        }
        myTasks.filter { !it.done }.forEach { m ->
            out += WorkItem(
                kind = WorkItem.Kind.MY_TASK,
                id = m.id,
                at = m.dueAt,
                createdAt = m.createdAt,
                title = m.title,
                detail = listOfNotNull("My task", m.personTitle.takeIf { m.hasPerson }?.let { "with $it" }, m.note.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                personKey = if (m.hasPerson) personKey(m.number) else "",
                personName = m.name,
                number = m.number,
                overdue = m.dueAt <= now,
            )
        }
        if (includeDossier) {
            val day = Dossier.openDay(now)
            dossier.filter { it.isOn(day) && records[DossierStore.key(it.id, day)]?.done != true }.forEach { d ->
                out += WorkItem(
                    kind = WorkItem.Kind.DOSSIER,
                    id = d.id,
                    at = d.dueAt(day),
                    createdAt = d.createdAt,
                    title = d.title,
                    detail = "Dossier · ${d.frequency}",
                    personKey = "",
                    personName = "",
                    number = "",
                    overdue = false,
                )
            }
        }
        return out
    }

    /** Overdue work by how long it has been waiting: 1 day, 2-3, 4-7, 7+. */
    fun ageBucket(item: WorkItem, now: Long): Int {
        val days = ((now - item.at) / DAY).toInt()
        return when {
            days <= 1 -> 0
            days <= 3 -> 1
            days <= 7 -> 2
            else -> 3
        }
    }

    val AGE_LABELS = listOf("1 day", "2-3 days", "4-7 days", "7+ days")

    /** A person's slips across all their calls and texts (not your own snoozes). */
    fun delays(calls: List<Reminder>, texts: List<SmsReminder>): Int =
        calls.sumOf { it.delays.size } + texts.sumOf { t ->
            t.log.count { it.status == SmsLogEntry.Status.NO_REPLY || it.status == SmsLogEntry.Status.MARKED_NOT_DONE }
        }

    /** Everyone with a number on a call, text or My task. Only people with something pending unless [all]. */
    fun people(
        now: Long,
        calls: List<Reminder>,
        texts: List<SmsReminder>,
        myTasks: List<MyTask>,
        all: Boolean = false,
    ): List<PersonSummary> {
        val open = open(now, calls, texts, myTasks, emptyList(), emptyMap(), includeDossier = false)
            .filter { it.personKey.isNotEmpty() }
        val keys = (calls.map { personKey(it.number) } + texts.map { personKey(it.number) } +
            myTasks.filter { it.hasPerson }.map { personKey(it.number) }).filter { it.isNotEmpty() }.toSet()
        return keys.mapNotNull { k ->
            val mine = open.filter { it.personKey == k }
            if (mine.isEmpty() && !all) return@mapNotNull null
            val pc = calls.filter { personKey(it.number) == k }
            val pt = texts.filter { personKey(it.number) == k }
            val pm = myTasks.filter { it.hasPerson && personKey(it.number) == k }
            val latest = (pc.map { Triple(it.createdAt, it.name, it.number) } + pt.map { Triple(it.createdAt, it.name, it.number) } +
                pm.map { Triple(it.createdAt, it.name, it.number) }).maxByOrNull { it.first }
            val name = (pc.map { it.name } + pt.map { it.name } + pm.map { it.name }).firstOrNull { it.isNotBlank() }.orEmpty()
            PersonSummary(
                key = k,
                name = latest?.second?.takeIf { it.isNotBlank() } ?: name,
                number = latest?.third.orEmpty(),
                pending = mine.size,
                overdue = mine.count { it.overdue },
                delayed = delays(pc, pt),
                done = pc.count { it.done && !it.review } + pt.count { it.done } + pm.count { it.done },
                oldestAt = mine.minOfOrNull { it.createdAt } ?: 0L,
                noReply = pt.count { !it.done && it.review == SmsReminder.Review.NO_REPLY },
            )
        }
    }

    fun waitingDays(p: PersonSummary, now: Long): Int = if (p.oldestAt == 0L) 0 else ((now - p.oldestAt) / DAY).toInt()

    /** Like "6 pending · 2 overdue · waiting 9 days". */
    fun line(p: PersonSummary, now: Long, withWaiting: Boolean = true): String = listOfNotNull(
        "${p.pending} pending",
        p.overdue.takeIf { it > 0 }?.let { "$it overdue" },
        waitingDays(p, now).takeIf { withWaiting && it >= 1 }?.let { "waiting $it ${if (it == 1) "day" else "days"}" },
    ).joinToString(" · ")

    fun startOfDay(d: LocalDate): Long = d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}
