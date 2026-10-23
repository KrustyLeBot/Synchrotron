package app.synchrotron.sync

import app.synchrotron.rclone.Progress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Live state of running syncs, observed by the UI and by the folder watcher. */
object SyncRegistry {
    private val _progress = MutableStateFlow<Map<Long, Progress>>(emptyMap())
    val progress: StateFlow<Map<Long, Progress>> = _progress

    private val finishedAt = HashMap<Long, Long>()

    private val jobs = HashMap<Long, Job>()

    fun start(pairId: Long, job: Job?) {
        if (job != null) synchronized(jobs) { jobs[pairId] = job }
        _progress.update { it + (pairId to Progress()) }
    }

    /** Stops the running sync of a pair (rclone's own job is stopped by the cancellation). */
    fun cancel(pairId: Long) {
        synchronized(jobs) { jobs[pairId] }?.cancel()
    }

    fun update(pairId: Long, p: Progress) = _progress.update { it + (pairId to p) }

    fun finish(pairId: Long) {
        synchronized(finishedAt) { finishedAt[pairId] = System.currentTimeMillis() }
        synchronized(jobs) { jobs.remove(pairId) }
        _progress.update { it - pairId }
    }

    fun isRunning(pairId: Long) = pairId in _progress.value

    /** True while a sync runs, and shortly after: file events caused by the sync itself are ignored. */
    fun isQuiet(pairId: Long, graceMs: Long = 45_000): Boolean {
        if (isRunning(pairId)) return true
        val at = synchronized(finishedAt) { finishedAt[pairId] } ?: return false
        return System.currentTimeMillis() - at < graceMs
    }
}
