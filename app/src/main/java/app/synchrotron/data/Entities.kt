package app.synchrotron.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncMode(val label: String, val hint: String) {
    BIDIRECTIONAL("Bidirectionnel", "Les changements passent dans les deux sens"),
    UPLOAD_ONLY("Envoi seul", "Sauvegarde le téléphone vers le cloud, ne supprime rien"),
    DOWNLOAD_ONLY("Réception seule", "Récupère depuis le cloud, ne supprime rien"),
}

enum class ConflictPolicy(val label: String, val hint: String, val rclone: String) {
    KEEP_BOTH("Garder les deux", "Les deux versions sont conservées (.conflict1 / .conflict2)", "none"),
    NEWER("Le plus récent gagne", "L'autre version est conservée en .conflict", "newer"),
    LOCAL("Le téléphone gagne", "La version du cloud est conservée en .conflict", "path1"),
    REMOTE("Le cloud gagne", "La version du téléphone est conservée en .conflict", "path2"),
}

enum class SyncStatus(val label: String) {
    NEVER("Jamais synchronisé"),
    RUNNING("En cours…"),
    OK("À jour"),
    CONFLICTS("Conflits"),
    BLOCKED("Confirmation requise"),
    ERROR("Erreur"),
}

@Entity(tableName = "pairs")
data class PairEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val localPath: String,
    /** Path inside the Proton Drive or on the Synchrotron server (same tree on both), without leading slash. */
    val remotePath: String,
    val mode: SyncMode = SyncMode.BIDIRECTIONAL,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    /** One rclone-style pattern per line. */
    val exclusions: String = "",
    /** 0 = manual only. WorkManager's minimum is 15. */
    val intervalMinutes: Int = 60,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val watchChanges: Boolean = false,
    /** Abort a sync that would delete more than this share of the files. */
    val maxDeletePercent: Int = 50,
    /** Bisync baseline exists (the first resync was confirmed and done). */
    val initialized: Boolean = false,
    val requestedByPackage: String? = null,
    val lastSyncAt: Long = 0,
    val lastStatus: SyncStatus = SyncStatus.NEVER,
    val lastMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val pairId: Long,
    val startedAt: Long,
    val durationMs: Long,
    val status: SyncStatus,
    val changes: Int,
    val message: String,
    val log: String = "",
)
