from transformers import AutoTokenizer

model_id = r"C:\Users\anshu\.cache\huggingface\hub\models--ai4bharat--indictrans2-indic-indic-dist-320M\snapshots\ffb7582b6d43791f1fb26b2153fc065f2e9ea575"
tokenizer = AutoTokenizer.from_pretrained(model_id, trust_remote_code=True)

print(f"BOS: {tokenizer.bos_token_id}")
print(f"EOS: {tokenizer.eos_token_id}")
print(f"PAD: {tokenizer.pad_token_id}")

langs = ["hin_Deva", "sat_Olck", "unr_Deva"]
for lang in langs:
    print(f"{lang}: {tokenizer.convert_tokens_to_ids(lang)}")

test_text = "आपका स्वागत है"
encoded = tokenizer(f"hin_Deva sat_Olck {test_text}", return_tensors="pt")
print(f"Full encoded: {encoded['input_ids']}")
