package com.bhasasetu.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

import androidx.room.Index

@Entity(
    tableName = "translations",
    indices = [Index(value = ["hindiText", "language"], unique = true)]
)
data class TranslationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val hindiText: String,
    val language: String,
    val translatedText: String,
    val phoneticText: String
)
