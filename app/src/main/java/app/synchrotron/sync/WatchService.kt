package app.synchrotron.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.synchrotron.R
import app.synchrotron.data.AppDb
import app.synchrotron.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Optional "sync on change": watches the folders of pairs that asked for it and triggers a sync
 * 30 s after the last change. FileObserver is not recursive, so one observer per sub-folder.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val observers = HashMap<String, FileObserver>()
    private val timers = HashMap<Long, Runnable>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, Notifier.CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat_sync).setContentTitle("Synchrotron surveille tes dossiers")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_WATCH, n)
        }
        scope.launch {
            val pairs = AppDb.get(this@WatchService).pairs().all().filter { it.watchChanges && it.initialized }
            handler.post {
                stopWatching()
                if (pairs.isEmpty()) {
                    stopSelf()
                } else {
                    pairs.forEach { watchTree(it.id, File(it.localPath)) }
                }
            }
        }
        return START_STICKY
    }

    private fun watchTree(pairId: Long, root: File) {
        var count = 0
        root.walkTopDown().onEnter { count++ < MAX_DIRS && !it.name.startsWith(".") || it == root }
            .filter { it.isDirectory }.forEach { observe(pairId, it) }
    }

    @Suppress("DEPRECATION")
    private fun observe(pairId: Long, dir: File) {
        val mask = FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO
        val observer = object : FileObserver(dir.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || SyncRegistry.isQuiet(pairId)) return
                if (event and CREATE != 0 && File(dir, path).isDirectory) handler.post { observe(pairId, File(dir, path)) }
                handler.post { debounce(pairId) }
            }
        }
        observers.put(dir.absolutePath, observer)?.stopWatching()
        observer.startWatching()
    }

    private fun debounce(pairId: Long) {
        timers[pairId]?.let(handler::removeCallbacks)
        val task = Runnable { Scheduler.syncNow(applicationContext, pairId) }
        timers[pairId] = task
        handler.postDelayed(task, DEBOUNCE_MS)
    }

    private fun stopWatching() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
    }

    override fun onDestroy() {
        stopWatching()
        timers.values.forEach(handler::removeCallbacks)
        scope.cancel()
    }

    companion object {
        private const val DEBOUNCE_MS = 30_000L
        private const val MAX_DIRS = 800

        /** Starts the service when some pair wants it, stops it otherwise. Safe to call from anywhere. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                val wanted = AppDb.get(app).pairs().all().any { it.watchChanges && it.initialized }
                val intent = Intent(app, WatchService::class.java)
                runCatching {
                    if (wanted) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
                }
            }
        }
    }
}
