from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from typing import Any

from translator import translate_text
from translator_model import indic_translator

SUPPORTED_LANGUAGES = ("Santali", "Ho", "Mundari", "Hindi")

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Load model on startup
    indic_translator.load_model()
    yield

app = FastAPI(
    title="Bhasa Setu API",
    description=(
        "Bi-directional Hindi to vernacular translation API for Bhasa Setu. "
        "Supported target languages are Santali, Ho, and Mundari."
    ),
    version="1.1.0",
    lifespan=lifespan
)


class TranslationRequest(BaseModel):
    text: str = Field(..., description="Text to translate.")
    source_language: str = Field(..., description="Source language (e.g. Hindi, Santali).")
    target_language: str = Field(..., description="Target language (e.g. Hindi, Santali).")


class TranslationResponse(BaseModel):
    translation: str = Field(description="Translated text, or an empty string if absent.")
    phonetic: str = Field(description="Pronunciation guide, or an empty string if absent.")
    offline: bool = Field(description="Whether the result came from the local dataset.")
    found: bool = Field(description="Whether a matching translation was found.")
    message: str = Field(description="Human-readable result status.")


@app.get("/", summary="Health check")
def home() -> dict[str, str]:
    return {
        "message": "Bhasa Setu API is running"
    }


@app.post(
    "/translate",
    response_model=TranslationResponse,
    summary="Translate text bi-directionally",
    description=(
        "Translates text between Hindi and vernacular languages (Santali, Ho, Mundari). "
        "Prioritizes dictionary lookup, then falls back to the ML model for supported pairs."
    ),
)
def translate(request: TranslationRequest) -> TranslationResponse:

    if not request.text.strip():
        raise HTTPException(
            status_code=400,
            detail="Text cannot be empty"
        )

    if request.source_language not in SUPPORTED_LANGUAGES:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported source language: {request.source_language}"
        )

    if request.target_language not in SUPPORTED_LANGUAGES:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported target language: {request.target_language}"
        )

    if request.source_language == request.target_language:
        return {
            "translation": request.text,
            "phonetic": "",
            "offline": True,
            "found": True,
            "message": "Source and target languages are identical."
        }

    result = translate_text(
        request.text,
        request.source_language,
        request.target_language
    )

    return {
        "translation": result["translation"],
        "phonetic": result["phonetic"],
        "offline": result["offline"],
        "found": result["found"],
        "message": result["message"]
    }
