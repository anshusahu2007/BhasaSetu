package com.bhasasetu.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bhasasetu.app.data.TranslationLoader
import com.bhasasetu.app.data.TranslationRepository
import com.bhasasetu.app.data.local.AppDatabase
import com.bhasasetu.app.data.ml.OfflineTranslationEngine
import com.bhasasetu.app.data.remote.TranslationApi
import com.bhasasetu.app.data.remote.TranslationRequest
import okhttp3.ResponseBody
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import retrofit2.Response

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun offlineOnnxEngineLoadsAndTranslatesHindiToSantali() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = OfflineTranslationEngine(appContext)

        val result = engine.translate("आपका स्वागत है", "Hindi", "Santali")

        assertNotNull(result)
        assertTrue(!result.isNullOrBlank())
    }

    @Test
    fun hindiNamasteToSantaliReturnsBundledRoomTranslation() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val loader = TranslationLoader(appContext)
            val santaliEntries = loader.loadFromAssets("santali.json", "Santali")
            assertEquals(14, santaliEntries.size)
            database.translationDao().insertAll(santaliEntries)

            val repository = TranslationRepository(
                context = appContext,
                translationDao = database.translationDao(),
                translationHistoryDao = database.translationHistoryDao(),
                translationApi = UnexpectedApiCall()
            )

            val result = repository.findTranslationResult("नमस्ते", "Hindi", "Santali")

            assertNotNull(result)
            assertEquals("database", result?.sourceMethod)
            assertEquals("ᱡᱚᱦᱟᱨ", result?.entity?.translatedText)
        } finally {
            database.close()
        }
    }
}

private class UnexpectedApiCall : TranslationApi {
    override suspend fun translate(request: TranslationRequest): Response<ResponseBody> {
        error("FastAPI must not be called after the Room dictionary hit")
    }
}