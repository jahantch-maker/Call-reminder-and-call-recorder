package com.callbackreminder.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every display size (Extra small · Small · Default · Large) on a small and a big phone: the main screens are drawn,
 * a card is opened, and nothing may stick out past the screen's sides (the v0.37 Timeline button bug). A picture of
 * each lands in app/build/screenshots, and report.txt there lists what was checked.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class DisplaySizeTest(private val size: Int, private val phone: String) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "size{0}-{1}")
        fun params(): List<Array<Any>> = Appearance.SIZES.indices.flatMap { s ->
            listOf(arrayOf<Any>(s, "small"), arrayOf<Any>(s, "big"))
        }

        private val out = File("build/screenshots").apply { mkdirs() }
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers(if (phone == "small") "w320dp-h640dp-xhdpi" else "w411dp-h891dp-xxhdpi")
        ReminderStore.init(ctx)
        SmsStore.init(ctx)
        ToScheduleStore.init(ctx)
        MyTaskStore.init(ctx)
        DossierStore.init(ctx)
        RecordingStore.init(ctx)
        OffDays.init(ctx)
        Appearance.setDisplaySize(ctx, size)
        sampleData()
    }

    /** A believable day: long task names, long person names, late ones and ones with no time. */
    private fun sampleData() {
        if (ReminderStore.items.value.isNotEmpty()) return
        val now = System.currentTimeMillis()
        val h = 3_600_000L
        runCatching { ReminderStore.add("Ali Hassan Contractor", "03001234567", "Tile order confirm with the supplier before Friday", now - 3 * h) }
        runCatching { ReminderStore.add("Basharat Ali Site Engineer", "03028603871", "Steel bar cutting list for Block C second floor slab", now + 5 * h) }
        runCatching { ReminderStore.add("Bilal", "03211234567", "Door frames advance", now + 30 * h) }
        runCatching { ReminderStore.add("Ilyas", "03331234567", "Send rate list", now - 26 * h) }
        runCatching { ToScheduleStore.add("", "", "Review Phase 2 budget with the accountant and the architect") }
        runCatching { MyTaskStore.add("Cement stock count at the main store", "", now + 2 * h, "", "") }
        runCatching { MyTaskStore.add("Call the bank about the guarantee", "", 0L, "", "") }
    }

    private fun shoot(name: String, content: @Composable () -> Unit, open: String? = null) {
        rule.setContent { CallbackTheme(dark = true) { content() } }
        rule.waitForIdle()
        if (open != null) {
            rule.onAllNodesWithText(open, substring = true).fetchSemanticsNodes().firstOrNull()?.let {
                rule.onAllNodesWithText(open, substring = true)[0].performClick()
                rule.waitForIdle()
            }
        }
        val label = "$name-${Appearance.SIZES[size].first.replace(" ", "")}-$phone"
        val shot = runCatching {
            // Draw the window straight onto a picture (the test clock never lets a screen capture finish).
            val v = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            rule.runOnUiThread { v.draw(Canvas(bmp)) }
            File(out, "$label.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.exceptionOrNull()?.let { " · no picture: $it" } ?: ""
        val root = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val width = root.boundsInRoot.width
        val off = mutableListOf<String>()
        val cut = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            val b = n.boundsInRoot
            val what = n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "node ${n.id}"
            if (b.width > 0f && (b.left < -1f || b.right > width + 1f)) off += "$what [${b.left.toInt()}..${b.right.toInt()} of ${width.toInt()}]"
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { get ->
                val rs = mutableListOf<TextLayoutResult>()
                val r = if (get(rs)) rs.firstOrNull() else null
                if (r != null) {
                    val t = r.layoutInput.text.text
                    val broken = (0 until r.lineCount - 1).any { i ->
                        val e = r.getLineEnd(i); e in 1 until t.length && (t[e - 1].isLetterOrDigit() && t[e].isLetterOrDigit() || t[e] in ",.:;!?")
                    }
                    if (broken || (0 until r.lineCount).any { r.isLineEllipsized(it) }) cut += t.replace("\n", " ")
                }
            }
            // A row that scrolls sideways may hold more than the screen on purpose.
            if (n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) == null) n.children.forEach { walk(it) }
        }
        walk(root)
        File(out, "report.txt").appendText(
            "$label: " + (if (off.isEmpty()) "nothing off screen" else "OFF SCREEN ${off.joinToString(" | ")}") +
                (if (cut.isEmpty()) "" else " · TEXT CUT: ${cut.joinToString(" | ")}") + shot + "\n"
        )
        assertTrue("$label: off screen: $off", off.isEmpty())
    }

    @Test
    fun followUps() = shoot("followups", { FollowUpsScreen(0, {}, {}) }, open = "Tile order confirm")

    @Test
    fun needsYou() = shoot("needsyou", { ReviewPage(0, {}, {}, {}) }, open = "Send rate list")

    @Test
    fun myTasks() = shoot("mytasks", { MyTasksScreen(0) }, open = "Cement stock count")

    @Test
    fun appearance() = shoot("appearance", { AppearanceSettingsScreen {} })

    @Test
    fun home() = shoot("home", {
        HomeScreen(
            resumeKey = 0, onOpenCalls = {}, onOpenTexts = { _, _ -> }, onOpenSetup = {}, onOpenRecordingSettings = {},
            onOpenToSchedule = {}, onOpenMyTasks = {}, onOpenDossierReview = {}, onOpenPeople = {}, onOpenPerson = {},
            onOpenItem = {},
        )
    })
}
