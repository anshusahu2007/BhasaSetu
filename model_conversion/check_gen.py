import torch
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

model_id = r"C:\Users\anshu\.cache\huggingface\hub\models--ai4bharat--indictrans2-indic-indic-dist-320M\snapshots\ffb7582b6d43791f1fb26b2153fc065f2e9ea575"
tokenizer = AutoTokenizer.from_pretrained(model_id, trust_remote_code=True)
model = AutoModelForSeq2SeqLM.from_pretrained(model_id, trust_remote_code=True)

text = "आपका स्वागत है"
src = "hin_Deva"
tgt = "sat_Olck"

# How IndicTranslator does it
prompt = f"{src} {tgt} {text}"
inputs = tokenizer(prompt, return_tensors="pt")

with torch.no_grad():
    generated_tokens = model.generate(
        **inputs,
        use_cache=True,
        num_beams=1, # Greedy for simplicity
        max_length=50
    )

print(f"Generated IDs: {generated_tokens}")
print(f"Decoded: {tokenizer.decode(generated_tokens[0], skip_special_tokens=True)}")
