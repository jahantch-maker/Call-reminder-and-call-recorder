package com.callbackreminder.app

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

/**
 * 🟢 Online now, live: while the app is open this phone is marked online in Realtime Database, and the server marks
 * it offline the moment the connection drops (onDisconnect), so a closed app or a phone with no data shows as gone
 * at once instead of after 15 minutes. Until Realtime Database is switched on in Firebase nothing connects and the
 * old "opened in the last 15 minutes" is used.
 */
object TeamPresence {
    /** The Rafiq Builders database (made in the default us-central1 location). */
    private const val URL = "https://rafiq-builders-default-rtdb.firebaseio.com"

    private class Seen(val on: Boolean, val at: Long)

    @Volatile private var seen: Map<String, Seen> = emptyMap()
    private var listening: Pair<String, ValueEventListener>? = null
    private var connected: ValueEventListener? = null

    private fun db(): FirebaseDatabase? = runCatching { FirebaseDatabase.getInstance(URL) }.getOrNull()

    /** True when Realtime Database has told us about this person (then it is the one that counts). */
    fun known(uid: String): Boolean = uid in seen
    fun online(uid: String): Boolean = seen[uid]?.on == true
    /** When they came online or went offline (0 = not known). */
    fun lastAt(uid: String): Long = seen[uid]?.at ?: 0L

    /** The app came to the front ([on]) or left it: mark this phone, and follow the team while it's open. */
    fun onForeground(on: Boolean) {
        val d = db() ?: return
        val me = TeamRepo.uid ?: return
        val team = TeamRepo.teamId ?: return
        runCatching {
            val mine = d.getReference("presence/$team/$me")
            if (!on) {
                listening?.let { (path, l) -> d.getReference(path).removeEventListener(l) }
                listening = null
                mine.setValue(mapOf("on" to false, "at" to ServerValue.TIMESTAMP))
                // Closing the connection runs the server's onDisconnect too, if the write above can't go.
                d.goOffline()
                return
            }
            d.goOnline()
            if (connected == null) {
                val l = object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        if (s.getValue(Boolean::class.java) != true) return
                        val ref = d.getReference("presence/${TeamRepo.teamId ?: return}/${TeamRepo.uid ?: return}")
                        ref.onDisconnect().setValue(mapOf("on" to false, "at" to ServerValue.TIMESTAMP))
                        ref.setValue(mapOf("on" to true, "at" to ServerValue.TIMESTAMP))
                    }
                    override fun onCancelled(e: DatabaseError) {}
                }
                d.getReference(".info/connected").addValueEventListener(l)
                connected = l
            }
            val path = "presence/$team"
            if (listening?.first != path) {
                listening?.let { (p, l) -> d.getReference(p).removeEventListener(l) }
                val l = object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        seen = s.children.mapNotNull { c ->
                            val key = c.key ?: return@mapNotNull null
                            val onNow = c.child("on").getValue(Boolean::class.java) == true
                            val at = (c.child("at").value as? Number)?.toLong() ?: 0L
                            key to Seen(onNow, at)
                        }.toMap()
                        TeamRepo.presenceChanged()
                    }
                    override fun onCancelled(e: DatabaseError) {}
                }
                d.getReference(path).addValueEventListener(l)
                listening = path to l
            }
        }
    }
}
