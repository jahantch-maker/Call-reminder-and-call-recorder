package com.callbackreminder.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** One of a contact's numbers, and whether WhatsApp has marked it as its own in the phone's contacts. */
data class WaOption(val number: String, val label: String, val onWhatsApp: Boolean)

/** A contact's numbers for the WhatsApp picker. [options] is empty when the number isn't in contacts. */
data class WaLookup(val name: String, val options: List<WaOption>)

/**
 * Which number the WhatsApp buttons use for a person with two or more numbers. WhatsApp adds its own entries
 * to the phone's contacts for each number that has an account (data rows like "923001234567@s.whatsapp.net"),
 * so the app can usually tell by itself; otherwise you pick once. The choice is kept per person: under every
 * number of that contact, so a follow-up saved with any of them finds it. Calls and SMS keep the saved number.
 */
object WaNumbers {
    private const val WA_MIME = "vnd.android.cursor.item/vnd.com.whatsapp%"

    private fun p(c: Context) = c.getSharedPreferences("wa_numbers", Context.MODE_PRIVATE)

    /** The number you (or the app) chose for this person, or null. */
    fun chosen(c: Context, number: String): String? =
        RecentPeople.key(number).takeIf { it.isNotEmpty() }?.let { p(c).getString(it, null) }

    /** Keeps [chosen] for the person behind [number] and all their other numbers. */
    fun save(c: Context, number: String, options: List<WaOption>, chosen: String) {
        val e = p(c).edit()
        (options.map { it.number } + number).map { RecentPeople.key(it) }.filter { it.isNotEmpty() }.toSet()
            .forEach { e.putString(it, chosen) }
        e.apply()
    }

    fun sameNumber(c: Context, a: String, b: String): Boolean =
        WhatsApp.international(c, a) == WhatsApp.international(c, b)

    /** Contacts whose name has [name] in it ("Basharat Site", "Basharat 2"), one number each: suggestions at approval. */
    fun contactsNamed(c: Context, name: String): List<Pair<String, String>> {
        val q = name.trim()
        if (q.length < 2 || !ContactLookup.hasPermission(c)) return emptyList()
        return try {
            val out = linkedMapOf<String, String>()
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )?.use { cur ->
                while (cur.moveToNext() && out.size < 8) {
                    val n = cur.getString(0).orEmpty()
                    val num = cur.getString(1)?.trim().orEmpty()
                    if (n.isNotBlank() && num.isNotBlank() && n !in out) out[n] = num
                }
            }
            out.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The contact's name and numbers. Nothing without contacts permission or when [number] isn't saved. */
    fun lookup(c: Context, number: String): WaLookup {
        if (number.isBlank() || !ContactLookup.hasPermission(c)) return WaLookup("", emptyList())
        return try {
            val r = c.contentResolver
            var name = ""
            val ids = mutableSetOf<Long>()
            r.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number.trim())),
                arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    ids += cur.getLong(0)
                    if (name.isEmpty()) name = cur.getString(1).orEmpty()
                }
            }
            if (ids.isEmpty()) return WaLookup("", emptyList())
            val inIds = ids.joinToString(",")

            // WhatsApp's own rows: the account's number before the "@".
            val wa = mutableSetOf<String>()
            r.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DATA1),
                "${ContactsContract.Data.CONTACT_ID} IN ($inIds) AND ${ContactsContract.Data.MIMETYPE} LIKE ?",
                arrayOf(WA_MIME), null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val d = cur.getString(0).orEmpty()
                    if ('@' in d) d.substringBefore('@').filter { it.isDigit() }.takeIf { it.length >= 6 }?.let { wa += it }
                }
            }

            val seen = mutableSetOf<String>()
            val out = mutableListOf<WaOption>()
            r.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                ),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} IN ($inIds)",
                null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val num = cur.getString(0)?.trim().orEmpty()
                    if (num.isEmpty()) continue
                    val intl = WhatsApp.international(c, num)
                    if (!seen.add(intl)) continue
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(c.resources, cur.getInt(1), cur.getString(2)).toString()
                    out += WaOption(num, label, intl in wa)
                }
            }
            WaLookup(name, out)
        } catch (e: Exception) {
            WaLookup("", emptyList())
        }
    }

    /**
     * The number to open for [number]: your choice, the only number, or the one WhatsApp marked (kept, with
     * [onAutoPick] told). Null means the app can't tell and you need to pick.
     */
    fun resolve(c: Context, number: String, onAutoPick: (String) -> Unit = {}): String? {
        chosen(c, number)?.let { return it }
        val l = lookup(c, number)
        if (l.options.size <= 1) return number
        val marked = l.options.filter { it.onWhatsApp }
        if (marked.size == 1) {
            save(c, number, l.options, marked[0].number)
            onAutoPick(marked[0].number)
            return marked[0].number
        }
        return null
    }
}

/**
 * The "Which number has WhatsApp?" sheet. With a message it opens WhatsApp on the picked number; without one
 * (from the person page's Change) it only saves the choice. Either way the choice is kept for the person.
 */
class WaNumberActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_NUMBER = "wa_number"
        private const val EXTRA_TEXT = "wa_text"

        /** Ask, then open WhatsApp with [text]. */
        fun pickAndOpen(c: Context, number: String, text: String) = start(c, number, text)

        /** Change the person's WhatsApp number (no chat opens). */
        fun change(c: Context, number: String) = start(c, number, null)

        private fun start(c: Context, number: String, text: String?) {
            c.startActivity(
                Intent(c, WaNumberActivity::class.java)
                    .putExtra(EXTRA_NUMBER, number)
                    .apply { if (text != null) putExtra(EXTRA_TEXT, text) }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT)
        val l = WaNumbers.lookup(this, number)
        if (l.options.isEmpty()) {
            // Not in contacts (or no permission): nothing to choose from.
            if (text != null) WhatsApp.launch(this, number, text)
            finish()
            return
        }
        setContent {
            CallbackTheme {
                Picker(number, text, l)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Picker(number: String, text: String?, l: WaLookup) {
        val cs = MaterialTheme.colorScheme
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        val marked = l.options.count { it.onWhatsApp }
        val first = l.name.trim().substringBefore(' ').ifBlank { "this person" }
        val who = l.name.ifBlank { number }
        var picked by rememberSaveable {
            mutableStateOf(
                WaNumbers.chosen(this, number)?.let { c -> l.options.firstOrNull { WaNumbers.sameNumber(this, it.number, c) }?.number }
                    ?: l.options.firstOrNull { it.onWhatsApp }?.number
            )
        }
        fun close(after: () -> Unit = {}) {
            scope.launch { sheet.hide() }.invokeOnCompletion { after(); finish() }
        }

        ModalBottomSheet(onDismissRequest = { finish() }, sheetState = sheet) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp + navBarMissing()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (text != null) "Which number has WhatsApp?" else "WhatsApp number for $first",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold
                )
                Text(
                    when {
                        text == null -> "Calls and SMS keep using $number."
                        marked == 0 -> "$who has ${l.options.size} numbers. Your contacts don't show which one is on WhatsApp."
                        else -> "$who has ${l.options.size} numbers and $marked of them are on WhatsApp."
                    },
                    color = cs.onSurfaceVariant, fontSize = 13.5.sp
                )
                Spacer(Modifier.height(2.dp))
                l.options.forEach { o ->
                    NumberRow(
                        o = o,
                        forCalls = WaNumbers.sameNumber(this@WaNumberActivity, o.number, number),
                        anyMarked = marked > 0,
                        on = picked == o.number,
                        onClick = { picked = o.number }
                    )
                }
                Text(
                    if (text != null) "ⓘ The app remembers this for $first. Change it later on their page in People."
                    else "ⓘ WhatsApp buttons on their cards and their daily list on WhatsApp use this number.",
                    color = cs.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { close() }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
                    Button(
                        onClick = {
                            picked?.let { n ->
                                WaNumbers.save(this@WaNumberActivity, number, l.options, n)
                                close { if (text != null) WhatsApp.launch(this@WaNumberActivity, n, text) }
                            }
                        },
                        enabled = picked != null,
                        colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen, contentColor = WhatsAppInk),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text(if (text != null) "Open WhatsApp" else "Save", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    @Composable
    private fun NumberRow(o: WaOption, forCalls: Boolean, anyMarked: Boolean, on: Boolean, onClick: () -> Unit) {
        val cs = MaterialTheme.colorScheme
        val border by animateColorAsState(if (on) WhatsAppGreen else cs.outlineVariant, tween(200), label = "border")
        val bg by animateColorAsState(if (on) WhatsAppGreen.copy(alpha = 0.14f) else cs.surface, tween(200), label = "bg")
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(bg)
                .border(BorderStroke(1.5.dp, border), RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(22.dp).border(2.dp, if (on) WhatsAppGreen else cs.onSurfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (on) Box(Modifier.size(11.dp).clip(CircleShape).background(WhatsAppGreen))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(o.number, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Text(
                    o.label + if (forCalls) " · used for calls" else "",
                    color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
            }
            when {
                o.onWhatsApp -> Tag("WhatsApp", WhatsAppGreen.copy(alpha = 0.18f), WhatsAppGreen)
                anyMarked -> Tag("Not on WhatsApp", cs.surfaceVariant, cs.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun Tag(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
        Text(
            text,
            color = fg, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
