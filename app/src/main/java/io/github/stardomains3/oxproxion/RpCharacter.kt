package io.github.stardomains3.oxproxion

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "rp_characters")
data class RpCharacter(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Stable id for backup/import remapping across devices (not the Room row id). */
    val exportKey: String = UUID.randomUUID().toString(),
    val name: String,
    val personality: String = "",
    val style: String = "",
    val greeting: String = "",
    val scenario: String = "",
    val examplesJson: String = "[]",
    val photoUri: String? = null,
    val prompt: String = "",
    val instruction: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
