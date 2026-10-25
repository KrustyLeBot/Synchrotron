package app.synchrotron.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import app.synchrotron.data.AppDb
import app.synchrotron.data.SyncMode
import app.synchrotron.data.SyncStatus
import app.synchrotron.rclone.RcloneEngine
import app.synchrotron.sync.Scheduler
import app.synchrotron.sync.SyncRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SYNC_NOW: other apps ask for a sync of an EXISTING pair, identified by id or by its local folder.
 * It cannot create pairs or reach any other data; at worst it makes a pair sync a bit more often.
 *
 * When sent as an ordered broadcast (`sendOrderedBroadcast`), the caller gets an immediate answer:
 * result code (see the `RESULT_*` constants) and, when a SYNC_FINISHED will follow, the `pair_id` extra.
 */
class SyncNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            var code = RESULT_NOT_REGISTERED
            var pairId = -1L
            try {
                val dao = AppDb.get(app).pairs()
                val id = intent.getLongExtra("pair_id", -1L)
                val pair = if (id >= 0) dao.get(id) else intent.getStringExtra("local_path")?.let { dao.byLocalPath(runCatching { java.io.File(it).canonicalPath }.getOrDefault(it)) }
                code = when {
                    pair == null -> RESULT_NOT_REGISTERED
                    !RcloneEngine.get(app).hasSession -> RESULT_NOT_LOGGED_IN
                    pair.mode == SyncMode.BIDIRECTIONAL && !pair.initialized -> RESULT_NEEDS_SETUP
                    else -> {
                        pairId = pair.id
                        when {
                            SyncRegistry.isRunning(pair.id) -> RESULT_RUNNING // its SYNC_FINISHED will follow
                            // The user's "Wi-Fi only" choice also holds for syncs requested by other apps.
                            !networkAllowed(app, pair.wifiOnly) -> RESULT_NETWORK
                            isFresh(pair.lastSyncAt, pair.lastStatus) -> RESULT_FRESH
                            else -> { Scheduler.syncNow(app, pair.id); RESULT_STARTED }
                        }
                    }
                }
            } finally {
                pending.resultCode = code
                pending.setResultExtras(Bundle().apply { if (pairId >= 0) putLong("pair_id", pairId) })
                pending.finish()
            }
        }
    }

    private fun isFresh(lastSyncAt: Long, status: SyncStatus) =
        (status == SyncStatus.OK || status == SyncStatus.CONFLICTS) && System.currentTimeMillis() - lastSyncAt < FRESH_MS

    private fun networkAllowed(context: Context, wifiOnly: Boolean): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm.activeNetwork == null) return false
        return !wifiOnly || !cm.isActiveNetworkMetered
    }

    companion object {
        const val ACTION = "app.synchrotron.action.SYNC_NOW"

        const val RESULT_STARTED = 1 // sync queued; SYNC_FINISHED will follow
        const val RESULT_RUNNING = 2 // already syncing; SYNC_FINISHED will follow
        const val RESULT_NOT_REGISTERED = 3 // no such pair: propose REGISTER_FOLDER
        const val RESULT_NEEDS_SETUP = 4 // pair exists but its first sync is not confirmed yet
        const val RESULT_NOT_LOGGED_IN = 5
        const val RESULT_FRESH = 6 // synced a few seconds ago: nothing to wait for
        const val RESULT_NETWORK = 7 // offline, or metered network with "Wi-Fi only" on

        private const val FRESH_MS = 20_000L
    }
}
