package com.bhasasetu.app.data.ml

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer

/**
 * Handles true on-device Machine Translation using ONNX Runtime Mobile.
 * Designed for IndicTrans2 320M INT8 quantized models.
 */
class OfflineTranslationEngine(private val context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var isInitialized = false

    private val srcVocab = mutableMapOf<String, Long>()
    private val tgtIdToTokenMap = mutableMapOf<Long, String>()

    private var bosTokenId = 0L
    private var padTokenId = 1L
    private var eosTokenId = 2L
    private var unkTokenId = 3L

    private val maxSeqLength = 128

    private val encoderFileName = "encoder_model_int8.onnx"
    private val decoderFileName = "decoder_model_int8.onnx"
    private val srcDictFileName = "dict.SRC.json"
    private val tgtDictFileName = "dict.TGT.json"

    /**
     * Checks if the required ONNX model and tokenizer files are present in assets.
     */
    fun isModelAvailable(): Boolean {
        return try {
            val assetsList = context.assets.list("models") ?: emptyArray()
            val hasEncoder = assetsList.contains(encoderFileName)
            val hasDecoder = assetsList.contains(decoderFileName)
            val hasSrcDict = assetsList.contains(srcDictFileName)
            val hasTgtDict = assetsList.contains(tgtDictFileName)
            hasEncoder && hasDecoder && hasSrcDict && hasTgtDict
        } catch (e: Exception) {
            Log.e("OFFLINE-ML", "[OFFLINE-ML] EXCEPTION=checking model assets", e)
            false
        }
    }

    private suspend fun initialize() {
        if (isInitialized) return

        withContext(Dispatchers.IO) {
            try {
                Log.d("OFFLINE-ML", "[OFFLINE-ML] Tokenizer loading")
                val srcDictFile = extractAssetIfNeeded(srcDictFileName)
                val tgtDictFile = extractAssetIfNeeded(tgtDictFileName)

                val srcJsonStr = srcDictFile.readText()
                val srcJsonObj = JSONObject(srcJsonStr)
                val srcKeys = srcJsonObj.keys()
                while (srcKeys.hasNext()) {
                    val key = srcKeys.next()
                    srcVocab[key] = srcJsonObj.getLong(key)
                }

                val tgtJsonStr = tgtDictFile.readText()
                val tgtJsonObj = JSONObject(tgtJsonStr)
                val tgtKeys = tgtJsonObj.keys()
                while (tgtKeys.hasNext()) {
                    val key = tgtKeys.next()
                    val id = tgtJsonObj.getLong(key)
                    tgtIdToTokenMap[id] = key
                }

                bosTokenId = srcVocab["<s>"] ?: 0L
                padTokenId = srcVocab["<pad>"] ?: 1L
                eosTokenId = srcVocab["</s>"] ?: 2L
                unkTokenId = srcVocab["<unk>"] ?: 3L

                Log.d("OFFLINE-ML", "[OFFLINE-ML] BOS token id = $bosTokenId")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] EOS token id = $eosTokenId")

                val encName = encoderFileName
                val decName = decoderFileName
                Log.d("OFFLINE-ML", "[OFFLINE-ML] encoder asset = $encName")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] decoder asset = $decName")
                val environment = OrtEnvironment.getEnvironment()
                ortEnv = environment

                val encoderFile = extractAssetIfNeeded(encName)
                val decoderFile = extractAssetIfNeeded(decName)
                require(encoderFile.isFile && encoderFile.length() > 0L) { "Encoder ONNX asset is missing or empty: ${encoderFile.absolutePath}" }
                require(decoderFile.isFile && decoderFile.length() > 0L) { "Decoder ONNX asset is missing or empty: ${decoderFile.absolutePath}" }
                Log.d("OFFLINE-ML", "[OFFLINE-ML] encoder size = ${encoderFile.length()}")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] decoder size = ${decoderFile.length()}")

                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                }

                val encSession = environment.createSession(encoderFile.absolutePath, options)
                Log.d("OFFLINE-ML", "[OFFLINE-ML] Encoder session loaded successfully")
                val decSession = environment.createSession(decoderFile.absolutePath, options)
                Log.d("OFFLINE-ML", "[OFFLINE-ML] Decoder session loaded successfully")

                require(encSession.inputNames.containsAll(setOf("input_ids", "attention_mask"))) {
                    "Unexpected encoder inputs: ${encSession.inputNames}"
                }
                require(encSession.outputNames.contains("last_hidden_state")) {
                    "Unexpected encoder outputs: ${encSession.outputNames}"
                }
                require(decSession.inputNames.containsAll(setOf("input_ids", "encoder_hidden_states", "encoder_attention_mask"))) {
                    "Unexpected decoder inputs: ${decSession.inputNames}"
                }
                require(decSession.outputNames.contains("logits")) {
                    "Unexpected decoder outputs: ${decSession.outputNames}"
                }

                encoderSession = encSession
                decoderSession = decSession

                Log.d("OFFLINE-ML", "[OFFLINE-ML] Encoder input names = ${encSession.inputNames}")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] Encoder output names = ${encSession.outputNames}")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] Decoder input names = ${decSession.inputNames}")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] Decoder output names = ${decSession.outputNames}")

                isInitialized = true
                Log.d("OFFLINE-ML", "Model and Tokenizer loaded successfully")
            } catch (e: Exception) {
                Log.e("OFFLINE-ML", "[OFFLINE-ML] EXCEPTION=initializing ONNX sessions: ${e.message}", e)
                isInitialized = false
                throw e
            }
        }
    }

    private fun extractAssetIfNeeded(fileName: String): File {
        val modelsDir = File(context.cacheDir, "models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        val targetFile = File(modelsDir, fileName)

        val assetStreamSize = try {
            context.assets.openFd("models/$fileName").use { it.length }
        } catch (e: Exception) {
            -1L
        }

        if (!targetFile.exists() || targetFile.length() == 0L || (assetStreamSize > 0L && targetFile.length() != assetStreamSize)) {
            Log.d("OFFLINE-ML", "Extracting asset models/$fileName to ${targetFile.absolutePath} (Asset size: $assetStreamSize)")
            context.assets.open("models/$fileName").use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d("OFFLINE-ML", "Extracted models/$fileName successfully. New size: ${targetFile.length()}")
        } else {
            Log.d("OFFLINE-ML", "Using cached models/$fileName (${targetFile.length()} bytes)")
        }
        return targetFile
    }

    /**
     * Translates sentence offline using ONNX model inference.
     */
    suspend fun translate(text: String, srcLang: String, tgtLang: String): String? {
        Log.d("OFFLINE-ML", "[OFFLINE-ML] START")
        Log.d("OFFLINE-ML", "[OFFLINE-ML] source=$srcLang")
        Log.d("OFFLINE-ML", "[OFFLINE-ML] target=$tgtLang")
        Log.d("OFFLINE-ML", "[OFFLINE-ML] input=$text")

        if (!isModelAvailable()) {
            val availableAssets = try {
                context.assets.list("models")?.toSet().orEmpty()
            } catch (e: Exception) {
                emptySet()
            }
            val requiredAssets = listOf(encoderFileName, decoderFileName, srcDictFileName, tgtDictFileName)
            val missingAssets = requiredAssets.filterNot(availableAssets::contains)
            Log.e("OFFLINE-ML", "[OFFLINE-ML] RESULT=UNAVAILABLE; missingAssets=$missingAssets; availableAssets=${availableAssets.sorted()}")
            return null
        }

        if (!isInitialized) {
            initialize()
        }

        if (!isInitialized) {
            throw IllegalStateException("ONNX sessions were not initialized")
        }

        return withContext(Dispatchers.Default) {
            try {
                Log.d("OFFLINE-ML", "[OFFLINE-ML] Encoding")

                val srcTag = languageTag(srcLang)
                val tgtTag = languageTag(tgtLang)

                Log.d("OFFLINE-ML", "[OFFLINE-ML] sourceTag=$srcTag targetTag=$tgtTag")

                if (!srcVocab.containsKey(srcTag) || !srcVocab.containsKey(tgtTag)) {
                    Log.w("OFFLINE-ML", "[OFFLINE-ML] RESULT=UNSUPPORTED; sourceTag=$srcTag targetTag=$tgtTag")
                    return@withContext null
                }

                val srcLangId = checkNotNull(srcVocab[srcTag]) { "Source language token missing: $srcTag" }
                val tgtLangId = checkNotNull(srcVocab[tgtTag]) { "Target language token missing: $tgtTag" }

                val tokenIds = tokenizeText(text)
                val fullInputIds = mutableListOf<Long>().apply {
                    add(srcLangId)
                    add(tgtLangId)
                    addAll(tokenIds)
                    add(eosTokenId)
                }.toLongArray()

                Log.d("OFFLINE-ML", "[OFFLINE-ML] source token IDs = $tokenIds")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] target token IDs = ${fullInputIds.toList()}")

                val env = checkNotNull(ortEnv) { "ONNX Runtime environment is not initialized" }
                val encSession = checkNotNull(encoderSession) { "Encoder session is not initialized" }
                val decSession = checkNotNull(decoderSession) { "Decoder session is not initialized" }

                val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(fullInputIds), longArrayOf(1, fullInputIds.size.toLong()))
                val maskArray = LongArray(fullInputIds.size) { 1L }
                val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(maskArray), longArrayOf(1, fullInputIds.size.toLong()))

                val encOutputs = encSession.run(mapOf("input_ids" to inputTensor, "attention_mask" to maskTensor))
                val encoderHiddenStates = encOutputs.get("last_hidden_state").orElseThrow {
                    IllegalStateException("Encoder output last_hidden_state is missing")
                } as OnnxTensor

                Log.d("OFFLINE-ML", "[OFFLINE-ML] Decoder generation")

                val decInputTokens = mutableListOf(eosTokenId)
                val genTokens = mutableListOf<Long>()

                var step = 0

                while (step < maxSeqLength) {
                    val decInputArray = decInputTokens.toLongArray()
                    val decInputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(decInputArray), longArrayOf(1, decInputArray.size.toLong()))

                    val decInputs = mapOf(
                        "input_ids" to decInputTensor,
                        "encoder_hidden_states" to encoderHiddenStates,
                        "encoder_attention_mask" to maskTensor
                    )

                    val decOutputs = decSession.run(decInputs)
                    val logitsTensor = decOutputs.get("logits").orElseThrow {
                        IllegalStateException("Decoder output logits is missing")
                    } as OnnxTensor

                    val lastPos = decInputTokens.size - 1
                    val stepAnalysis = getLogitsStepAnalysis(logitsTensor, lastPos)

                    decInputTensor.close()
                    logitsTensor.close()
                    decOutputs.close()

                    val nextToken = stepAnalysis.selectedTokenId

                    if (nextToken == eosTokenId) {
                        break
                    }

                    genTokens.add(nextToken)
                    decInputTokens.add(nextToken)
                    step++
                }

                inputTensor.close()
                maskTensor.close()
                encoderHiddenStates.close()
                encOutputs.close()

                Log.d("OFFLINE-ML", "[OFFLINE-ML] generatedTokenIds=$genTokens")

                val resultText = detokenize(genTokens)
                Log.d("OFFLINE-ML", "[OFFLINE-ML] decodedText='$resultText'")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] RESULT=$resultText")
                Log.d("ONNX", "translation=$resultText")

                if (resultText.isNotBlank()) {
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] Translation success: $resultText")
                    resultText
                } else {
                    Log.e("OFFLINE-ML", "[OFFLINE-ML] RESULT=EMPTY; generatedTokenIds=$genTokens")
                    null
                }
            } catch (e: Exception) {
                Log.e("OFFLINE-ML", "[OFFLINE-ML] EXCEPTION=${e.javaClass.simpleName}: ${e.message}", e)
                throw e
            }
        }
    }

    fun languageTag(langName: String): String {
        return when (langName.lowercase()) {
            "hindi" -> "hin_Deva"
            "santali" -> "sat_Olck"
            "mundari" -> "unr_Deva"
            "ho" -> "hoc_Deva"
            else -> langName
        }
    }

    private fun tokenizeText(text: String): List<Long> {
        val result = mutableListOf<Long>()
        val words = text.trim().split("\\s+".toRegex())

        for (w in words) {
            if (w.isBlank()) continue
            val prefixed = "\u2581$w"

            when {
                srcVocab.containsKey(prefixed) -> result.add(srcVocab[prefixed]!!)
                srcVocab.containsKey(w) -> result.add(srcVocab[w]!!)
                else -> subwordTokenize("\u2581$w", result)
            }
        }
        return result
    }

    private fun subwordTokenize(word: String, result: MutableList<Long>) {
        var p = 0
        while (p < word.length) {
            var matched = false
            for (end in word.length downTo p + 1) {
                val sub = word.substring(p, end)
                val id = srcVocab[sub]
                if (id != null) {
                    result.add(id)
                    p = end
                    matched = true
                    break
                }
            }
            if (!matched) {
                result.add(unkTokenId)
                p++
            }
        }
    }

    private data class LogitsStepAnalysis(
        val shape: String,
        val selectedTokenId: Long,
        val selectedTokenLogit: Float,
        val topTokenIds: List<Long>,
        val topTokenScores: List<Float>
    )

    private fun getLogitsStepAnalysis(logitsTensor: OnnxTensor, seqIndex: Int): LogitsStepAnalysis {
        val shape = logitsTensor.info.shape
        val shapeStr = "[${shape.joinToString()}]"
        val vocabSize = shape[2].toInt()
        val floatBuffer = logitsTensor.floatBuffer
        val offset = seqIndex * vocabSize

        val topIndices = (0 until vocabSize).map { i ->
            i to floatBuffer.get(offset + i)
        }.sortedByDescending { it.second }.take(10)

        val topTokenIds = topIndices.map { it.first.toLong() }
        val topTokenScores = topIndices.map { it.second }
        val selectedTokenId = topIndices.first().first.toLong()
        val selectedTokenLogit = topIndices.first().second

        return LogitsStepAnalysis(
            shape = shapeStr,
            selectedTokenId = selectedTokenId,
            selectedTokenLogit = selectedTokenLogit,
            topTokenIds = topTokenIds,
            topTokenScores = topTokenScores
        )
    }

    private fun detokenize(ids: List<Long>): String {
        val ignoreIds = setOf(bosTokenId, padTokenId, eosTokenId, unkTokenId, 8L, 29925L, 121515L)
        val tokens = mutableListOf<String>()

        for (id in ids) {
            if (ignoreIds.contains(id)) continue
            val token = tgtIdToTokenMap[id] ?: continue
            tokens.add(token)
        }

        val raw = tokens.joinToString("").replace("▁", " ").replace(" ", " ").replace("\u2581", " ")
        return raw.trim().replace("\\s+".toRegex(), " ")
    }
}
