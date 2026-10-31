package com.callbackreminder.app

/**
 * v0.49 The app switches the Rafiq Builders portal sets for each person (Team › Access). They are read live from his
 * member row, so a change reaches the phone in a few seconds. A switch that is OFF hides its button (the layout moves
 * up); until the portal has set any switch the old behaviour applies. Individual users (not in the team) are unaffected.
 */
object TeamAccess {
    /** Whether [key] is ON for me. [legacy] is the answer before the portal set anything, and for people outside the team. */
    fun ok(key: String, legacy: Boolean = true): Boolean {
        val me = TeamRepo.me.value
        return if (me == null || !me.active) legacy else me.can(key, legacy)
    }

    /** "Call recording": OFF for Rafiq Builders members unless the portal turns it on; individual users record as before. */
    fun recordingAllowed(): Boolean {
        val me = TeamRepo.me.value ?: return true
        if (!me.active || me.seatId.isBlank()) return true
        return me.can("callRec", false)
    }
}
