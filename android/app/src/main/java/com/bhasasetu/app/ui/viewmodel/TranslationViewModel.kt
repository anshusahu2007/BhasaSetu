package com.bhasasetu.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bhasasetu.app.data.TranslationLoader
import com.bhasasetu.app.data.TranslationRepository
import com.bhasasetu.app.data.local.TranslationEntity
import com.bhasasetu.app.data.local.TranslationHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.UnknownHostException

data class TranslationUiState(
    val hindiText: String = "",
    val selectedLanguage: String = "Santali",
    val translation: String = "",
    val phonetic: String = "",
    val studentRecognizedText: String = "",
    val studentHindiResult: String = "",
    val isLoading: Boolean = false,
    val isStudentLoading: Boolean = false,
    val error: String? = null,
    val studentError: String? = null,
    val translationStatus: String = "",
    val studentTranslationStatus: String = "",
    val teacherInputSource: String = "text",
    val studentInputSource: String = "text",
    val selectedTab: Int = 0
)

class TranslationViewModel(
    private val repository: TranslationRepository,
    private val loader: TranslationLoader
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranslationUiState())
    val uiState: StateFlow<TranslationUiState> = _uiState.asStateFlow()

    val translationHistory: StateFlow<List<TranslationHistoryEntity>> = repository.getAllHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        initializeData()
    }

    private fun initializeData() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                withContext(Dispatchers.IO) {
                    repository.initializeDatabase(loader)
                }
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                Log.e("TranslationVM", "Init Error", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Initialization error: ${e.localizedMessage}"
                )
            }
        }
    }

    fun onHindiTextChanged(text: String, source: String = "text") {
        _uiState.value = _uiState.value.copy(
            hindiText = text, 
            error = null, 
            translationStatus = "",
            teacherInputSource = source
        )
    }

    fun onLanguageSelected(language: String) {
        _uiState.value = _uiState.value.copy(selectedLanguage = language, error = null, translationStatus = "")
    }

    fun onTabSelected(index: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = index)
    }

    fun onStudentSpeechRecognized(text: String) {
        _uiState.value = _uiState.value.copy(
            studentRecognizedText = text, 
            studentHindiResult = "", 
            studentError = null, 
            studentTranslationStatus = "",
            studentInputSource = "voice"
        )
    }

    fun onStudentTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(
            studentRecognizedText = text, 
            studentHindiResult = "", 
            studentError = null, 
            studentTranslationStatus = "",
            studentInputSource = "text"
        )
    }

    fun translate() {
        val currentState = _uiState.value
        if (currentState.hindiText.isBlank()) {
            _uiState.value = currentState.copy(error = "Please enter Hindi text")
            return
        }

        viewModelScope.launch {
            _uiState.value = currentState.copy(isLoading = true, translation = "", phonetic = "", error = null, translationStatus = "Translating...")
            
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.findTranslation(
                        text = currentState.hindiText, 
                        sourceLanguage = "Hindi", 
                        targetLanguage = currentState.selectedLanguage,
                        sourceType = currentState.teacherInputSource
                    )
                }

                if (result != null) {
                    val status = if (result.id != 0L) "Translation found offline" else "Translated using server"
                    _uiState.value = _uiState.value.copy(
                        translation = result.translatedText,
                        phonetic = result.phoneticText,
                        isLoading = false,
                        translationStatus = status
                    )
                } else {
                    val isOffline = !repository.isNetworkAvailable()
                    val status = if (isOffline) {
                        "Offline translation is not available for this sentence. Connect to the translation server or use a saved/local translation."
                    } else if (currentState.selectedLanguage == "Ho") {
                        "Ho dictionary entry not available"
                    } else {
                        "Translation not found"
                    }
                        
                    _uiState.value = _uiState.value.copy(
                        translation = "Not found",
                        phonetic = "",
                        isLoading = false,
                        translationStatus = status
                    )
                }
            } catch (e: Exception) {
                Log.e("TranslationVM", "Translation Error", e)
                val isOffline = !repository.isNetworkAvailable()
                val status = if (isOffline) {
                    "Offline translation is not available for this sentence. Connect to the translation server or use a saved/local translation."
                } else {
                    "Unable to connect to translation server"
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    translationStatus = status,
                    error = "Network/Connection error. Check backend."
                )
            }
        }
    }

    fun translateToHindi() {
        val currentState = _uiState.value
        if (currentState.studentRecognizedText.isBlank()) {
            _uiState.value = currentState.copy(studentError = "No text to translate")
            return
        }

        viewModelScope.launch {
            _uiState.value = currentState.copy(isStudentLoading = true, studentHindiResult = "", studentError = null, studentTranslationStatus = "Translating...")
            
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.findTranslation(
                        text = currentState.studentRecognizedText, 
                        sourceLanguage = currentState.selectedLanguage, 
                        targetLanguage = "Hindi",
                        sourceType = currentState.studentInputSource
                    )
                }

                if (result != null) {
                    val status = if (result.id != 0L) "Translation found offline" else "Translated using server"
                    _uiState.value = _uiState.value.copy(
                        studentHindiResult = result.translatedText,
                        isStudentLoading = false,
                        studentTranslationStatus = status
                    )
                } else {
                    val isOffline = !repository.isNetworkAvailable()
                    val status = if (isOffline) {
                        "Offline translation is not available for this sentence. Connect to the translation server or use a saved/local translation."
                    } else if (currentState.selectedLanguage == "Ho") {
                        "Ho dictionary entry not available"
                    } else {
                        "Translation not found"
                    }
                        
                    _uiState.value = _uiState.value.copy(
                        studentHindiResult = "Not found",
                        isStudentLoading = false,
                        studentTranslationStatus = status
                    )
                }
            } catch (e: Exception) {
                Log.e("TranslationVM", "Student Translation Error", e)
                val isOffline = !repository.isNetworkAvailable()
                val status = if (isOffline) {
                    "Offline translation is not available for this sentence. Connect to the translation server or use a saved/local translation."
                } else {
                    "Unable to connect to translation server"
                }
                _uiState.value = _uiState.value.copy(
                    isStudentLoading = false,
                    studentTranslationStatus = status,
                    studentError = "Network error. Check connection."
                )
            }
        }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            repository.deleteHistoryItem(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
        }
    }
}
