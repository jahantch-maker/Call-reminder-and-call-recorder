package com.callbackreminder.app

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.abs

/**
 * The caller card: when someone with pending tasks calls (or you call them), a card with their tasks
 * shows over the call screen. Once the call is answered it shrinks to a small "3 tasks" bubble at the
 * screen edge; tap it to read the tasks while you talk. It goes away when the call ends. Only shows for
 * people with at least one pending call or text task. On/off in Settings › After-call popup.
 */
object CallerCard {
    private const val NOTIF_ID = 1_099_100
    /** A call the app placed counts as "this number is being dialled" for this long. */
    private const val DIAL_WINDOW_MS = 90_000L

    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var shown: Info? = null
    private var expanded = false
    private var bubbleY = -1

    private var dialledNumber = ""
    private var dialledAt = 0L

    /** What the card shows for one person. */
    data class Info(
        val key: String,
        val name: String,
        val pending: Int,
        val overdue: Int,
        /** Up to 3 lines, overdue first. */
        val lines: List<String>,
        val more: Int,
        val lastNote: String,
    )

    // ---- Setting ----

    private fun p(c: Context) = c.getSharedPreferences("caller_card", Context.MODE_PRIVATE)
    fun enabled(c: Context) = p(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) {
        p(c).edit().putBoolean("on", on).apply()
        if (!on) hide(c)
    }

    // ---- Hooks ----

    /** The app is placing a call to [number]: the phone's own broadcast may not say who it is. */
    fun dialling(number: String) {
        dialledNumber = number
        dialledAt = System.currentTimeMillis()
    }

    /** A phone (SIM) call changed state. [number] is the caller, when Android tells us. */
    fun onPhoneState(ctx: Context, state: String, number: String?) {
        val app = ctx.applicationContext
        main.post {
            try {
                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING ->
                        if (!number.isNullOrBlank()) showFor(app, infoForNumber(app, number), expand = true)
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        if (shown != null) {
                            collapse(app)
                        } else {
                            val n = number?.takeIf { it.isNotBlank() }
                                ?: dialledNumber.takeIf { System.currentTimeMillis() - dialledAt < DIAL_WINDOW_MS }
                            if (!n.isNullOrBlank()) showFor(app, infoForNumber(app, n), expand = false)
                        }
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        dialledNumber = ""
                        hide(app)
                    }
                }
            } catch (e: Exception) {
                // A card that can't show must never get in the way of the call.
            }
        }
    }

    /** A WhatsApp call is ringing or running. WhatsApp only gives the name, so we match on that. */
    fun onWhatsApp(ctx: Context, name: String, connected: Boolean) {
        if (name.isBlank()) return
        val app = ctx.applicationContext
        main.post {
            try {
                if (shown == null) showFor(app, infoForName(app, name), expand = !connected)
                else if (connected && expanded) collapse(app)
            } catch (e: Exception) {
            }
        }
    }

    fun onWhatsAppEnded(ctx: Context) {
        val app = ctx.applicationContext
        main.post { hide(app) }
    }

    /**
     * Settings' "Try it": the card for the person with the most pending work, gone after 10 seconds.
     * Returns false when nobody has pending tasks.
     */
    fun preview(ctx: Context): Boolean {
        val app = ctx.applicationContext
        init(app)
        val top = Work.people(System.currentTimeMillis(), ReminderStore.items.value, SmsStore.items.value, MyTaskStore.items.value)
            .maxByOrNull { it.pending } ?: return false
        val info = infoFor(app, top.key) ?: return false
        main.post { showFor(app, info, expand = true) }
        main.postDelayed({ if (shown?.key == info.key) hide(app) }, 10_000L)
        return true
    }

    // ---- What to show ----

    private fun init(ctx: Context) {
        ReminderStore.init(ctx)
        SmsStore.init(ctx)
        MyTaskStore.init(ctx)
        RecordingStore.init(ctx)
    }

    fun infoForNumber(ctx: Context, number: String): Info? {
        init(ctx)
        val key = RecentPeople.key(number)
        if (key.isEmpty()) return null
        return infoFor(ctx, key)
    }

    fun infoForName(ctx: Context, name: String): Info? {
        init(ctx)
        val wanted = name.trim().lowercase()
        val number = (ReminderStore.items.value.map { it.name to it.number } +
            SmsStore.items.value.map { it.name to it.number } +
            MyTaskStore.items.value.map { it.name to it.number })
            .firstOrNull { it.first.trim().lowercase() == wanted && it.second.isNotBlank() }?.second
            ?: return null
        return infoFor(ctx, RecentPeople.key(number))
    }

    private fun infoFor(ctx: Context, key: String): Info? {
        val now = System.currentTimeMillis()
        val calls = ReminderStore.items.value.filter { RecentPeople.key(it.number) == key }
        val texts = SmsStore.items.value.filter { RecentPeople.key(it.number) == key }
        val mine = MyTaskStore.items.value.filter { it.hasPerson && RecentPeople.key(it.number) == key }
        val open = Work.open(now, calls, texts, mine, emptyList(), emptyMap(), includeDossier = false)
            .sortedWith(compareByDescending<WorkItem> { it.overdue }.thenBy { it.at })
        if (open.isEmpty()) return null
        val name = (calls.map { it.createdAt to it.name } + texts.map { it.createdAt to it.name } + mine.map { it.createdAt to it.name })
            .filter { it.second.isNotBlank() }.maxByOrNull { it.first }?.second
            ?: open.first().number
        val lines = open.take(3).map { w ->
            val what = when (w.kind) {
                WorkItem.Kind.CALL -> "Call" + (calls.firstOrNull { it.id == w.id }?.note?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: " back")
                WorkItem.Kind.TEXT -> texts.firstOrNull { it.id == w.id }?.let { t ->
                    (if (t.code.isNotEmpty()) "${t.code} " else "") + t.message
                } ?: "Text"
                else -> mine.firstOrNull { it.id == w.id }?.title ?: "My task"
            }
            val days = ((now - w.at) / 86_400_000L).toInt()
            val whenText = when {
                w.inReview -> "to review"
                w.overdue && days >= 1 -> "overdue $days ${if (days == 1) "day" else "days"}"
                w.overdue -> "overdue"
                else -> Fmt.dueLabel(w.at)
            }
            "$what ($whenText)"
        }
        val noteFromCalls = calls.filter { it.note.isNotBlank() }.maxByOrNull { it.createdAt }?.let { it.createdAt to it.note }
        val noteFromRec = RecordingStore.items.value
            .filter { it.note.isNotBlank() && it.number.isNotBlank() && RecentPeople.key(it.number) == key }
            .maxByOrNull { it.startedAt }?.let { it.startedAt to it.note }
        val lastNote = listOfNotNull(noteFromCalls, noteFromRec).maxByOrNull { it.first }?.second.orEmpty()
        return Info(
            key = key,
            name = name,
            pending = open.size,
            overdue = open.count { it.overdue },
            lines = lines,
            more = (open.size - 3).coerceAtLeast(0),
            lastNote = lastNote,
        )
    }

    // ---- The overlay ----

    private fun showFor(ctx: Context, info: Info?, expand: Boolean) {
        if (info == null || !enabled(ctx)) return
        shown = info
        val locked = ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        // Some phones hide overlays on the lock screen, so a notification goes along with the card there.
        if (!Settings.canDrawOverlays(ctx) || locked) notify(ctx, info)
        if (!Settings.canDrawOverlays(ctx)) return
        if (expand) expand(ctx) else collapse(ctx)
    }

    private fun wm(ctx: Context) = ctx.getSystemService(WindowManager::class.java)

    private fun remove(ctx: Context) {
        view?.let { v ->
            try {
                wm(ctx).removeView(v)
            } catch (e: Exception) {
            }
        }
        view = null
    }

    fun hide(ctx: Context) {
        remove(ctx)
        shown = null
        expanded = false
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
    }

    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun params(ctx: Context, width: Int, gravity: Int, y: Int) = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        this.gravity = gravity
        this.y = y
    }

    private fun text(ctx: Context, s: String, sp: Float, color: Int, bold: Boolean = false, lines: Int = 2) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    /** The full card near the top of the screen. Tap it to shrink to the bubble; × closes it. */
    private fun expand(ctx: Context) {
        val info = shown ?: return
        remove(ctx)
        expanded = true
        val dark = Color.parseColor("#1B1B1F")
        val grey = Color.parseColor("#5F6368")
        val red = Color.parseColor("#B3261E")
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 14))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 22).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(ctx, 2), Color.parseColor("#0FA68E"))
            }
            elevation = dp(ctx, 8).toFloat()
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(ctx, info.name, 19f, dark, bold = true, lines = 1), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text(ctx, "✕", 20f, grey).apply {
            setPadding(dp(ctx, 12), 0, 0, 0)
            setOnClickListener { hide(ctx) }
        })
        card.addView(head)
        card.addView(text(
            ctx,
            "${info.pending} pending" + if (info.overdue > 0) " · ${info.overdue} overdue" else "",
            14f,
            if (info.overdue > 0) red else grey,
            bold = true
        ))
        info.lines.forEach { line ->
            card.addView(text(ctx, "•  $line", 15f, dark).apply { setPadding(0, dp(ctx, 6), 0, 0) })
        }
        if (info.more > 0) card.addView(text(ctx, "+${info.more} more", 14f, grey).apply { setPadding(0, dp(ctx, 4), 0, 0) })
        if (info.lastNote.isNotBlank()) {
            card.addView(text(ctx, "Last note: “${info.lastNote}”", 14f, Color.parseColor("#8A5300")).apply {
                setPadding(0, dp(ctx, 8), 0, 0)
                setTypeface(typeface, Typeface.ITALIC)
            })
        }
        card.setOnClickListener { collapse(ctx) }
        val wrap = LinearLayout(ctx).apply {
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
            addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        try {
            wm(ctx).addView(wrap, params(ctx, WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP, dp(ctx, 90)))
            view = wrap
        } catch (e: Exception) {
            view = null
        }
    }

    /** The small "3 tasks" bubble at the right edge. Drag it up or down; tap it to open the card. */
    @SuppressLint("ClickableViewAccessibility")
    private fun collapse(ctx: Context) {
        val info = shown ?: return
        remove(ctx)
        expanded = false
        val size = dp(ctx, 64)
        val bubble = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(if (info.overdue > 0) "#B3261E" else "#0FA68E"))
                setStroke(dp(ctx, 2), Color.WHITE)
            }
            elevation = dp(ctx, 8).toFloat()
            addView(text(ctx, "${info.pending}", 20f, Color.WHITE, bold = true, lines = 1).apply { gravity = Gravity.CENTER })
            addView(text(ctx, if (info.pending == 1) "task" else "tasks", 11f, Color.WHITE, lines = 1).apply { gravity = Gravity.CENTER })
        }
        if (bubbleY < 0) bubbleY = dp(ctx, 160)
        val lp = params(ctx, size, Gravity.TOP or Gravity.END, bubbleY).apply {
            height = size
            x = dp(ctx, 8)
        }
        var downY = 0f
        var startY = 0
        var moved = false
        bubble.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dy) > dp(ctx, 6)) moved = true
                    if (moved) {
                        lp.y = (startY + dy).coerceAtLeast(0)
                        bubbleY = lp.y
                        try {
                            wm(ctx).updateViewLayout(v, lp)
                        } catch (ex: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) expand(ctx)
                    true
                }
                else -> false
            }
        }
        try {
            wm(ctx).addView(bubble, lp)
            view = bubble
        } catch (e: Exception) {
            view = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(ctx: Context, info: Info) {
        if (!Notifier.canNotify(ctx)) return
        val body = (info.lines + listOfNotNull(
            "+${info.more} more".takeIf { info.more > 0 },
            "Last note: “${info.lastNote}”".takeIf { info.lastNote.isNotBlank() },
        )).joinToString("\n")
        val open = PendingIntent.getActivity(
            ctx,
            NOTIF_ID,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, Notifier.CH_POSTCALL)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("${info.name}: ${info.pending} pending" + if (info.overdue > 0) " · ${info.overdue} overdue" else "")
            .setContentText(info.lines.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build())
    }
}
