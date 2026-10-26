package app.synchrotron

import android.app.Application
import app.synchrotron.notify.Notifier
import app.synchrotron.rclone.RcloneEngine
import app.synchrotron.suite.SuiteUpdater
import app.synchrotron.sync.Scheduler
import app.synchrotron.sync.WatchService
import app.synchrotron.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SynchrotronApp : Application() {
    val updater by lazy { SuiteUpdater(this, BuildConfig.UPDATE_REPO, "$packageName.files") }

    override fun onCreate() {
        super.onCreate()
        // Loading rclone takes a few hundred ms: start now, in parallel with whatever woke the process up.
        RcloneEngine.get(this).warmUp()
        Notifier.createChannels(this)
        UpdateWorker.schedule(this)
        CoroutineScope(Dispatchers.IO).launch { Scheduler.scheduleAll(this@SynchrotronApp) }
        WatchService.refresh(this)
    }
}
