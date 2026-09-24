package com.bhasasetu.app.data

import android.content.Context
import com.bhasasetu.app.data.local.TranslationEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

@Serializable
data class JsonTranslation(
    val hindi: String,
    val translation: String,
    val phonetic: String
)

class TranslationLoader(private val context: Context) {

    private val json = Json { 
        ignoreUnknownKeys = true 
    }

    fun loadFromAssets(fileName: String, language: String): List<TranslationEntity> {
        return try {
            val jsonString = context.assets.open(fileName).bufferedReader().use { it.readText() }
            val list = json.decodeFromString<List<JsonTranslation>>(jsonString)
            list.map {
                TranslationEntity(
                    hindiText = it.hindi,
                    language = language,
                    translatedText = it.translation,
                    phoneticText = it.phonetic
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
