from pathlib import Path

from fastapi.testclient import TestClient

from main import app
from translator import load_dataset


client = TestClient(app)


def test_home() -> None:
    response = client.get("/")

    assert response.status_code == 200
    assert response.json() == {"message": "Bhasa Setu API is running"}


def test_translate_test_record() -> None:
    response = client.post(
        "/translate",
        json={
            "text": "TEST",
            "source_language": "Hindi",
            "target_language": "Santali",
        },
    )

    assert response.status_code == 200
    assert response.json() == {
        "translation": "TEST_TRANSLATION",
        "phonetic": "TEST_PHONETIC",
        "offline": True,
        "found": True,
        "message": "Translation found",
    }


def test_translate_test_record_for_all_target_languages() -> None:
    for language in ("Santali", "Ho", "Mundari"):
        response = client.post(
            "/translate",
            json={
                "text": "TEST",
                "source_language": "Hindi",
                "target_language": language,
            },
        )

        assert response.status_code == 200
        assert response.json()["found"] is True


def test_empty_text_is_rejected() -> None:
    response = client.post(
        "/translate",
        json={"text": "  ", "source_language": "Hindi", "target_language": "Ho"},
    )

    assert response.status_code == 400
    assert response.json()["detail"] == "Text cannot be empty"


def test_invalid_language_is_rejected() -> None:
    response = client.post(
        "/translate",
        json={"text": "TEST", "source_language": "English", "target_language": "Ho"},
    )

    assert response.status_code == 400
    assert response.json()["detail"] == "Source language must be Hindi"


def test_invalid_target_language_is_rejected() -> None:
    response = client.post(
        "/translate",
        json={"text": "TEST", "source_language": "Hindi", "target_language": "English"},
    )

    assert response.status_code == 400
    assert response.json()["detail"] == "Unsupported target language"


def test_missing_translation_returns_fallback_shape() -> None:
    response = client.post(
        "/translate",
        json={
            "text": "NOT_IN_DATASET",
            "source_language": "Hindi",
            "target_language": "Mundari",
        },
    )

    assert response.status_code == 200
    assert response.json() == {
        "translation": "",
        "phonetic": "",
        "offline": False,
        "found": False,
        "message": "Translation not found",
    }


def test_invalid_request_is_rejected() -> None:
    response = client.post("/translate", json={"text": "TEST"})

    assert response.status_code == 422


def test_invalid_json_is_rejected() -> None:
    response = client.post(
        "/translate",
        content='{"text": "TEST",',
        headers={"content-type": "application/json"},
    )

    assert response.status_code == 422


def test_missing_dataset_is_treated_as_empty(monkeypatch) -> None:
    monkeypatch.setattr("translator.DATASET_DIR", Path("missing-dataset"))

    assert load_dataset("Santali") == []
