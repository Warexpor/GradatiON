package io.github.stardomains3.oxproxion

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

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
}
