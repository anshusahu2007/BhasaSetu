import onnxruntime as ort
import numpy as np
from transformers import AutoTokenizer
import os

model_dir = "onnx_output_no_cache"
tokenizer_id = r"C:\Users\anshu\.cache\huggingface\hub\models--ai4bharat--indictrans2-indic-indic-dist-320M\snapshots\ffb7582b6d43791f1fb26b2153fc065f2e9ea575"

print("Loading tokenizer...")
tokenizer = AutoTokenizer.from_pretrained(tokenizer_id, trust_remote_code=True)

def translate_onnx(text, src_lang, tgt_lang, use_int8=False):
    suffix = "_int8" if use_int8 else ""

    enc_path = os.path.join(model_dir, f"encoder_model{suffix}.onnx")
    dec_path = os.path.join(model_dir, f"decoder_model{suffix}.onnx")

    providers = ['CPUExecutionProvider']
    # Use session options to optimize
    sess_options = ort.SessionOptions()
    sess_options.intra_op_num_threads = 4

    enc_session = ort.InferenceSession(enc_path, sess_options, providers=providers)
    dec_session = ort.InferenceSession(dec_path, sess_options, providers=providers)

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

    # Generation Loop (Greedy, NO CACHE)
    decoder_input_ids = np.array([[2]], dtype=np.int64) # Start with EOS as seen in trace

    generated_tokens = []

    for i in range(128):
        dec_inputs = {
            "input_ids": decoder_input_ids,
            "encoder_hidden_states": encoder_hidden_states,
            "encoder_attention_mask": attention_mask
        }
        outputs = dec_session.run(None, dec_inputs)
        logits = outputs[0]

        # Logits: [batch, seq, vocab]
        next_token = np.argmax(logits[0, -1, :]).item()

        # Stop on EOS (2), but ignore first token if it matches
        if next_token == 2:
            break

        generated_tokens.append(next_token)
        decoder_input_ids = np.concatenate([decoder_input_ids, [[next_token]]], axis=1)

    result = tokenizer.decode(generated_tokens, skip_special_tokens=True)
    return result.strip()

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
        print(f"[{src}->{tgt}] In: {text} | Out: {res}")
    except Exception as e:
        print(f"Error in {src}->{tgt}: {e}")

print("\n--- INT8 ONNX Validation ---")
for text, src, tgt in test_cases:
    try:
        res = translate_onnx(text, src, tgt, use_int8=True)
        print(f"[{src}->{tgt}] In: {text} | Out: {res}")
    except Exception as e:
        print(f"Error in {src}->{tgt} (INT8): {e}")
