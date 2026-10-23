package app.synchrotron.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.synchrotron.data.AppDb
import app.synchrotron.notify.Notifier
import app.synchrotron.rclone.Progress

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val pairId = inputData.getLong(KEY_PAIR, -1)

    override suspend fun getForegroundInfo(): ForegroundInfo = info(Progress())

    private suspend fun info(p: Progress): ForegroundInfo {
        val name = AppDb.get(applicationContext).pairs().get(pairId)?.name ?: "Synchrotron"
        val n = Notifier.progress(applicationContext, name, p)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_BASE + pairId.toInt(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_BASE + pairId.toInt(), n)
        }
    }

    override suspend fun doWork(): Result {
        if (pairId < 0) return Result.failure()
        // The foreground notification only matters for long syncs; never fail the work because of it.
        runCatching { setForeground(info(Progress())) }
        var lastUpdate = 0L
        SyncRunner(applicationContext).run(
            pairId,
            SyncOptions(
                resync = inputData.getBoolean(KEY_RESYNC, false),
                force = inputData.getBoolean(KEY_FORCE, false),
                wipe = inputData.getInt(KEY_WIPE, 0),
            ),
        ) { p ->
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 1500) {
                lastUpdate = now
                runCatching { setForeground(info(p)) }
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_PAIR = "pair"
        const val KEY_RESYNC = "resync"
        const val KEY_FORCE = "force"
        const val KEY_WIPE = "wipe"
        private const val NOTIF_BASE = 5000
    }
}
