package com.bhasasetu.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bhasasetu.app.data.TranslationLoader
import com.bhasasetu.app.data.TranslationRepository

class TranslationViewModelFactory(
    private val repository: TranslationRepository,
    private val loader: TranslationLoader
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TranslationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TranslationViewModel(repository, loader) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
