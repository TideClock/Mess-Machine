package com.tideclock.messmachine.cloud

import kotlin.jvm.JvmField

/*
 * The data classes of docs/ACCOUNTS-API.md section 4.2. Names are the server's JSON names.
 * A missing or null text is "", number 0, bool false, list empty, unless the type is nullable.
 * JSON objects that are whole messes (doc) are held as JSON text. Wire.kt has one parser per class.
 */

class Session(@JvmField val access: String, @JvmField val refresh: String, @JvmField val expiresAt: Long /* epoch s */,
              @JvmField val userId: String, @JvmField val email: String)

class Me(@JvmField val id: String, @JvmField val display_name: String, @JvmField val name_set: Boolean, @JvmField val colour: Int,
         @JvmField val sound_bytes: Long, @JvmField val sound_limit: Long, @JvmField val messes: Int, @JvmField val mess_limit: Int,
         @JvmField val terms_ok: Boolean, @JvmField val terms_version: String, @JvmField val is_admin: Boolean)

class MessRow(@JvmField val id: String, @JvmField val name: String, @JvmField val role: String, @JvmField val owner_name: String,
              @JvmField val rev: Long, @JvmField val updated_at: String, @JvmField val members: Int, @JvmField val share_mode: String,
              @JvmField val doc_bytes: Int, @JvmField val blocked: Boolean)

class Batch(@JvmField val rev: Long, @JvmField val user: String, @JvmField val batch: String, @JvmField val v: Int,
            @JvmField val ops: List<Any?>? /* null: fetch with opsSince */, @JvmField val at: String)

class Member(@JvmField val id: String, @JvmField val name: String, @JvmField val colour: Int, @JvmField val role: String)

class Sound(@JvmField val key: String, @JvmField val sha256: String, @JvmField val src: String, @JvmField val owner: String,
            @JvmField val fmt: String, @JvmField val bytes: Long, @JvmField val seconds: Double, @JvmField val rate: Int,
            @JvmField val kind: String, @JvmField val title: String, @JvmField val bpm: Double, @JvmField val beats: Int,
            @JvmField val mkey: String, @JvmField val state: String)

class Opened(@JvmField val id: String, @JvmField val name: String, @JvmField val role: String, @JvmField val owner_id: String,
             @JvmField val rev: Long, @JvmField val doc_rev: Long, @JvmField val doc: String, @JvmField val ops_v: Int,
             @JvmField val ops: List<Batch>, @JvmField val sounds: List<Sound>, @JvmField val members: List<Member>,
             @JvmField val share_mode: String, @JvmField val share_token: String, @JvmField val topic: String)

class Rev(@JvmField val id: String, @JvmField val rev: Long)                 // create_mess, fork_mess, rename_mess, restore_version
class Saved(@JvmField val rev: Long, @JvmField val version_id: String)       // put_doc
class Prepared(@JvmField val path: String /* file on this device */, @JvmField val sha256: String, @JvmField val bytes: Long,
               @JvmField val seconds: Double, @JvmField val rate: Int)
class Registered(@JvmField val state: String, @JvmField val path: String /* in the bucket */, @JvmField val used: Long, @JvmField val limit: Long)
class Used(@JvmField val state: String, @JvmField val used: Long)            // finish_upload
class MySound(@JvmField val sha256: String, @JvmField val title: String, @JvmField val kind: String, @JvmField val bytes: Long,
              @JvmField val seconds: Double, @JvmField val state: String, @JvmField val messes: Int, @JvmField val vault: String)
class Deleted(@JvmField val state: String, @JvmField val path: String, @JvmField val used: Long, @JvmField val messes: Int)
class SoundUrl(@JvmField val key: String, @JvmField val sha256: String, @JvmField val bytes: Long, @JvmField val url: String)

// Slice B
class Since(@JvmField val rev: Long, @JvmField val doc_rev: Long, @JvmField val members: Int, @JvmField val topic: String, @JvmField val ops: List<Batch>)
class Applied(@JvmField val rev: Long, @JvmField val at: String)
class Snapped(@JvmField val doc_rev: Long, @JvmField val version_id: String)
class Invite(@JvmField val id: String, @JvmField val token: String, @JvmField val expires_at: String)
class InviteRow(@JvmField val id: String, @JvmField val expires_at: String, @JvmField val uses_left: Int, @JvmField val revoked: Boolean)
class InviteInfo(@JvmField val mess_name: String, @JvmField val owner_name: String)
class Joined(@JvmField val mess_id: String, @JvmField val role: String)
class Blocked(@JvmField val id: String, @JvmField val name: String)
class Live(@JvmField val live: Boolean, @JvmField val editors: Int)

// Slice C, D, F
class Presence(@JvmField val u: String, @JvmField val page: String, @JvmField val loop: Int, @JvmField val pad: Int,
               @JvmField val playing: Boolean, @JvmField val rec: Boolean)
class Share(@JvmField val share_mode: String, @JvmField val share_token: String)
class Shared(@JvmField val name: String, @JvmField val owner_name: String, @JvmField val mode: String, @JvmField val rev: Long,
             @JvmField val doc_rev: Long, @JvmField val doc: String, @JvmField val ops: List<Batch>, @JvmField val sounds: List<Sound>)
class VersionRow(@JvmField val id: String, @JvmField val rev: Long, @JvmField val label: String, @JvmField val by_name: String,
                 @JvmField val at: String, @JvmField val bytes: Int)
class Version(@JvmField val id: String, @JvmField val rev: Long, @JvmField val label: String, @JvmField val doc: String)
class Grant(@JvmField val version: String, @JvmField val body: String, @JvmField val body_sha256: String)
