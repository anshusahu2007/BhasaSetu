import torch
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
from indicnlp.normalize.indic_normalize import IndicNormalizerFactory
import logging

# Configure logging
logger = logging.getLogger(__name__)

class IndicTranslator:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(IndicTranslator, cls).__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return

        self.model_id = "ai4bharat/indictrans2-indic-indic-dist-320M"
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.tokenizer = None
        self.model = None
        self.normalizer = None
        self._initialized = True
        logger.info(f"IndicTranslator initialized on device: {self.device}")

    def load_model(self):
        """Loads the model, tokenizer, and normalizer without IndicTransToolkit."""
        try:
            logger.info(f"Loading model: {self.model_id}...")

            # 1. Load pure-python normalizer from indic-nlp-library
            factory = IndicNormalizerFactory()
            self.normalizer = factory.get_normalizer("hi")

            # 2. Load tokenizer and model with trust_remote_code=True
            self.tokenizer = AutoTokenizer.from_pretrained(
                self.model_id,
                trust_remote_code=True
            )

            self.model = AutoModelForSeq2SeqLM.from_pretrained(
                self.model_id,
                trust_remote_code=True
            ).to(self.device)

            self.model.eval()

            logger.info("Model and Normalizer loaded successfully.")
            return True
        except Exception as e:
            logger.error(f"Error loading model: {e}")
            self.normalizer = None
            self.tokenizer = None
            self.model = None
            return False

    def translate(self, text: str, src_lang: str, tgt_lang: str) -> str:
        """Translates text between supported Indic languages using direct HF inference."""
        if not text or not text.strip():
            return ""

        # Ensure model is loaded
        if self.model is None or self.tokenizer is None or self.normalizer is None:
            if not self.load_model():
                return ""

        try:
            # 1. Normalization
            clean_text = text.strip()
            normalized_text = self.normalizer.normalize(clean_text)

            # 2. Add language tags manually
            # Required format for IndicTrans2 distilled: "src_lang tgt_lang {text}"
            prompt = f"{src_lang} {tgt_lang} {normalized_text}"

            # 3. Tokenization
            inputs = self.tokenizer(
                prompt,
                padding=True,
                truncation=True,
                return_tensors="pt"
            ).to(self.device)

            # 4. Inference
            # CRITICAL: use_cache=False is required to avoid 'NoneType' object has no attribute 'shape'
            with torch.no_grad():
                generated_tokens = self.model.generate(
                    **inputs,
                    use_cache=False,
                    num_beams=5,
                    max_length=256,
                    repetition_penalty=1.2
                )

            # 5. Decode output
            translations = self.tokenizer.batch_decode(
                generated_tokens,
                skip_special_tokens=True,
                clean_up_tokenization_spaces=True
            )

            if not translations:
                return ""

            # Post-processing: Cleanup
            result = translations[0]
            # Strip the target tag if the model includes it in output
            return result.replace(tgt_lang, "").strip()

        except Exception as e:
            logger.error(f"Inference error for {src_lang}->{tgt_lang}: {e}")
            return ""

# Singleton instance
indic_translator = IndicTranslator()

# For backward compatibility with existing code
santali_translator = indic_translator
