package app.synchrotron.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.synchrotron.data.AppDb
import app.synchrotron.sync.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SYNC_NOW: other apps ask for a sync of an EXISTING pair, identified by id or by its local folder.
 * It cannot create pairs or reach any other data; at worst it makes a pair sync a bit more often.
 */
class SyncNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDb.get(app).pairs()
                val id = intent.getLongExtra("pair_id", -1L)
                val pair = if (id >= 0) dao.get(id) else intent.getStringExtra("local_path")?.let { dao.byLocalPath(it) }
                if (pair != null && pair.initialized) Scheduler.syncNow(app, pair.id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object { const val ACTION = "app.synchrotron.action.SYNC_NOW" }
}
