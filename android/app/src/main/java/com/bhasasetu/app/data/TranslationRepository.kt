package com.bhasasetu.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.bhasasetu.app.data.local.TranslationDao
import com.bhasasetu.app.data.local.TranslationEntity
import com.bhasasetu.app.data.local.TranslationHistoryDao
import com.bhasasetu.app.data.local.TranslationHistoryEntity
import com.bhasasetu.app.data.remote.TranslationApi
import com.bhasasetu.app.data.remote.TranslationRequest
import com.bhasasetu.app.data.ml.OfflineTranslationEngine
import kotlinx.coroutines.flow.Flow

class TranslationRepository(
    private val context: Context,
    private val translationDao: TranslationDao,
    private val translationHistoryDao: TranslationHistoryDao,
    private val translationApi: TranslationApi
) {

    private val offlineEngine = OfflineTranslationEngine(context)

    fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val actType = connectivityManager.getNetworkCapabilities(network) ?: return false
        val available = actType.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!available) {
            Log.d("OFFLINE", "Network available=false")
        }
        return available
    }

    suspend fun findTranslation(
        text: String, 
        sourceLanguage: String, 
        targetLanguage: String,
        sourceType: String = "text"
    ): TranslationEntity? {
        val trimmedText = text.trim()
        Log.d("TRANSLATION", "source=$sourceLanguage target=$targetLanguage input='$trimmedText'")
        
        // 1. Search Room first (ALWAYS)
        val localResult = try {
            if (sourceLanguage == "Hindi") {
                translationDao.findTranslation(trimmedText, targetLanguage)
            } else if (targetLanguage == "Hindi") {
                translationDao.findReverseTranslation(trimmedText, sourceLanguage)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("ROOM", "Room error", e)
            null
        }

        if (localResult != null) {
            Log.d("ROOM", "HIT")
            val resultEntity = if (sourceLanguage == "Hindi") {
                localResult
            } else {
                TranslationEntity(
                    hindiText = localResult.hindiText,
                    language = sourceLanguage,
                    translatedText = localResult.hindiText,
                    phoneticText = ""
                )
            }
            saveToHistory(trimmedText, resultEntity.translatedText, sourceLanguage, targetLanguage, resultEntity.phoneticText, sourceType)
            return resultEntity
        }

        Log.d("ROOM", "MISS")

        // 2. Try Offline ML Model (If available)
        if (offlineEngine.isModelAvailable()) {
            Log.d("OFFLINE-ML", "Attempting offline ML translation for $sourceLanguage -> $targetLanguage")
            val offlineTranslation = offlineEngine.translate(trimmedText, sourceLanguage, targetLanguage)
            if (!offlineTranslation.isNullOrBlank()) {
                val resultEntity = if (sourceLanguage == "Hindi") {
                    TranslationEntity(
                        hindiText = trimmedText,
                        language = targetLanguage,
                        translatedText = offlineTranslation,
                        phoneticText = ""
                    )
                } else {
                    TranslationEntity(
                        hindiText = offlineTranslation,
                        language = sourceLanguage,
                        translatedText = offlineTranslation,
                        phoneticText = ""
                    )
                }
                // Cache result in Room database
                if (sourceLanguage == "Hindi") {
                    insertTranslations(listOf(resultEntity))
                }
                saveToHistory(trimmedText, resultEntity.translatedText, sourceLanguage, targetLanguage, null, sourceType)
                return resultEntity
            }
        }

        // 3. If Room misses and Offline ML misses/unavailable, check internet
        if (!isNetworkAvailable()) {
            Log.d("OFFLINE", "Network unavailable and Offline ML failed. Returning null.")
            return null
        }

        // 4. Call FastAPI (Online Fallback)
        Log.d("API", "Calling FastAPI: $sourceLanguage -> $targetLanguage")
        try {
            val response = translationApi.translate(
                TranslationRequest(
                    text = trimmedText,
                    source_language = sourceLanguage,
                    target_language = targetLanguage
                )
            )
            
            if (response.found) {
                val resultEntity = if (sourceLanguage == "Hindi") {
                    TranslationEntity(
                        hindiText = trimmedText,
                        language = targetLanguage,
                        translatedText = response.translation,
                        phoneticText = response.phonetic
                    )
                } else {
                    TranslationEntity(
                        hindiText = response.translation,
                        language = sourceLanguage,
                        translatedText = response.translation,
                        phoneticText = ""
                    )
                }
                
                if (sourceLanguage == "Hindi") {
                    insertTranslations(listOf(resultEntity))
                }
                
                saveToHistory(trimmedText, resultEntity.translatedText, sourceLanguage, targetLanguage, resultEntity.phoneticText, sourceType)
                return resultEntity
            } else {
                return null
            }
        } catch (e: Exception) {
            Log.e("API", "FastAPI Error: ${e.message}")
            throw e
        }
    }

    private suspend fun saveToHistory(
        sourceText: String,
        translatedText: String,
        sourceLanguage: String,
        targetLanguage: String,
        phonetic: String?,
        sourceType: String
    ) {
        try {
            val lastEntry = translationHistoryDao.getLastHistoryEntry(sourceText, targetLanguage)
            val now = System.currentTimeMillis()
            
            if (lastEntry != null && lastEntry.translatedText == translatedText && (now - lastEntry.timestamp) < 5000) {
                return
            }

            val historyEntry = TranslationHistoryEntity(
                sourceText = sourceText,
                translatedText = translatedText,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                phonetic = phonetic,
                sourceType = sourceType,
                timestamp = now
            )
            translationHistoryDao.insertHistory(historyEntry)
        } catch (e: Exception) {
            Log.e("TranslationRepo", "Error saving to history", e)
        }
    }

    fun getAllHistory(): Flow<List<TranslationHistoryEntity>> = translationHistoryDao.getAllHistory()

    suspend fun deleteHistoryItem(id: Long) = translationHistoryDao.deleteHistory(id)

    suspend fun clearAllHistory() = translationHistoryDao.deleteAllHistory()

    suspend fun insertTranslations(translations: List<TranslationEntity>) {
        try {
            translationDao.insertAll(translations)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun initializeDatabase(loader: TranslationLoader) {
        val languages = listOf("Santali", "Ho", "Mundari")
        val files = listOf("santali.json", "ho.json", "mundari.json")
        
        val allTranslations = mutableListOf<TranslationEntity>()
        
        languages.zip(files).forEach { (lang, file) ->
            val count = translationDao.getCountForLanguage(lang)
            if (count == 0) {
                val list = loader.loadFromAssets(file, lang)
                if (list.isNotEmpty()) {
                    allTranslations.addAll(list)
                }
            }
        }
        
        if (allTranslations.isNotEmpty()) {
            insertTranslations(allTranslations)
        }
    }
}
