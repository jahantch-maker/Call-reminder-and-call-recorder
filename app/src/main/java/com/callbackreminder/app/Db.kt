package com.callbackreminder.app

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's database on the phone: one row per task (its JSON), so a tap saves that one task instead of rewriting
 * every list. Finished items older than [ARCHIVE_DAYS] move to an archive table: still searchable, never loaded on
 * start. The old lists in SharedPreferences are copied in once and kept untouched as a fallback.
 */
object Db {
    const val ARCHIVE_DAYS = 90
    private const val NAME = "callback.db"

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE rows (tbl TEXT NOT NULL, id INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY (tbl, id))")
            db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    @Volatile private var helper: Helper? = null

    private fun db(ctx: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) { helper ?: Helper(ctx.applicationContext).also { helper = it } }
        return h.writableDatabase
    }

    /** Lists that could not be read: they show nothing and save nothing until you restore a backup. */
    private val _failed = MutableStateFlow<Set<String>>(emptySet())
    val failed: StateFlow<Set<String>> = _failed.asStateFlow()

    internal fun markFailed(table: String) {
        _failed.value = _failed.value + table
    }

    fun rows(ctx: Context, table: String): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        db(ctx).rawQuery("SELECT id, json FROM rows WHERE tbl = ?", arrayOf(table)).use { c ->
            while (c.moveToNext()) out += c.getLong(0) to c.getString(1)
        }
        return out
    }

    fun count(ctx: Context, table: String): Int =
        db(ctx).rawQuery("SELECT COUNT(*) FROM rows WHERE tbl = ?", arrayOf(table)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Writes [changed] rows and removes [gone] ids, all at once. */
    fun write(ctx: Context, table: String, changed: List<Pair<Long, String>>, gone: Collection<Long>) {
        val d = db(ctx)
        d.beginTransaction()
        try {
            changed.forEach { (id, json) ->
                d.insertWithOnConflict("rows", null, ContentValues().apply {
                    put("tbl", table); put("id", id); put("json", json)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            gone.forEach { d.delete("rows", "tbl = ? AND id = ?", arrayOf(table, it.toString())) }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    fun meta(ctx: Context, k: String): String? =
        db(ctx).rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setMeta(ctx: Context, k: String, v: String) {
        db(ctx).insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- Backup ----

    /** Every row and setting, for the nightly backup file. */
    fun export(ctx: Context): JSONObject {
        val rows = JSONArray()
        db(ctx).rawQuery("SELECT tbl, id, json FROM rows", null).use { c ->
            while (c.moveToNext()) rows.put(JSONObject().put("t", c.getString(0)).put("i", c.getLong(1)).put("j", c.getString(2)))
        }
        val meta = JSONObject()
        db(ctx).rawQuery("SELECT k, v FROM meta", null).use { c -> while (c.moveToNext()) meta.put(c.getString(0), c.getString(1)) }
        return JSONObject().put("rows", rows).put("meta", meta)
    }

    /**
     * Restore: the file's rows replace everything. A backup from before the database (null) empties it, so the
     * lists are copied in again from the restored settings when the app restarts.
     */
    fun restore(ctx: Context, from: JSONObject?) {
        val d = db(ctx)
        d.beginTransaction()
        try {
            d.delete("rows", null, null)
            d.delete("meta", null, null)
            if (from != null) {
                val rows = from.optJSONArray("rows") ?: JSONArray()
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    d.insertWithOnConflict("rows", null, ContentValues().apply {
                        put("tbl", r.getString("t")); put("id", r.getLong("i")); put("json", r.getString("j"))
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                val meta = from.optJSONObject("meta") ?: JSONObject()
                meta.keys().forEach { k ->
                    d.insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", meta.getString(k)) }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }
}

/**
 * One list kept in [Db] (the calls, the texts, My tasks…). [save] writes only the items that changed since the last
 * save. Items that [archivable] says are finished long ago go to the archive table on start.
 */
class Rows<T>(
    private val table: String,
    private val idOf: (T) -> Long,
    private val write: (T) -> JSONObject,
    private val read: (JSONObject) -> T,
    /** The time a finished item was finished, or null while it still needs you (never archived). */
    private val archivable: (T) -> Long? = { null },
) {
    private var saved: Map<Long, T> = emptyMap()
    private var broken = false
    private val archive = "${table}_archive"

    /**
     * Reads the list. The first time, the old list from [prefs] is copied in and checked; the old copy stays as it
     * was. If reading fails the list stays empty, nothing is ever saved over the data, and [Db.failed] says so.
     */
    fun load(ctx: Context, prefs: SharedPreferences): List<T> {
        return try {
            if (Db.meta(ctx, "migrated_$table") == null) migrate(ctx, prefs)
            val items = Db.rows(ctx, table).mapNotNull { (_, json) ->
                // A row that can't be read is left in the database as it is.
                runCatching { read(JSONObject(json)) }.getOrNull()
            }
            saved = items.associateBy(idOf)
            archiveOld(ctx, items)
        } catch (e: Exception) {
            CrashLog.record(ctx, e, "Reading $table")
            broken = true
            Db.markFailed(table)
            emptyList()
        }
    }

    private fun migrate(ctx: Context, prefs: SharedPreferences) {
        val raw = prefs.getString("list", null)
        val items: List<T> = if (raw == null) emptyList()
        else JSONArray(raw).let { arr -> (0 until arr.length()).map { read(arr.getJSONObject(it)) } }.distinctBy(idOf)
        Db.write(ctx, table, items.map { idOf(it) to write(it).toString() }, emptyList())
        // Every item made it across, or the copy is not trusted.
        check(Db.count(ctx, table) >= items.size) { "copied ${Db.count(ctx, table)} of ${items.size}" }
        Db.setMeta(ctx, "migrated_$table", System.currentTimeMillis().toString())
    }

    private fun archiveOld(ctx: Context, items: List<T>): List<T> {
        val cutoff = System.currentTimeMillis() - Db.ARCHIVE_DAYS * 86_400_000L
        val old = items.filter { (archivable(it) ?: Long.MAX_VALUE) < cutoff }
        if (old.isEmpty()) return items
        Db.write(ctx, archive, old.map { idOf(it) to write(it).toString() }, emptyList())
        Db.write(ctx, table, emptyList(), old.map(idOf))
        val keep = items - old.toSet()
        saved = keep.associateBy(idOf)
        return keep
    }

    /** Saves [list]: only new and changed items are written, removed ones deleted. */
    @Synchronized
    fun save(ctx: Context, list: List<T>) {
        if (broken) return
        val now = list.associateBy(idOf)
        val changed = list.filter { saved[idOf(it)] != it }
        val gone = saved.keys - now.keys
        if (changed.isEmpty() && gone.isEmpty()) return
        Db.write(ctx, table, changed.map { idOf(it) to write(it).toString() }, gone)
        saved = now
    }

    /** The archived items (for search). */
    fun archived(ctx: Context): List<T> = runCatching {
        Db.rows(ctx, archive).mapNotNull { (_, json) -> runCatching { read(JSONObject(json)) }.getOrNull() }
    }.getOrDefault(emptyList())
}
