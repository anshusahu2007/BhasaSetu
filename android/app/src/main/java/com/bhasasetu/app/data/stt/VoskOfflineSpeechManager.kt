package com.bhasasetu.app.data.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.RecognitionListener
import java.io.File
import java.io.FileOutputStream

class VoskOfflineSpeechManager(private val context: Context) {

    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
    private var isInitializing = false

    suspend fun ensureModelLoaded(): Boolean {
        if (voskModel != null) return true
        if (isInitializing) return false

        isInitializing = true
        return withContext(Dispatchers.IO) {
            try {
                Log.d("OFFLINE-STT", "[OFFLINE-STT] Model loading")
                val modelDir = File(context.filesDir, "model-hi")
                if (!isModelFolderValid(modelDir)) {
                    copyAssetFolder("model-hi", modelDir)
                }

                voskModel = Model(modelDir.absolutePath)
                isInitializing = false
                Log.d("OFFLINE-STT", "[OFFLINE-STT] Model loaded")
                true
            } catch (e: Exception) {
                isInitializing = false
                Log.e("OFFLINE-STT", "[OFFLINE-STT] Recognition error: ${e.message}", e)
                false
            }
        }
    }

    private fun isModelFolderValid(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val confDir = File(dir, "conf")
        val amDir = File(dir, "am")
        val graphDir = File(dir, "graph")
        return confDir.exists() && amDir.exists() && graphDir.exists()
    }

    private fun copyAssetFolder(assetPath: String, targetDir: File) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val assets = context.assets.list(assetPath) ?: return
        if (assets.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetDir).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            for (file in assets) {
                val subAssetPath = if (assetPath.isEmpty()) file else "$assetPath/$file"
                val subTargetFile = File(targetDir, file)
                val subAssets = context.assets.list(subAssetPath)
                if (subAssets != null && subAssets.isNotEmpty()) {
                    copyAssetFolder(subAssetPath, subTargetFile)
                } else {
                    context.assets.open(subAssetPath).use { input ->
                        FileOutputStream(subTargetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onListeningStarted: () -> Unit
    ) {
        val model = voskModel
        if (model == null) {
            Log.e("OFFLINE-STT", "[OFFLINE-STT] Recognition error: Model not loaded")
            onError("Offline speech model not ready. Please try again.")
            return
        }

        try {
            stopListening()

            Log.d("OFFLINE-STT", "[OFFLINE-STT] Listening")
            val recognizer = Recognizer(model, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)

            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = parseText(hypothesis, "partial")
                    if (!text.isNullOrBlank()) {
                        Log.d("OFFLINE-STT", "Partial result: $text")
                    }
                }

                override fun onResult(hypothesis: String?) {
                    val text = parseText(hypothesis, "text")
                    if (!text.isNullOrBlank()) {
                        Log.d("OFFLINE-STT", "[OFFLINE-STT] Recognized text: $text")
                        onResult(text)
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    val text = parseText(hypothesis, "text")
                    if (!text.isNullOrBlank()) {
                        Log.d("OFFLINE-STT", "[OFFLINE-STT] Recognized text: $text")
                        onResult(text)
                    }
                }

                override fun onError(exception: Exception?) {
                    Log.e("OFFLINE-STT", "[OFFLINE-STT] Recognition error: ${exception?.message}", exception)
                    onError(exception?.message ?: "Recognition error")
                }

                override fun onTimeout() {
                    Log.d("OFFLINE-STT", "Speech recognition timeout")
                }
            })
            onListeningStarted()
        } catch (e: Exception) {
            Log.e("OFFLINE-STT", "[OFFLINE-STT] Recognition error: ${e.message}", e)
            onError(e.message ?: "Failed to start listening")
        }
    }

    fun stopListening() {
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
        } catch (e: Exception) {
            speechService = null
        }
    }

    private fun parseText(jsonStr: String?, key: String): String? {
        if (jsonStr.isNullOrBlank()) return null
        return try {
            val json = JSONObject(jsonStr)
            if (json.has(key)) json.getString(key).trim() else null
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        stopListening()
        try {
            voskModel?.close()
            voskModel = null
        } catch (e: Exception) {
            voskModel = null
        }
    }
}
