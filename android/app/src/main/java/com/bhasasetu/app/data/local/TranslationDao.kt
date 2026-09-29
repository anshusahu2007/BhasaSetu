package com.bhasasetu.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(translations: List<TranslationEntity>)

    @Query("""
        SELECT * FROM translations 
        WHERE language = :targetLanguage
    """)
    suspend fun getTranslationsForLanguage(targetLanguage: String): List<TranslationEntity>

    @Query("""
        SELECT * FROM translations 
        WHERE TRIM(hindiText) = TRIM(:hindi) COLLATE NOCASE 
        AND language = :targetLanguage 
        LIMIT 1
    """)
    suspend fun findTranslation(hindi: String, targetLanguage: String): TranslationEntity?

    @Query("""
        SELECT * FROM translations 
        WHERE TRIM(translatedText) = TRIM(:text) COLLATE NOCASE 
        AND language = :sourceLanguage 
        LIMIT 1
    """)
    suspend fun findReverseTranslation(text: String, sourceLanguage: String): TranslationEntity?

    @Query("SELECT COUNT(*) FROM translations")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM translations WHERE language = :language")
    suspend fun getCountForLanguage(language: String): Int
}
