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
import com.bhasasetu.app.data.remote.TranslationResponse
import com.bhasasetu.app.data.remote.RetrofitClient
import com.bhasasetu.app.data.ml.OfflineTranslationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.text.Normalizer

data class TranslationResult(
    val entity: TranslationEntity,
    val sourceMethod: String // "database", "onnx", or "server"
)

class TranslationRepository(
    private val context: Context,
    private val translationDao: TranslationDao,
    private val translationHistoryDao: TranslationHistoryDao,
    private val translationApi: TranslationApi
) {

    private val offlineEngine = OfflineTranslationEngine(context)
    private val responseJson = Json { ignoreUnknownKeys = true }

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

    private fun normalizeText(input: String): String {
        if (input.isBlank()) return ""

        val normalized = Normalizer.normalize(input, Normalizer.Form.NFC)
        val result = StringBuilder(normalized.length)
        var pendingSpace = false
        var index = 0

        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            val category = Character.getType(codePoint)
            when {
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> {
                    pendingSpace = result.isNotEmpty()
                }
                category == Character.CONNECTOR_PUNCTUATION.toInt() ||
                    category == Character.DASH_PUNCTUATION.toInt() ||
                    category == Character.START_PUNCTUATION.toInt() ||
                    category == Character.END_PUNCTUATION.toInt() ||
                    category == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                    category == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                    category == Character.OTHER_PUNCTUATION.toInt() -> pendingSpace = result.isNotEmpty()
                else -> {
                    if (pendingSpace) result.append(' ')
                    result.appendCodePoint(codePoint)
                    pendingSpace = false
                }
            }
            index += Character.charCount(codePoint)
        }

        return result.toString()
    }

    suspend fun findTranslationResult(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        sourceType: String = "text"
    ): TranslationResult? {
        val rawInput = text.trim()
        val normalizedInput = normalizeText(rawInput)
        val isHoTranslation = sourceLanguage == "Ho" || targetLanguage == "Ho"
        val sourceLanguageCode = offlineEngine.languageTag(sourceLanguage)
        val targetLanguageCode = offlineEngine.languageTag(targetLanguage)

        if (isHoTranslation) {
            Log.d("HO-DEBUG", "sourceLanguage=$sourceLanguage targetLanguage=$targetLanguage input=$rawInput")
        }

        Log.d("TRANSLATION", "[TRACE-3] Repository source=$sourceLanguage")
        Log.d("TRANSLATION", "[TRACE-3] Repository target=$targetLanguage")
        Log.d("TRANSLATION", "[TRACE-3] Repository input=$rawInput")
        Log.d("TRANSLATION", "[TRACE-3] sourceLanguageCode=$sourceLanguageCode")
        Log.d("TRANSLATION", "[TRACE-3] targetLanguageCode=$targetLanguageCode")

        if (normalizedInput.isBlank()) return null

        val dictionaryLanguage = if (sourceLanguage == "Hindi") targetLanguage else sourceLanguage
        val roomQuery = if (sourceLanguage == "Hindi") {
            "SELECT * FROM translations WHERE TRIM(hindiText) = TRIM(:hindi) COLLATE NOCASE AND language = :targetLanguage LIMIT 1"
        } else if (targetLanguage == "Hindi") {
            "SELECT * FROM translations WHERE TRIM(translatedText) = TRIM(:text) COLLATE NOCASE AND language = :sourceLanguage LIMIT 1"
        } else {
            "No directional dictionary query for $sourceLanguage -> $targetLanguage"
        }

        // Auto-initialize database if the target/source dictionary is empty
        val currentDictCount = try { translationDao.getCountForLanguage(dictionaryLanguage) } catch (_: Exception) { 0 }
        if (currentDictCount == 0) {
            Log.w("ROOM", "Database for $dictionaryLanguage is empty (count=0). Initializing database from assets now...")
            initializeDatabase(TranslationLoader(context))
        }

        if (isHoTranslation) {
            val hoRows = try { translationDao.getCountForLanguage("Ho") } catch (_: Exception) { 0 }
            Log.d("HO-DEBUG", "sourceLanguage=$sourceLanguage")
            Log.d("HO-DEBUG", "targetLanguage=$targetLanguage")
            Log.d("HO-DEBUG", "input=$rawInput")
            Log.d("HO-DEBUG", "normalizedInput=$normalizedInput")
            Log.d("HO-DEBUG", "roomQuery=$roomQuery")
            Log.d("HO-DEBUG", "roomRows=$hoRows")
        }

        Log.d("TRANSLATION", "[TRACE-4] ROOM query=$roomQuery")
        Log.d("TRANSLATION", "[TRACE-4] ROOM parameters input='$rawInput' normalized='$normalizedInput' dictionaryLanguage=$dictionaryLanguage")

        val localResult = try {
            Log.d("ROOM", "total translation rows=${translationDao.getCount()}")
            Log.d("ROOM", "Santali rows=${translationDao.getCountForLanguage("Santali")}")
            Log.d("ROOM", "Mundari rows=${translationDao.getCountForLanguage("Mundari")}")
            Log.d("ROOM", "Ho rows=${translationDao.getCountForLanguage("Ho")}")
            val dictionaryCount = translationDao.getCountForLanguage(dictionaryLanguage)
            Log.d("ROOM", "database count for $dictionaryLanguage=$dictionaryCount")
            if (sourceLanguage == "Hindi") {
                val exact = translationDao.findTranslation(rawInput, targetLanguage)
                    ?: translationDao.findTranslation(normalizedInput, targetLanguage)
                if (exact != null) {
                    exact
                } else {
                    val list = translationDao.getTranslationsForLanguage(targetLanguage)
                    list.firstOrNull {
                        normalizeText(it.hindiText).equals(normalizedInput, ignoreCase = true) ||
                        it.hindiText.trim().equals(rawInput, ignoreCase = true) ||
                        it.hindiText.trim().equals(normalizedInput, ignoreCase = true)
                    }
                }
            } else if (targetLanguage == "Hindi") {
                val exact = translationDao.findReverseTranslation(rawInput, sourceLanguage)
                    ?: translationDao.findReverseTranslation(normalizedInput, sourceLanguage)
                if (exact != null) {
                    exact
                } else {
                    val list = translationDao.getTranslationsForLanguage(sourceLanguage)
                    list.firstOrNull {
                        normalizeText(it.translatedText).equals(normalizedInput, ignoreCase = true) ||
                        it.translatedText.trim().equals(rawInput, ignoreCase = true) ||
                        it.translatedText.trim().equals(normalizedInput, ignoreCase = true) ||
                        normalizeText(it.phoneticText).equals(normalizedInput, ignoreCase = true) ||
                        it.phoneticText.trim().equals(rawInput, ignoreCase = true)
                    }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("TRANSLATION", "FULL EXCEPTION", e)
            throw e
        }

        Log.d("TRANSLATION", "[TRACE-4] ROOM result=${if (localResult == null) "MISS" else "HIT"}")
        if (isHoTranslation) {
            val roomResult = localResult?.let {
                "HIT id=${it.id} sourceLanguage=${if (sourceLanguage == "Hindi") "Hindi" else it.language} targetLanguage=${if (sourceLanguage == "Hindi") it.language else "Hindi"} sourceText=${if (sourceLanguage == "Hindi") it.hindiText else it.translatedText} targetText=${if (sourceLanguage == "Hindi") it.translatedText else it.hindiText} rowLanguage=${it.language}"
            } ?: "MISS"
            Log.d("HO-DEBUG", "roomResult=$roomResult")
        }
        if (localResult != null) {
            Log.d("TRANSLATION", "[TRACE-5] OFFLINE ENGINE called=false; reason=ROOM HIT")
            Log.d("TRANSLATION", "[TRACE-6] ONLINE request=skipped; reason=ROOM HIT")
            if (sourceLanguage == "Ho" || targetLanguage == "Ho") {
                Log.d("HO", "dictionary HIT")
            }
            val resultEntity = if (sourceLanguage == "Hindi") {
                localResult
            } else {
                TranslationEntity(
                    id = localResult.id,
                    hindiText = localResult.hindiText,
                    language = sourceLanguage,
                    translatedText = localResult.hindiText,
                    phoneticText = localResult.phoneticText
                )
            }
            saveToHistory(rawInput, resultEntity.translatedText, sourceLanguage, targetLanguage, resultEntity.phoneticText, sourceType)
            Log.d("TRANSLATION", "method=room\nresult=${resultEntity.translatedText}")
            Log.d("TRANSLATION", "[TRACE-7] FINAL result=${resultEntity.translatedText}")
            Log.d("TRANSLATION", "[TRACE-7] FINAL method=database")
            if (isHoTranslation) {
                Log.d("HO-DEBUG", "finalTranslation=${resultEntity.translatedText}")
            }
            return TranslationResult(resultEntity, "database")
        }

        if (isHoTranslation) {
            Log.d("HO", "dictionary MISS")
            Log.d("HO-DEBUG", "translationMethod=none; Ho Room MISS, ONNX and server fallback skipped")
            Log.d("HO-DEBUG", "finalResult=Not found")
            return null
        }

        // 2. Offline ONNX model ONLY for genuinely supported language pairs
        var offlineFailure = ""
        var offlineException: Exception? = null
        if (sourceLanguage == "Ho" || targetLanguage == "Ho") {
            Log.d("HO", "Skipping ONNX for Ho (unsupported tag hoc_Deva)")
            offlineFailure = "ONNX skipped for unsupported Ho pair"
        } else {
            Log.d("OFFLINE-ML", "called=true")
            Log.d("TRANSLATION", "[TRACE-5] OFFLINE ENGINE called=true")
            val modelAvailable = offlineEngine.isModelAvailable()
            Log.d("OFFLINE-ML", "modelAvailable=$modelAvailable")
            val offlineTranslation = try {
                offlineEngine.translate(normalizedInput, sourceLanguage, targetLanguage)
            } catch (e: Exception) {
                offlineFailure = "OfflineTranslationEngine exception: ${e.javaClass.simpleName}: ${e.message}"
                offlineException = e
                Log.e("TRANSLATION", "[TRACE-5] OFFLINE ENGINE exception=${e.javaClass.simpleName}: ${e.message}", e)
                null
            }
            Log.d("TRANSLATION", "[TRACE-5] OFFLINE ENGINE result=${offlineTranslation ?: if (offlineException == null) "null; reason=see OFFLINE-ML RESULT" else "null; exception=${offlineException.javaClass.simpleName}"}")
            if (!offlineTranslation.isNullOrBlank()) {
                val resultEntity = if (sourceLanguage == "Hindi") {
                    TranslationEntity(
                        hindiText = rawInput,
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
                if (sourceLanguage == "Hindi") {
                    insertTranslations(listOf(resultEntity))
                }
                saveToHistory(rawInput, resultEntity.translatedText, sourceLanguage, targetLanguage, null, sourceType)
                Log.d("TRANSLATION", "method=onnx\nresult=$offlineTranslation")
                return TranslationResult(resultEntity, "onnx")
            }
            if (offlineFailure.isEmpty()) {
                offlineFailure = if (!modelAvailable) {
                    "required ONNX model assets are missing"
                } else {
                    "OfflineTranslationEngine returned no translation; inspect OFFLINE-ML logs for the specific result"
                }
                Log.e("OFFLINE-ML", "[OFFLINE-ML] RESULT=UNAVAILABLE ($offlineFailure)")
            }
        }

        // 3. Check network availability before attempting online fallback
        if (!isNetworkAvailable()) {
            offlineException?.let { throw it }
            Log.e("OFFLINE", "Room MISS; $offlineFailure; network unavailable, server fallback skipped")
            return null
        }

        // 4. FastAPI (Online Fallback)
        val request = TranslationRequest(
            text = rawInput,
            source_language = sourceLanguage,
            target_language = targetLanguage
        )
        Log.d("TRANSLATION", "[TRACE-6] ONLINE request=${RetrofitClient.TRANSLATE_URL} body=$request")
        try {
            val httpResponse = translationApi.translate(request)
            val responseBody = httpResponse.body()?.string()
                ?: httpResponse.errorBody()?.string().orEmpty()
            Log.d("TRANSLATION", "[TRACE-6] ONLINE HTTP status=${httpResponse.code()} ${httpResponse.message()}")
            Log.d("TRANSLATION", "[TRACE-6] ONLINE response=$responseBody")
            if (!httpResponse.isSuccessful) {
                throw IOException("HTTP ${httpResponse.code()} ${httpResponse.message()}: $responseBody")
            }
            val response = responseJson.decodeFromString<TranslationResponse>(responseBody)
            if (response.found) {
                if (response.translation.isBlank()) {
                    Log.e("TRANSLATION", "[TRACE-6] ONLINE response has found=true but translation is blank")
                    return null
                }
                val resultEntity = if (sourceLanguage == "Hindi") {
                    TranslationEntity(
                        hindiText = rawInput,
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
                
                saveToHistory(rawInput, resultEntity.translatedText, sourceLanguage, targetLanguage, response.phonetic, sourceType)
                Log.d("TRANSLATION", "method=server\nresult=${response.translation}")
                return TranslationResult(resultEntity, "server")
            } else {
                Log.w("TRANSLATION", "[TRACE-6] ONLINE response found=false message=${response.message}")
                return null
            }
        } catch (e: Exception) {
            Log.e("TRANSLATION", "FULL EXCEPTION", e)
            throw e
        }
    }

    suspend fun findTranslation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        sourceType: String = "text"
    ): TranslationEntity? {
        return findTranslationResult(text, sourceLanguage, targetLanguage, sourceType)?.entity
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
            Log.e("DICTIONARY", "Failed to insert ${translations.size} translations into Room", e)
        }
    }

    suspend fun initializeDatabase(loader: TranslationLoader) {
        val languages = listOf("Santali", "Ho", "Mundari")
        val files = listOf("santali.json", "ho.json", "mundari.json")
        
        val allTranslations = mutableListOf<TranslationEntity>()
        
        languages.zip(files).forEach { (lang, file) ->
            val list = loader.loadFromAssets(file, lang)
            Log.d("DICTIONARY", "$lang entries = ${list.size}")
            if (list.isNotEmpty()) {
                allTranslations.addAll(list)
            }
        }
        
        if (allTranslations.isNotEmpty()) {
            insertTranslations(allTranslations)
            Log.d("DICTIONARY", "Successfully loaded ${allTranslations.size} total entries into Room DB.")
        }

        languages.forEach { language ->
            Log.d("DICTIONARY", "$language entries in Room = ${translationDao.getCountForLanguage(language)}")
        }
    }
}
