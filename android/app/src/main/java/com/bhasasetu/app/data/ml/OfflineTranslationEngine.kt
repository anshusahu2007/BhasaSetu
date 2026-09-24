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
            assetsList.contains(encoderFileName) &&
                    assetsList.contains(decoderFileName) &&
                    assetsList.contains(srcDictFileName) &&
                    assetsList.contains(tgtDictFileName)
        } catch (e: Exception) {
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

                Log.d("OFFLINE-ML", "[OFFLINE-ML] Model loading")
                ortEnv = OrtEnvironment.getEnvironment()

                val encoderFile = extractAssetIfNeeded(encoderFileName)
                val decoderFile = extractAssetIfNeeded(decoderFileName)

                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                }

                val encSession = ortEnv?.createSession(encoderFile.absolutePath, options)
                val decSession = ortEnv?.createSession(decoderFile.absolutePath, options)

                encoderSession = encSession
                decoderSession = decSession

                if (encSession != null) {
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] Encoder input names = ${encSession.inputNames}")
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] Encoder input shapes = ${encSession.inputInfo.mapValues { it.value.info }}")
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] Encoder output names = ${encSession.outputNames}")
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] Encoder output shapes = ${encSession.outputInfo.mapValues { it.value.info }}")
                }

                if (decSession != null) {
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] Decoder input names = ${decSession.inputNames}")
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] Decoder input shapes = ${decSession.inputInfo.mapValues { it.value.info }}")
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] Decoder output names = ${decSession.outputNames}")
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] Decoder output shapes = ${decSession.outputInfo.mapValues { it.value.info }}")
                }

                isInitialized = true
                Log.d("OFFLINE-ML", "Model and Tokenizer loaded successfully")
            } catch (e: Exception) {
                Log.e("OFFLINE-ML", "[OFFLINE-ML] Translation failed - initialization error: ${e.message}", e)
                isInitialized = false
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
            context.assets.open("models/$fileName").use { it.available().toLong() }
        } catch (e: Exception) {
            -1L
        }

        if (!targetFile.exists() || targetFile.length() == 0L || (assetStreamSize > 0L && targetFile.length() != assetStreamSize)) {
            Log.d("OFFLINE-ML", "Extracting asset models/$fileName to ${targetFile.absolutePath} (Asset size: $assetStreamSize, Current file size: ${targetFile.length()})")
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
        if (!isModelAvailable()) {
            Log.e("OFFLINE-ML", "[OFFLINE-ML] Translation failed - model files missing in assets")
            return null
        }

        if (!isInitialized) {
            initialize()
        }

        if (!isInitialized) {
            Log.e("OFFLINE-ML", "[OFFLINE-ML] Translation failed - engine not initialized")
            return null
        }

        return withContext(Dispatchers.Default) {
            try {
                Log.d("OFFLINE-ML", "[OFFLINE-ML] Encoding")

                val srcTag = mapLanguageTag(srcLang)
                val tgtTag = mapLanguageTag(tgtLang)

                val srcLangId = srcVocab[srcTag] ?: 8L
                val tgtLangId = srcVocab[tgtTag] ?: 29925L

                val tokenIds = tokenizeText(text)
                val fullInputIds = mutableListOf<Long>().apply {
                    add(srcLangId)
                    add(tgtLangId)
                    addAll(tokenIds)
                    add(eosTokenId)
                }.toLongArray()

                Log.d("OFFLINE-ML", "[OFFLINE-ML] source token IDs = $tokenIds")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] target token IDs = ${fullInputIds.toList()}")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] source language token/id for Hindi = $srcLangId ($srcTag)")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] target language token/id for $tgtLang = $tgtLangId ($tgtTag)")

                val env = ortEnv ?: return@withContext null
                val encSession = encoderSession ?: return@withContext null
                val decSession = decoderSession ?: return@withContext null

                val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(fullInputIds), longArrayOf(1, fullInputIds.size.toLong()))
                val maskArray = LongArray(fullInputIds.size) { 1L }
                val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(maskArray), longArrayOf(1, fullInputIds.size.toLong()))

                Log.d("OFFLINE-ML", "[OFFLINE-ML] encoder input_ids shape = [1, ${fullInputIds.size}]")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] encoder attention_mask shape = [1, ${maskArray.size}]")

                val encOutputs = encSession.run(mapOf("input_ids" to inputTensor, "attention_mask" to maskTensor))
                val encoderHiddenStates = encOutputs[0] as OnnxTensor

                Log.d("OFFLINE-ML", "[OFFLINE-ML] encoder output shape = ${encoderHiddenStates.info.shape.joinToString()}")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] encoder output name = ${encSession.outputNames}")

                Log.d("OFFLINE-ML", "[OFFLINE-ML] Decoder generation")

                val decInputTokens = mutableListOf(eosTokenId)
                val genTokens = mutableListOf<Long>()

                Log.d("OFFLINE-ML", "[OFFLINE-ML] initial decoder token IDs = $decInputTokens")

                var stoppedBecauseEOS = false
                var step = 0

                while (step < maxSeqLength) {
                    val decInputArray = decInputTokens.toLongArray()
                    val decInputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(decInputArray), longArrayOf(1, decInputArray.size.toLong()))

                    val decInputs = mapOf(
                        "input_ids" to decInputTensor,
                        "encoder_hidden_states" to encoderHiddenStates,
                        "encoder_attention_mask" to maskTensor
                    )

                    if (step == 0) {
                        Log.d("OFFLINE-ML", "[OFFLINE-ML] decoder input_ids shape = [1, ${decInputArray.size}]")
                        Log.d("OFFLINE-ML", "[OFFLINE-ML] decoder encoder_hidden_states shape = ${encoderHiddenStates.info.shape.joinToString()}")
                        Log.d("OFFLINE-ML", "[OFFLINE-ML] decoder attention_mask shape = [1, ${maskArray.size}]")
                    }

                    val decOutputs = decSession.run(decInputs)
                    val logitsTensor = decOutputs[0] as OnnxTensor

                    if (step == 0) {
                        Log.d("OFFLINE-ML", "[OFFLINE-ML] decoder logits shape = ${logitsTensor.info.shape.joinToString()}")
                    }

                    val lastPos = decInputTokens.size - 1
                    val stepAnalysis = getLogitsStepAnalysis(logitsTensor, lastPos)

                    decInputTensor.close()
                    logitsTensor.close()
                    decOutputs.close()

                    Log.d("OFFLINE-ML", "[OFFLINE-ML] step=$step inputIds=$decInputTokens")
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] step=$step logitsShape=${stepAnalysis.shape}")
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] step=$step selectedTokenId=${stepAnalysis.selectedTokenId}")
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] step=$step selectedTokenLogit=${stepAnalysis.selectedTokenLogit}")
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] step=$step topTokenIds=${stepAnalysis.topTokenIds}")
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] step=$step topTokenScores=${stepAnalysis.topTokenScores}")

                    val nextToken = stepAnalysis.selectedTokenId

                    if (nextToken == eosTokenId) {
                        stoppedBecauseEOS = true
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
                Log.d("OFFLINE-ML", "[OFFLINE-ML] generatedTokenCount=${genTokens.size}")
                Log.d("OFFLINE-ML", "[OFFLINE-ML] stoppedBecauseEOS=$stoppedBecauseEOS")

                val resultText = detokenize(genTokens)
                Log.d("OFFLINE-ML", "[OFFLINE-ML] decodedText='$resultText'")

                if (resultText.isNotBlank()) {
                    Log.d("OFFLINE-ML", "[OFFLINE-ML] Translation success: $resultText")
                    resultText
                } else {
                    Log.e("OFFLINE-ML", "[OFFLINE-ML] Translation failed: empty result")
                    null
                }
            } catch (e: Exception) {
                Log.e("OFFLINE-ML", "[OFFLINE-ML] CRITICAL DECODER EXCEPTION: ${e.message}", e)
                e.printStackTrace()
                null
            }
        }
    }

    private fun mapLanguageTag(langName: String): String {
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
            val prefixed1 = "▁$w"
            val prefixed2 = " $w"
            val prefixed3 = "\u2581$w"

            when {
                srcVocab.containsKey(prefixed1) -> result.add(srcVocab[prefixed1]!!)
                srcVocab.containsKey(prefixed2) -> result.add(srcVocab[prefixed2]!!)
                srcVocab.containsKey(prefixed3) -> result.add(srcVocab[prefixed3]!!)
                srcVocab.containsKey(w) -> result.add(srcVocab[w]!!)
                else -> subwordTokenize("▁$w", result)
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

