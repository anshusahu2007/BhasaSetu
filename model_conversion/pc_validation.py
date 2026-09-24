import onnxruntime as ort
import numpy as np
from transformers import AutoTokenizer, AutoConfig
import torch
import os

model_dir = "onnx_output"
tokenizer_id = r"C:\Users\anshu\.cache\huggingface\hub\models--ai4bharat--indictrans2-indic-indic-dist-320M\snapshots\ffb7582b6d43791f1fb26b2153fc065f2e9ea575"

print("Loading tokenizer...")
tokenizer = AutoTokenizer.from_pretrained(tokenizer_id, trust_remote_code=True)

def translate_onnx(text, src_lang, tgt_lang, use_int8=False):
    suffix = "_int8" if use_int8 else ""

    enc_path = os.path.join(model_dir, f"encoder_model{suffix}.onnx")
    dec_path = os.path.join(model_dir, f"decoder_model{suffix}.onnx")
    dec_past_path = os.path.join(model_dir, f"decoder_with_past_model{suffix}.onnx")

    providers = ['CPUExecutionProvider']
    enc_session = ort.InferenceSession(enc_path, providers=providers)
    dec_session = ort.InferenceSession(dec_path, providers=providers)

    # If int8 decoder_with_past doesn't exist yet, we'll skip past for now or use the full one
    if not os.path.exists(dec_past_path):
        dec_past_session = None
    else:
        dec_past_session = ort.InferenceSession(dec_past_path, providers=providers)

    # Preprocess
    prompt = f"{src_lang} {tgt_lang} {text}"
    inputs = tokenizer(prompt, return_tensors="np")
    input_ids = inputs["input_ids"]
    attention_mask = inputs["attention_mask"]

    # Encoder
    enc_outputs = enc_session.run(None, {
        "input_ids": input_ids,
        "attention_mask": attention_mask
    })
    encoder_hidden_states = enc_outputs[0]

    # Generation Loop (Greedy)
    batch_size = 1
    decoder_input_ids = np.array([[tokenizer.bos_token_id]], dtype=np.int64)

    generated_tokens = []
    past_key_values = None

    for i in range(50):
        if i == 0 or dec_past_session is None:
            # Initial step
            dec_inputs = {
                "input_ids": decoder_input_ids,
                "encoder_hidden_states": encoder_hidden_states,
                "encoder_attention_mask": attention_mask
            }
            outputs = dec_session.run(None, dec_inputs)
        else:
            # Step with past
            dec_inputs = {
                "input_ids": decoder_input_ids,
                "encoder_hidden_states": encoder_hidden_states,
                "encoder_attention_mask": attention_mask
            }
            # Map past key values
            for j, val in enumerate(past_key_values):
                dec_inputs[dec_past_session.get_inputs()[j+3].name] = val

            outputs = dec_past_session.run(None, dec_inputs)

        logits = outputs[0]
        next_token = np.argmax(logits[:, -1, :], axis=-1)[0]

        if next_token == tokenizer.eos_token_id:
            break

        generated_tokens.append(next_token)
        decoder_input_ids = np.array([[next_token]], dtype=np.int64)

        # Update past_key_values for next step
        past_key_values = outputs[1:]

    result = tokenizer.decode(generated_tokens, skip_special_tokens=True)
    return result.replace(tgt_lang, "").strip()

# Tests
test_cases = [
    ("आपका स्वागत है", "hin_Deva", "sat_Olck"),
    ("बच्चों को ध्यान से सुनो", "hin_Deva", "sat_Olck"),
    ("आपका स्वागत है", "hin_Deva", "unr_Deva"),
    ("बच्चे स्कूल जा रहे हैं", "hin_Deva", "unr_Deva")
]

print("\n--- FP32 ONNX Validation ---")
for text, src, tgt in test_cases:
    try:
        res = translate_onnx(text, src, tgt, use_int8=False)
        print(f"[{src}->{tgt}] Input: {text} | Output: {res}")
    except Exception as e:
        print(f"Error in {src}->{tgt}: {e}")

print("\n--- INT8 ONNX Validation ---")
for text, src, tgt in test_cases:
    try:
        # Note: We need to make sure we have all int8 files
        res = translate_onnx(text, src, tgt, use_int8=True)
        print(f"[{src}->{tgt}] Input: {text} | Output: {res}")
    except Exception as e:
        print(f"Error in {src}->{tgt} (INT8): {e}")
