package app.synchrotron.sync

import app.synchrotron.rclone.Progress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import app.synchrotron.data.SyncStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Live state of running syncs, observed by the UI and by the folder watcher. */
object SyncRegistry {
    private val _progress = MutableStateFlow<Map<Long, Progress>>(emptyMap())
    val progress: StateFlow<Map<Long, Progress>> = _progress


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
        synchronized(jobs) { jobs.remove(pairId) }
        _progress.update { it - pairId }
    }

    fun isRunning(pairId: Long) = pairId in _progress.value

    data class Finished(val pairId: Long, val status: SyncStatus, val changes: Int)

    private val _finished = MutableSharedFlow<Finished>(extraBufferCapacity = 16)

    /** Every finished sync, for callers waiting on one (the bound SyncService, the folder watcher). */
    val finished: SharedFlow<Finished> = _finished

    fun emitFinished(pairId: Long, status: SyncStatus, changes: Int) {
        _finished.tryEmit(Finished(pairId, status, changes))
    }
}
