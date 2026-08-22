package io.github.stardomains3.oxproxion

import androidx.lifecycle.LiveData

class RpRepository(private val rpDao: RpDao) {
    val allCharacters: LiveData<List<RpCharacter>> = rpDao.getAllCharacters()
    val allLorebooks: LiveData<List<RpLorebook>> = rpDao.getAllLorebooks()

    suspend fun getCharacterById(id: Long): RpCharacter? = rpDao.getCharacterById(id)

    suspend fun getCharacterByExportKey(exportKey: String): RpCharacter? =
        if (exportKey.isBlank()) null else rpDao.getCharacterByExportKey(exportKey)

    suspend fun saveCharacter(character: RpCharacter): Long {
        return if (character.id == 0L) {
            rpDao.insertCharacter(character.copy(updatedAt = System.currentTimeMillis()))
        } else {
            rpDao.updateCharacter(character.copy(updatedAt = System.currentTimeMillis()))
            character.id
        }
    }

    suspend fun deleteCharacter(id: Long) = rpDao.deleteCharacter(id)

    suspend fun getAllCharactersOnce(): List<RpCharacter> = rpDao.getAllCharactersOnce()

    suspend fun getLorebookById(id: Long): RpLorebook? = rpDao.getLorebookById(id)

    suspend fun getActiveLorebook(): RpLorebook? = rpDao.getActiveLorebook()

    suspend fun saveLorebook(lorebook: RpLorebook): Long {
        return if (lorebook.id == 0L) {
            rpDao.insertLorebook(lorebook.copy(updatedAt = System.currentTimeMillis()))
        } else {
            rpDao.updateLorebook(lorebook.copy(updatedAt = System.currentTimeMillis()))
            lorebook.id
        }
    }

    suspend fun setActiveLorebook(id: Long) {
        rpDao.deactivateAllLorebooks()
        rpDao.getLorebookById(id)?.let { book ->
            rpDao.updateLorebook(book.copy(isActive = true, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteLorebook(id: Long) {
        // Do not auto-promote another book — that silently changes the RP prompt.
        // User must Set active explicitly (or import path may activate the first book).
        rpDao.deleteLorebook(id)
    }

    suspend fun getAllLorebooksOnce(): List<RpLorebook> = rpDao.getAllLorebooksOnce()
}
