package com.bhasasetu.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: TranslationHistoryEntity)

    @Query("SELECT * FROM translation_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<TranslationHistoryEntity>>

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteHistory(id: Long)

    @Query("DELETE FROM translation_history")
    suspend fun deleteAllHistory()

    @Query("SELECT * FROM translation_history WHERE sourceText = :sourceText AND targetLanguage = :targetLanguage ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastHistoryEntry(sourceText: String, targetLanguage: String): TranslationHistoryEntity?
}
