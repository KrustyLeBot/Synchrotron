package app.synchrotron.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.synchrotron.MainActivity
import app.synchrotron.R
import app.synchrotron.data.PairEntity
import app.synchrotron.data.SyncStatus
import app.synchrotron.rclone.Progress

object Notifier {
    const val CH_PROBLEMS = "problems"
    const val CH_PROGRESS = "progress"
    const val CH_WATCH = "watch"
    /** Things only the user can fix (conflicts, a sync waiting for confirmation, an error): heads-up, with a sound. */
    const val CH_ATTENTION = "attention"
    const val ID_SESSION = 1
    const val ID_WATCH = 2
    const val ID_UPDATE = 3
    const val ID_TEST = 4
    private const val ID_PAIR_BASE = 1000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROBLEMS, "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ATTENTION, "Action requise (conflits, synchro bloquée)", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Synchronisation en cours", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Surveillance des dossiers", NotificationManager.IMPORTANCE_MIN))
    }

    private fun open(context: Context, pairId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (pairId != null) putExtra(MainActivity.EXTRA_PAIR_ID, pairId)
        }
        return PendingIntent.getActivity(
            context, (pairId ?: -1).toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(context).notify(id, n)
    }

    // ---- one alert per problem, again after REMIND_MS if it is still there ----

    private const val REMIND_MS = 6 * 60 * 60 * 1000L
    private fun state(context: Context) = context.getSharedPreferences("notify_state", Context.MODE_PRIVATE)

    /** True when this problem was not announced yet (or not for REMIND_MS): a sync retried every few minutes must not ring each time. */
    private fun alertDue(context: Context, key: String, fingerprint: String): Boolean {
        val prefs = state(context)
        val now = System.currentTimeMillis()
        val last = prefs.getString(key, null)
        if (last != null) {
            val at = last.substringAfterLast('|').toLongOrNull() ?: 0L
            if (last.substringBeforeLast('|') == fingerprint && now - at < REMIND_MS) return false
        }
        prefs.edit().putString(key, "$fingerprint|$now").apply()
        return true
    }

    private fun forget(context: Context, key: String) = state(context).edit().remove(key).apply()

    private fun attention(context: Context, title: String, message: String, pairId: Long?) =
        NotificationCompat.Builder(context, CH_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_sync).setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(open(context, pairId)).setAutoCancel(true)

    fun problem(context: Context, pair: PairEntity, status: SyncStatus, message: String) {
        if (!alertDue(context, "pair-${pair.id}", "$status|$message")) return
        val title = if (status == SyncStatus.BLOCKED) "${pair.name} : synchro en attente de ta confirmation" else "${pair.name} : erreur de synchro"
        val text = if (status == SyncStatus.BLOCKED) "$message\nOuvre Synchrotron pour confirmer ou annuler." else message
        notify(context, ID_PAIR_BASE + pair.id.toInt(), attention(context, title, text, pair.id).build())
    }

    fun conflicts(context: Context, pair: PairEntity, message: String) {
        if (!alertDue(context, "pair-${pair.id}", "CONFLICTS|$message")) return
        notify(context, ID_PAIR_BASE + pair.id.toInt(),
            attention(context, "${pair.name} : conflits à résoudre", "$message\nOuvre Synchrotron pour choisir la bonne version.", pair.id).build())
    }

    fun clear(context: Context, pair: PairEntity) {
        forget(context, "pair-${pair.id}")
        NotificationManagerCompat.from(context).cancel(ID_PAIR_BASE + pair.id.toInt())
    }

    fun sessionExpired(context: Context) {
        if (!alertDue(context, "session", "session")) return
        notify(context, ID_SESSION, attention(context, "Reconnecte-toi à Proton", "La session a expiré, les synchronisations sont en pause.", null).build())
    }

    /** The Synchrotron server refuses the settings (or they are missing): syncs are on hold until fixed. */
    fun serverProblem(context: Context, message: String) {
        if (!alertDue(context, "session", message)) return
        notify(context, ID_SESSION, attention(context, "Vérifie les réglages du serveur", message, null).build())
    }

    /** Can the user be warned at all? A banner and the settings test use this. */
    enum class Reach { OK, NO_PERMISSION, BLOCKED }

    fun reach(context: Context): Reach {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Reach.NO_PERMISSION
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return Reach.BLOCKED
        val channel = nm.getNotificationChannel(CH_ATTENTION)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return Reach.BLOCKED
        return Reach.OK
    }

    /** What an alert looks like, to check that it reaches the user. */
    fun test(context: Context) {
        notify(context, ID_TEST, attention(context, "Notification de test", "Si tu vois ceci, tu seras prévenu des conflits et des synchros bloquées.", null).build())
    }

    fun updateAvailable(context: Context, build: Int) {
        notify(context, ID_UPDATE, NotificationCompat.Builder(context, CH_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat_sync).setContentTitle("Mise à jour disponible")
            .setContentText("Synchrotron build $build")
            .setContentIntent(open(context, null)).setAutoCancel(true).build())
    }

    fun progress(context: Context, pairName: String, p: Progress): android.app.Notification {
        val text = when {
            p.file.isNotEmpty() -> p.file
            p.transfers > 0 -> "${p.transfers} fichiers"
            else -> "Analyse des changements…"
        }
        val b = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_sync).setContentTitle("Synchronisation de $pairName")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(context, null))
        if (p.totalBytes > 0) {
            b.setProgress(1000, (p.bytes * 1000 / p.totalBytes).toInt().coerceIn(0, 1000), false)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }
}
