package com.bhasasetu.app.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

@Serializable
data class TranslationRequest(
    val text: String,
    val source_language: String,
    val target_language: String
)

@Serializable
data class TranslationResponse(
    val translation: String,
    val phonetic: String,
    val offline: Boolean,
    val found: Boolean,
    val message: String
)

interface TranslationApi {
    @POST("translate")
    suspend fun translate(@Body request: TranslationRequest): TranslationResponse
}
