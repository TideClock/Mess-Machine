package com.tideclock.messmachine

import com.tideclock.messmachine.platform.MFile

/**
 * Sound packs from the packs release (CMP step 4): the offers, and installing, unpacking and removing them. Android's
 * is AndroidPacks (HttpURLConnection and java.util.zip); the zip and the network are the platform's.
 */
interface Packs {
    /** One pack on offer: from packs.json on the release. */
    data class Offer(val id: String, val name: String, val blurb: String, val version: Int, val clips: Int, val bytes: Long, val url: String)

    /** Installed packs: id to version. */
    val installed: MutableMap<String, Int>
    /** Register every pack already on the phone (at start). */
    fun loadInstalled()
    /** What the packs release offers right now. Network: call off the main thread. */
    fun fetchOffers(): List<Offer>
    /** Download a pack and install it. Network: call off the main thread. */
    fun install(o: Offer)
    /** Unpack a pack's zip into its own folder and register it. Every entry must stay inside that folder. */
    fun installZip(zip: MFile, id: String)
    /** Take a pack off the phone. */
    fun remove(id: String)
}
