import os
import torch
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, AutoConfig
from optimum.exporters.onnx import main_export
import logging

# Path to the source model
model_id = r"C:\Users\anshu\.cache\huggingface\hub\models--ai4bharat--indictrans2-indic-indic-dist-320M\snapshots\ffb7582b6d43791f1fb26b2153fc065f2e9ea575"
output_dir = "onnx_output"

print("Loading model and tokenizer...")
# Patch config
config = AutoConfig.from_pretrained(model_id, trust_remote_code=True)
config.model_type = "m2m_100"
# Add missing attribute for optimum
config.hidden_size = config.encoder_embed_dim

model = AutoModelForSeq2SeqLM.from_pretrained(model_id, config=config, trust_remote_code=True)
tokenizer = AutoTokenizer.from_pretrained(model_id, trust_remote_code=True)

print("Exporting with Optimum...")
try:
    main_export(
        model_name_or_path=model_id,
        output=output_dir,
        task="text2text-generation-with-past",
        trust_remote_code=True,
        # We pass the model object directly if possible, or hope optimum uses the patched config
    )
except Exception as e:
    print(f"Optimum export failed: {e}")

print("Export complete.")
