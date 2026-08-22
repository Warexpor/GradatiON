package io.github.stardomains3.oxproxion

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rp_lorebooks")
data class RpLorebook(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val content: String = "",
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
