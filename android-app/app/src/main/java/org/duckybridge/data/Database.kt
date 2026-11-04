package org.duckybridge.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "devices")
data class Device(@PrimaryKey val id: String, val name: String, val host: String, val port: Int, val fingerprint: String, val lastSeen: Long = 0, val os: String = "Inconnu")
@Entity(tableName = "history")
data class History(@PrimaryKey val id: String, val deviceId: String, val command: String, val result: String, val timestamp: Long = System.currentTimeMillis())
@Entity(tableName = "scripts")
data class SavedScript(@PrimaryKey val id: String, val name: String, val json: String, val validated: Boolean = false)
@Dao
interface BridgeDao {
    @Query("SELECT * FROM devices ORDER BY name") fun devices(): Flow<List<Device>>
    @Upsert suspend fun save(device: Device)
    @Query("DELETE FROM devices WHERE id = :id") suspend fun deleteDevice(id: String)
    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT 100") fun history(): Flow<List<History>>
    @Insert suspend fun add(history: History)
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY timestamp DESC LIMIT 100)") suspend fun trimHistory()
    @Query("SELECT * FROM scripts ORDER BY name") fun scripts(): Flow<List<SavedScript>>
    @Upsert suspend fun saveScript(script: SavedScript)
}
@Database(entities = [Device::class, History::class, SavedScript::class], version = 1, exportSchema = true)
abstract class BridgeDatabase : RoomDatabase() { abstract fun dao(): BridgeDao }
