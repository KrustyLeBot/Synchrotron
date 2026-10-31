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
        // Keep the last crash where the settings can show it: without a computer there is no other way to read it.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "${java.util.Date()} · ${thread.name} · build ${BuildConfig.VERSION_NAME}\n" + android.util.Log.getStackTraceString(error).take(6000),
                )
            }
            previous?.uncaughtException(thread, error)
        }
        // Loading rclone takes a few hundred ms: start now, in parallel with whatever woke the process up.
        RcloneEngine.get(this).warmUp()
        Notifier.createChannels(this)
        UpdateWorker.schedule(this)
        CoroutineScope(Dispatchers.IO).launch { Scheduler.scheduleAll(this@SynchrotronApp) }
        WatchService.refresh(this)
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
