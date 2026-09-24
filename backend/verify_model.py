import torch
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
import logging

logging.basicConfig(level=logging.ERROR)

model_id = "ai4bharat/indictrans2-indic-indic-dist-320M"
device = "cpu"

print(f"Loading model and tokenizer...")
tokenizer = AutoTokenizer.from_pretrained(model_id, trust_remote_code=True)
model = AutoModelForSeq2SeqLM.from_pretrained(model_id, trust_remote_code=True).to(device)
model.eval()

def test_translation(text, src, tgt):
    prompt = f"{src} {tgt} {text}"
    inputs = tokenizer(prompt, return_tensors="pt").to(device)
    with torch.no_grad():
        generated_tokens = model.generate(**inputs, use_cache=False, num_beams=5, max_length=256)
    translation = tokenizer.batch_decode(generated_tokens, skip_special_tokens=True)[0]
    return translation.replace(tgt, "").strip()

pairs = [
    ("नमस्ते", "hin_Deva", "sat_Olck"), # Hindi -> Santali
    ("ᱡᱚᱦᱟᱨ", "sat_Olck", "hin_Deva"), # Santali -> Hindi
    ("नमस्ते", "hin_Deva", "unr_Deva"), # Hindi -> Mundari
    ("ᱡᱚᱦᱟᱨ", "unr_Deva", "hin_Deva"), # Mundari -> Hindi (Mundari uses Ol Chiki sometimes too, but unr_Deva implies Devanagari)
    ("नमस्ते", "hin_Deva", "hoc_Deva"), # Hindi -> Ho (Expecting failure/error)
]

for text, src, tgt in pairs:
    try:
        print(f"Testing {src} -> {tgt}...")
        result = test_translation(text, src, tgt)
        print(f"Result: {result}")
    except Exception as e:
        print(f"Failed {src} -> {tgt}: {e}")
