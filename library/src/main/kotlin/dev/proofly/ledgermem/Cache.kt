package dev.proofly.ledgermem

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Entity(tableName = "memories")
public data class CachedMemory(
    @PrimaryKey val id: String,
    val text: String,
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "workspace_id") val workspaceId: String,
)

@Dao
public interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(memory: CachedMemory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertAll(memories: List<CachedMemory>)

    @Query("SELECT * FROM memories ORDER BY updated_at DESC LIMIT :limit")
    public fun recent(limit: Int): Flow<List<CachedMemory>>

    @Query("DELETE FROM memories WHERE id = :id")
    public suspend fun remove(id: String)

    @Query("DELETE FROM memories")
    public suspend fun clear()
}

internal class TagListConverter {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromList(value: List<String>): String = json.encodeToString(serializer, value)

    @TypeConverter
    fun toList(value: String): List<String> =
        runCatching { json.decodeFromString(serializer, value) }.getOrDefault(emptyList())
}

@Database(entities = [CachedMemory::class], version = 1, exportSchema = false)
@TypeConverters(TagListConverter::class)
public abstract class MemoryDatabase : RoomDatabase() {
    public abstract fun memoryDao(): MemoryDao
}

/** Thin façade so callers don't need to know about Room types. */
public class MemoryCache(private val dao: MemoryDao) {

    public suspend fun upsert(memory: Memory) {
        dao.upsert(memory.toCached())
    }

    public suspend fun upsertAll(memories: List<Memory>) {
        dao.upsertAll(memories.map(Memory::toCached))
    }

    public fun recent(limit: Int = 50): Flow<List<Memory>> =
        dao.recent(limit).map { rows -> rows.map(CachedMemory::toDomain) }

    public suspend fun remove(id: String) {
        dao.remove(id)
    }

    public suspend fun clear() {
        dao.clear()
    }
}

internal fun Memory.toCached(): CachedMemory = CachedMemory(
    id = id,
    text = text,
    tagsJson = Json.encodeToString(ListSerializer(String.serializer()), tags),
    createdAt = createdAt,
    updatedAt = updatedAt,
    workspaceId = workspaceId,
)

internal fun CachedMemory.toDomain(): Memory = Memory(
    id = id,
    text = text,
    tags = runCatching {
        Json.decodeFromString(ListSerializer(String.serializer()), tagsJson)
    }.getOrDefault(emptyList()),
    createdAt = createdAt,
    updatedAt = updatedAt,
    workspaceId = workspaceId,
)
