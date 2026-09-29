package com.bhasasetu.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@Serializable
data class TestJsonTranslation(
    val hindi: String,
    val translation: String,
    val phonetic: String
)

class TranslationUnitTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun verifyHoJsonStructureAndPhrases() {
        val hoFile = File("src/main/assets/ho.json")
        assertTrue("ho.json file must exist in assets", hoFile.exists())

        val list = json.decodeFromString<List<TestJsonTranslation>>(hoFile.readText())
        assertEquals("ho.json must contain 14 entries", 14, list.size)

        val namasteEntry = list.firstOrNull { it.hindi == "नमस्ते" }
        assertTrue("Hindi 'नमस्ते' must exist in ho.json", namasteEntry != null)
        assertEquals("Johar", namasteEntry?.translation)
        assertEquals("Johar", namasteEntry?.phonetic)
    }

    @Test
    fun testTenHindiToHoSentences() {
        val hoFile = File("src/main/assets/ho.json")
        val list = json.decodeFromString<List<TestJsonTranslation>>(hoFile.readText())
        val hoMap = list.associate { it.hindi to it.translation }

        val testCases = listOf(
            "नमस्ते" to "Johar",
            "धन्यवाद" to "Sarhao",
            "बैठ जाओ" to "Durop pe",
            "ध्यान से सुनो" to "Ajom me",
            "दोहराओ" to "Mene me",
            "लिखो" to "Ol me",
            "पढ़ो" to "Pao me",
            "आओ" to "Huju me",
            "जाओ" to "Sen me",
            "अच्छा" to "Bugi"
        )

        for ((hindiInput, expectedHo) in testCases) {
            val result = hoMap[hindiInput]
            assertNotNull("Translation for '$hindiInput' should exist", result)
            assertEquals("Hindi -> Ho translation mismatch for '$hindiInput'", expectedHo, result)
        }
    }

    @Test
    fun testTenHoToHindiSentences() {
        val hoFile = File("src/main/assets/ho.json")
        val list = json.decodeFromString<List<TestJsonTranslation>>(hoFile.readText())
        val reverseHoMap = list.associate { it.translation.lowercase() to it.hindi }

        val testCases = listOf(
            "johar" to "नमस्ते",
            "sarhao" to "धन्यवाद",
            "durop pe" to "बैठ जाओ",
            "ajom me" to "ध्यान से सुनो",
            "mene me" to "दोहराओ",
            "ol me" to "लिखो",
            "pao me" to "पढ़ो",
            "huju me" to "आओ",
            "sen me" to "जाओ",
            "bugi" to "अच्छा"
        )

        for ((hoInput, expectedHindi) in testCases) {
            val result = reverseHoMap[hoInput.lowercase()]
            assertNotNull("Reverse translation for '$hoInput' should exist", result)
            assertEquals("Ho -> Hindi translation mismatch for '$hoInput'", expectedHindi, result)
        }
    }

    @Test
    fun verifySantaliJsonStructure() {
        val file = File("src/main/assets/santali.json")
        assertTrue("santali.json file must exist in assets", file.exists())

        val list = json.decodeFromString<List<TestJsonTranslation>>(file.readText())
        assertEquals("santali.json must contain 14 entries", 14, list.size)

        val firstObj = list[0]
        assertEquals("नमस्ते", firstObj.hindi)
        assertEquals("ᱡᱚᱦᱟᱨ", firstObj.translation)
    }

    @Test
    fun verifyMundariJsonStructure() {
        val file = File("src/main/assets/mundari.json")
        assertTrue("mundari.json file must exist in assets", file.exists())

        val list = json.decodeFromString<List<TestJsonTranslation>>(file.readText())
        assertEquals("mundari.json must contain 14 entries", 14, list.size)

        val firstObj = list[0]
        assertEquals("नमस्ते", firstObj.hindi)
        assertEquals("Johar", firstObj.translation)
    }

    @Test
    fun verifyOnnxAssetsExistInProject() {
        val encoderFile = File("src/main/assets/models/encoder_model_int8.onnx")
        val decoderFile = File("src/main/assets/models/decoder_model_int8.onnx")
        val srcDictFile = File("src/main/assets/models/dict.SRC.json")
        val tgtDictFile = File("src/main/assets/models/dict.TGT.json")

        assertTrue("encoder_model_int8.onnx must exist in assets/models", encoderFile.exists() && encoderFile.length() > 0)
        assertTrue("decoder_model_int8.onnx must exist in assets/models", decoderFile.exists() && decoderFile.length() > 0)
        assertTrue("dict.SRC.json must exist in assets/models", srcDictFile.exists() && srcDictFile.length() > 0)
        assertTrue("dict.TGT.json must exist in assets/models", tgtDictFile.exists() && tgtDictFile.length() > 0)
    }
}
