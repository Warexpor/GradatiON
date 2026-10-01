package io.github.stardomains3.oxproxion

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import java.util.UUID

@Dao
interface RpDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacter(character: RpCharacter): Long

    @Update
    suspend fun updateCharacter(character: RpCharacter)

    @Query("SELECT * FROM rp_characters ORDER BY updatedAt DESC")
    fun getAllCharacters(): LiveData<List<RpCharacter>>

    @Query("SELECT * FROM rp_characters ORDER BY updatedAt DESC")
    suspend fun getAllCharactersOnce(): List<RpCharacter>

    @Query("SELECT * FROM rp_characters WHERE id = :id")
    suspend fun getCharacterById(id: Long): RpCharacter?

    @Query("SELECT * FROM rp_characters WHERE exportKey = :exportKey LIMIT 1")
    suspend fun getCharacterByExportKey(exportKey: String): RpCharacter?

    @Query("DELETE FROM rp_characters WHERE id = :id")
    suspend fun deleteCharacter(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLorebook(lorebook: RpLorebook): Long

    @Update
    suspend fun updateLorebook(lorebook: RpLorebook)

    @Query("SELECT * FROM rp_lorebooks ORDER BY updatedAt DESC")
    fun getAllLorebooks(): LiveData<List<RpLorebook>>

    @Query("SELECT * FROM rp_lorebooks ORDER BY updatedAt DESC")
    suspend fun getAllLorebooksOnce(): List<RpLorebook>

    @Query("SELECT * FROM rp_lorebooks WHERE id = :id")
    suspend fun getLorebookById(id: Long): RpLorebook?

    @Query("SELECT * FROM rp_lorebooks WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveLorebook(): RpLorebook?

    @Query("UPDATE rp_lorebooks SET isActive = 0")
    suspend fun deactivateAllLorebooks()

    @Query("DELETE FROM rp_lorebooks WHERE id = :id")
    suspend fun deleteLorebook(id: Long)

    /**
     * Every character in one transaction. A failure part-way rolls the whole batch back.
     * Matching is by export key, including a key inserted earlier in this same batch.
     */
    @Transaction
    suspend fun importCharacters(incoming: List<RpCharacterExport>): List<ImportedCharacter> {
        return incoming.mapIndexed { index, ex ->
            RpImportGuard.beforeRow(index)
            val existing = ex.exportKey.takeIf { it.isNotBlank() }?.let { getCharacterByExportKey(it) }
            val isNew = existing == null
            val exportKey = existing?.exportKey ?: ex.exportKey.ifBlank { UUID.randomUUID().toString() }
            val row = (existing ?: RpCharacter(name = ex.name, exportKey = exportKey)).copy(
                name = ex.name,
                personality = ex.personality,
                style = ex.style,
                greeting = ex.greeting,
                scenario = ex.scenario,
                examplesJson = ex.examplesJson,
                prompt = ex.prompt,
                instruction = ex.instruction,
                photoUri = existing?.photoUri,
                exportKey = exportKey,
                updatedAt = System.currentTimeMillis()
            )
            val id = if (row.id == 0L) {
                insertCharacter(row)
            } else {
                updateCharacter(row)
                row.id
            }
            ImportedCharacter(id, exportKey, isNew, ex.avatarBase64)
        }
    }

    /**
     * Every lorebook in one transaction. A second entry with the same name updates the one just
     * inserted. The last entry marked active wins; an empty library with none marked active
     * activates the first when [activateFirstIfNone] is set.
     */
    @Transaction
    suspend fun importLorebooks(incoming: List<RpLorebookExport>, activateFirstIfNone: Boolean): Int {
        var firstId: Long? = null
        incoming.forEachIndexed { index, ex ->
            RpImportGuard.beforeRow(index)
            val existing = getAllLorebooksOnce()
                .firstOrNull { it.name.equals(ex.name, ignoreCase = true) }
            val row = (existing ?: RpLorebook(name = ex.name)).copy(
                name = ex.name,
                content = ex.content,
                updatedAt = System.currentTimeMillis()
            )
            val id = if (row.id == 0L) {
                insertLorebook(row)
            } else {
                updateLorebook(row)
                row.id
            }
            if (firstId == null) firstId = id
            if (ex.isActive) {
                deactivateAllLorebooks()
                getLorebookById(id)?.let {
                    updateLorebook(it.copy(isActive = true, updatedAt = System.currentTimeMillis()))
                }
            }
        }
        if (activateFirstIfNone && getActiveLorebook() == null) {
            firstId?.let { id ->
                getLorebookById(id)?.let {
                    updateLorebook(it.copy(isActive = true, updatedAt = System.currentTimeMillis()))
                }
            }
        }
        return incoming.size
    }
}

/** One row from [RpDao.importCharacters], enough to write its portrait after the transaction commits. */
data class ImportedCharacter(
    val id: Long,
    val exportKey: String,
    val isNew: Boolean,
    val avatarBase64: String?
)

/**
 * Test hook. When set, the next import throws at this row index, inside the transaction, so the
 * batch rolls back. Cleared when it fires. Null in production.
 */
internal object RpImportGuard {
    var failAt: Int? = null

    fun beforeRow(index: Int) {
        if (failAt == index) {
            failAt = null
            throw IllegalStateException("simulated import failure")
        }
    }
}
