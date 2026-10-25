package app.synchrotron.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.synchrotron.data.AppDb
import app.synchrotron.data.PairEntity
import java.util.concurrent.TimeUnit

object Scheduler {
    private fun periodicName(id: Long) = "pair-periodic-$id"
    private fun nowName(id: Long) = "pair-now-$id"

    /** (Re)creates the periodic job of a pair; cancels it when the pair is manual-only. */
    fun schedule(context: Context, pair: PairEntity) {
        val wm = WorkManager.getInstance(context)
        if (pair.intervalMinutes <= 0) {
            wm.cancelUniqueWork(periodicName(pair.id))
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (pair.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(pair.chargingOnly)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(maxOf(15, pair.intervalMinutes).toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_PAIR to pair.id))
            .build()
        wm.enqueueUniquePeriodicWork(periodicName(pair.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    suspend fun scheduleAll(context: Context) {
        AppDb.get(context).pairs().all().forEach { schedule(context, it) }
    }

    fun cancel(context: Context, pairId: Long) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(periodicName(pairId))
        wm.cancelUniqueWork(nowName(pairId))
    }

    /** Immediate sync, whatever the Wi-Fi / charging settings (the user asked for it). */
    fun syncNow(context: Context, pairId: Long, resync: Boolean = false, force: Boolean = false, wipe: Int = 0) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(SyncWorker.KEY_PAIR to pairId, SyncWorker.KEY_RESYNC to resync, SyncWorker.KEY_FORCE to force, SyncWorker.KEY_WIPE to wipe,
                SyncWorker.KEY_REQUESTED to System.currentTimeMillis()))
            .build()
        val policy = if (resync || force || wipe != 0) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(nowName(pairId), policy, request)
    }
}
