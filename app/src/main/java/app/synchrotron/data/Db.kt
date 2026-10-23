package app.synchrotron.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun fromMode(v: SyncMode) = v.name
    @TypeConverter fun toMode(v: String) = SyncMode.valueOf(v)
    @TypeConverter fun fromPolicy(v: ConflictPolicy) = v.name
    @TypeConverter fun toPolicy(v: String) = ConflictPolicy.valueOf(v)
    @TypeConverter fun fromStatus(v: SyncStatus) = v.name
    @TypeConverter fun toStatus(v: String) = SyncStatus.valueOf(v)
}

@Dao
interface PairDao {
    @Query("SELECT * FROM pairs ORDER BY createdAt")
    fun observeAll(): Flow<List<PairEntity>>

    @Query("SELECT * FROM pairs ORDER BY createdAt")
    suspend fun all(): List<PairEntity>

    @Query("SELECT * FROM pairs WHERE id = :id")
    fun observe(id: Long): Flow<PairEntity?>

    @Query("SELECT * FROM pairs WHERE id = :id")
    suspend fun get(id: Long): PairEntity?

    @Query("SELECT * FROM pairs WHERE localPath = :path LIMIT 1")
    suspend fun byLocalPath(path: String): PairEntity?

    @Insert suspend fun insert(pair: PairEntity): Long
    @Update suspend fun update(pair: PairEntity)
    @Query("DELETE FROM pairs WHERE id = :id") suspend fun delete(id: Long)

    @Query("UPDATE pairs SET lastStatus = :status, lastMessage = :message, lastSyncAt = :at, initialized = :initialized WHERE id = :id")
    suspend fun setResult(id: Long, status: SyncStatus, message: String, at: Long, initialized: Boolean)

    @Query("UPDATE pairs SET lastStatus = :status, lastMessage = :message WHERE id = :id")
    suspend fun setStatus(id: Long, status: SyncStatus, message: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE pairId = :pairId ORDER BY startedAt DESC LIMIT 50")
    fun observe(pairId: Long): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY startedAt DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<HistoryEntity>

    @Insert suspend fun insert(entry: HistoryEntity)

    @Query("DELETE FROM history WHERE pairId = :pairId AND id NOT IN (SELECT id FROM history WHERE pairId = :pairId ORDER BY startedAt DESC LIMIT 50)")
    suspend fun trim(pairId: Long)
}

@Database(entities = [PairEntity::class, HistoryEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun pairs(): PairDao
    abstract fun history(): HistoryDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "synchrotron.db")
                .build().also { instance = it }
        }
    }
}
