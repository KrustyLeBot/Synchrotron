package app.synchrotron.integration

import android.content.Context
import android.net.ConnectivityManager
import app.synchrotron.data.AppDb
import app.synchrotron.data.PairEntity
import app.synchrotron.data.SyncMode
import app.synchrotron.data.SyncStatus
import app.synchrotron.rclone.RcloneEngine
import app.synchrotron.sync.SyncRegistry
import java.io.File

/**
 * Whether a sync asked for by another app (SYNC_NOW broadcast or bound [SyncService]) may run.
 * Only EXISTING pairs can be synced; nothing here creates or exposes anything.
 */
object SyncGate {
    const val RESULT_STARTED = 1 // sync starting; its end will be reported
    const val RESULT_RUNNING = 2 // already syncing; its end will be reported
    const val RESULT_NOT_REGISTERED = 3 // no such pair: propose REGISTER_FOLDER
    const val RESULT_NEEDS_SETUP = 4 // pair exists but its first sync is not confirmed yet
    const val RESULT_NOT_LOGGED_IN = 5
    const val RESULT_FRESH = 6 // synced a few seconds ago: nothing to wait for
    const val RESULT_NETWORK = 7 // offline, or metered network with "Wi-Fi only" on

    private const val FRESH_MS = 20_000L

    class Decision(val code: Int, val pair: PairEntity?)

    suspend fun decide(context: Context, pairId: Long, localPath: String?): Decision {
        val dao = AppDb.get(context).pairs()
        val pair = when {
            pairId >= 0 -> dao.get(pairId)
            localPath != null -> dao.byLocalPath(runCatching { File(localPath).canonicalPath }.getOrDefault(localPath))
            else -> null
        }
        val code = when {
            pair == null -> RESULT_NOT_REGISTERED
            !RcloneEngine.get(context).hasSession -> RESULT_NOT_LOGGED_IN
            pair.mode == SyncMode.BIDIRECTIONAL && !pair.initialized -> RESULT_NEEDS_SETUP
            SyncRegistry.isRunning(pair.id) -> RESULT_RUNNING
            // The user's "Wi-Fi only" choice also holds for syncs requested by other apps.
            !networkAllowed(context, pair.wifiOnly) -> RESULT_NETWORK
            isFresh(pair) -> RESULT_FRESH
            else -> RESULT_STARTED
        }
        return Decision(code, pair)
    }

    private fun isFresh(pair: PairEntity) =
        (pair.lastStatus == SyncStatus.OK || pair.lastStatus == SyncStatus.CONFLICTS) &&
            System.currentTimeMillis() - pair.lastSyncAt < FRESH_MS

    private fun networkAllowed(context: Context, wifiOnly: Boolean): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm.activeNetwork == null) return false
        return !wifiOnly || !cm.isActiveNetworkMetered
    }
}
