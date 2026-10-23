package app.synchrotron.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.synchrotron.sync.Scheduler
import app.synchrotron.sync.WatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-plans the periodic syncs after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Scheduler.scheduleAll(app)
                WatchService.refresh(app)
            } finally {
                pending.finish()
            }
        }
    }
}
