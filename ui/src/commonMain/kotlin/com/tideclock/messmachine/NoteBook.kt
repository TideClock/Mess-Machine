package com.tideclock.messmachine

/** A note whose GitHub page was opened, not yet seen on GitHub (Submit may not have been tapped). */
const val OPENED = "opened on GitHub, not seen there yet"

data class MNote(
    val id: String, val cat: String, val topic: String, val text: String, val at: Long, val build: String, val screen: String,
    val extra: String = "", val sent: Boolean = false, val issue: Int = 0, val status: String = "", val replies: List<String> = emptyList(),
    /** A quick note keeps the phone's state and last taps from the moment you spoke, not from when it is sent. */
    val ctx: String = "", val ctxTrail: String = "",
)

val NOTE_CATS = listOf("bug" to "Bug", "tuning" to "Tuning", "feature" to "Feature", "question" to "Question", "discuss" to "Discuss", "wishlist" to "Wishlist")
fun catLabel(c: String) = NOTE_CATS.firstOrNull { it.first == c }?.second ?: "Note"

const val REPO = "hubbub0/Mess-Machine"

/**
 * The notes (the BUG button) as AppState sees them (CMP step 4, NotesOut): kept on the phone, sent as GitHub issues,
 * replies read back. Android's is NotesBook.
 */
interface NoteBook {
    val notes: MutableList<MNote>
    val checking: Boolean
    val lastCheck: String
    val hasKey: Boolean
    /** what happened lately, for the issue body */
    fun trail(what: String)
    fun trailText(): String
    fun add(n: MNote)
    fun remove(id: String)
    fun parsePrefix(text: String, cat: String, topic: String): Triple<String, String, String>
    fun saveKey(k: String)
    fun forgetKey()
    fun send(n: MNote, details: String, done: (String) -> Unit = {})
    fun sendWaiting(details: (MNote) -> String, done: (String) -> Unit)
    fun openBundle(waiting: List<MNote>, details: String): Int
    fun checkReplies(done: (String) -> Unit)
}
