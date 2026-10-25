package app.synchrotron.integration

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import app.synchrotron.sync.SyncExecutor
import app.synchrotron.sync.SyncRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fast path of "sync when the app opens": the app binds this service, sends [MSG_SYNC] with `local_path`
 * and gets [MSG_ANSWER] (arg1 = `SyncGate.RESULT_*`, data `pair_id`) then [MSG_FINISHED]
 * (data `status`, `changes`).
 *
 * While an app in the foreground is bound, Android keeps Synchrotron's process at the same priority,
 * so the sync starts immediately in this process instead of waiting for WorkManager.
 * Same rules as SYNC_NOW: only existing pairs, no path or setting can be passed.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == MSG_SYNC) handleSync(msg.replyTo, msg.data?.getString("local_path"), msg.data?.getLong("pair_id", -1L) ?: -1L)
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handleSync(reply: Messenger?, localPath: String?, pairId: Long) {
        if (reply == null) return
        scope.launch {
            val decision = SyncGate.decide(applicationContext, pairId, localPath)
            val pair = decision.pair
            val follow = decision.code == SyncGate.RESULT_STARTED || decision.code == SyncGate.RESULT_RUNNING
            // Subscribe before starting the sync, so even an instant one cannot be missed.
            val done = if (follow) async(start = CoroutineStart.UNDISPATCHED) {
                SyncRegistry.finished.first { it.pairId == pair!!.id }
            } else null
            send(reply, MSG_ANSWER, decision.code, Bundle().apply { if (follow) putLong("pair_id", pair!!.id) })
            if (decision.code == SyncGate.RESULT_STARTED) SyncExecutor.launch(applicationContext, pair!!.id)
            val result = done?.await() ?: return@launch
            send(reply, MSG_FINISHED, 0, Bundle().apply {
                putLong("pair_id", result.pairId)
                putString("status", result.status.name)
                putInt("changes", result.changes)
            })
        }
    }

    private fun send(to: Messenger, what: Int, arg1: Int, data: Bundle) {
        try {
            to.send(Message.obtain(null, what, arg1, 0).apply { this.data = data })
        } catch (e: RemoteException) {
            // The app went away; the sync itself carries on.
        }
    }

    override fun onDestroy() {
        scope.cancel()
    }

    companion object {
        const val ACTION = "app.synchrotron.action.BIND_SYNC"
        const val MSG_SYNC = 1
        const val MSG_ANSWER = 2
        const val MSG_FINISHED = 3
    }
}
