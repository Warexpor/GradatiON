package io.github.stardomains3.oxproxion.code

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CodeSessionDao {
    @Query("SELECT * FROM code_session ORDER BY updatedAt DESC")
    suspend fun getAll(): List<CodeSessionEntity>

    @Query("SELECT * FROM code_session WHERE id = :id")
    suspend fun getById(id: String): CodeSessionEntity?

    @Query("SELECT * FROM code_session WHERE hostId = :hostId ORDER BY updatedAt DESC")
    suspend fun getForHost(hostId: String): List<CodeSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: CodeSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<CodeSessionEntity>)

    @Query("DELETE FROM code_session WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM code_session WHERE hostId = :hostId")
    suspend fun deleteForHost(hostId: String)

    @Query("DELETE FROM code_session")
    suspend fun deleteAll()

    /** Replace the whole index (matches the old prefs list-of-sessions semantics). */
    @Transaction
    suspend fun replaceAll(sessions: List<CodeSessionEntity>) {
        deleteAll()
        if (sessions.isNotEmpty()) upsertAll(sessions)
    }
}
