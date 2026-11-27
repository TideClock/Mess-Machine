package com.tideclock.messmachine

import com.tideclock.messmachine.cloud.MessRow
import com.tideclock.messmachine.cloud.Sound
import com.tideclock.messmachine.cloud.StoreHost

/**
 * The app's side of CloudStore (docs/ACCOUNTS-API.md s4.4): the store decides what goes up and comes down, this
 * does it to the phone's mess files, the open screen and the sound library. Everything runs on the main thread.
 */
class CloudHost(private val s: AppState) : StoreHost {
    override fun onAccount() = s.cloudRedraw()
    override fun onMesses() = s.cloudRedraw()
    override fun localDoc(localId: String): String? = s.localMessDoc(localId)
    override fun needs(localId: String): List<String> = s.cloudNeeds(localId)
    override fun adopt(row: MessRow, doc: String): String = s.cloudAdopt(row.name, doc)
    override fun takeServer(localId: String, name: String, doc: String) = s.cloudTakeServer(localId, name, doc)
    override fun keepBoth(localId: String, copyName: String, serverName: String, serverDoc: String): String =
        s.cloudKeepBoth(localId, copyName, serverName, serverDoc)
    override fun soundArrived(s: Sound, path: String) = this.s.cloudSoundArrived(s, path)
    override fun soundGone(s: Sound) = this.s.cloudSoundGone(s)
    override fun notice(kind: Int, text: String) {
        // Tyler's answer 7: my mess changed on two of my phones, so the app asks instead of only saying so
        if (kind == com.tideclock.messmachine.cloud.CloudStore.N_TWO_PHONES) s.twoPhones = s.store?.two else s.cloudSay(text)
    }
    override fun dropLocal(localId: String) = s.cloudDropLocal(localId)
    override fun soundInfo(key: String): Sound? = s.cloudSoundInfo(key)
}
