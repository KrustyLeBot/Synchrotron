package app.synchrotron.sync

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs a sync right away in this process, without WorkManager. Only used while the process is kept
 * alive and prioritised by something else: an app bound to [app.synchrotron.integration.SyncService],
 * or the folder watcher's foreground service. WorkManager may delay a job by seconds or minutes when
 * Synchrotron is rarely opened (app standby, expedited-job quota): the source of slow "sync on open".
 */
object SyncExecutor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun launch(context: Context, pairId: Long, options: SyncOptions = SyncOptions()): Job {
        val app = context.applicationContext
        return scope.launch { SyncRunner(app).run(pairId, options) }
    }
}
