package app.synchrotron.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.synchrotron.SynchrotronApp
import app.synchrotron.notify.Notifier
import java.util.concurrent.TimeUnit

/** Once a day, on unmetered network: tells the user when a newer build is published. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updater = (applicationContext as SynchrotronApp).updater
        val release = runCatching { updater.latest() }.getOrNull()
        if (release != null && release.build > updater.installedBuild()) Notifier.updateAvailable(applicationContext, release.build)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
