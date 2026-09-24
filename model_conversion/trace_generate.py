import torch
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

model_id = r"C:\Users\anshu\.cache\huggingface\hub\models--ai4bharat--indictrans2-indic-indic-dist-320M\snapshots\ffb7582b6d43791f1fb26b2153fc065f2e9ea575"
tokenizer = AutoTokenizer.from_pretrained(model_id, trust_remote_code=True)
model = AutoModelForSeq2SeqLM.from_pretrained(model_id, trust_remote_code=True)

text = "आपका स्वागत है"
src = "hin_Deva"
tgt = "sat_Olck"

prompt = f"{src} {tgt} {text}"
inputs = tokenizer(prompt, return_tensors="pt")

# Intercept the input to see what goes to the model
print(f"Model Input IDs: {inputs['input_ids']}")

# Force a specific start token
with torch.no_grad():
    # We use a custom call to see what the model generates token by token
    encoder_outputs = model.get_encoder()(input_ids=inputs['input_ids'], attention_mask=inputs['attention_mask'])

    decoder_input_ids = torch.tensor([[model.config.decoder_start_token_id]])
    print(f"Initial Decoder Input: {decoder_input_ids}")

    # Try one step
    outputs = model(
        input_ids=inputs['input_ids'],
        attention_mask=inputs['attention_mask'],
        decoder_input_ids=decoder_input_ids,
        use_cache=False
    )
    logits = outputs.logits
    next_token = torch.argmax(logits[:, -1, :], dim=-1)
    print(f"Step 1 Next Token ID: {next_token}")
    print(f"Step 1 Next Token: {tokenizer.decode(next_token)}")

    # Full generate with use_cache=False
    gen = model.generate(**inputs, use_cache=False, max_length=10, num_beams=1)
    print(f"Full Generate Result: {gen}")
    print(f"Full Decode: {tokenizer.decode(gen[0], skip_special_tokens=True)}")
