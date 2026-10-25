package app.synchrotron.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.synchrotron.sync.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SYNC_NOW: other apps ask for a sync of an EXISTING pair, identified by id or by its local folder.
 * Older entry point; the client library prefers binding [SyncService], which starts the sync at once
 * instead of going through WorkManager.
 *
 * When sent as an ordered broadcast, the caller gets an immediate answer: result code (`SyncGate.RESULT_*`)
 * and, when a SYNC_FINISHED will follow, the `pair_id` extra.
 */
class SyncNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            var code = SyncGate.RESULT_NOT_REGISTERED
            var pairId = -1L
            try {
                val decision = SyncGate.decide(app, intent.getLongExtra("pair_id", -1L), intent.getStringExtra("local_path"))
                code = decision.code
                if (code == SyncGate.RESULT_STARTED || code == SyncGate.RESULT_RUNNING) pairId = decision.pair!!.id
                if (code == SyncGate.RESULT_STARTED) Scheduler.syncNow(app, pairId)
            } finally {
                pending.resultCode = code
                pending.setResultExtras(Bundle().apply { if (pairId >= 0) putLong("pair_id", pairId) })
                pending.finish()
            }
        }
    }

    companion object { const val ACTION = "app.synchrotron.action.SYNC_NOW" }
}
