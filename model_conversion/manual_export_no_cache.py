import os
import torch
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic

# Path to the source model
model_id = r"C:\Users\anshu\.cache\huggingface\hub\models--ai4bharat--indictrans2-indic-indic-dist-320M\snapshots\ffb7582b6d43791f1fb26b2153fc065f2e9ea575"
output_dir = "onnx_output_no_cache"
os.makedirs(output_dir, exist_ok=True)

print("Loading model and tokenizer...")
tokenizer = AutoTokenizer.from_pretrained(model_id, trust_remote_code=True)
model = AutoModelForSeq2SeqLM.from_pretrained(model_id, trust_remote_code=True)
model.eval()

# 1. Export Encoder
class EncoderWrapper(torch.nn.Module):
    def __init__(self, encoder):
        super().__init__()
        self.encoder = encoder
    def forward(self, input_ids, attention_mask):
        outputs = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        return outputs.last_hidden_state

print("Exporting Encoder...")
encoder_wrapper = EncoderWrapper(model.get_encoder())
dummy_input_ids = torch.ones((1, 10), dtype=torch.long)
dummy_attention_mask = torch.ones((1, 10), dtype=torch.long)

torch.onnx.export(
    encoder_wrapper,
    (dummy_input_ids, dummy_attention_mask),
    os.path.join(output_dir, "encoder_model.onnx"),
    input_names=["input_ids", "attention_mask"],
    output_names=["last_hidden_state"],
    dynamic_axes={
        "input_ids": {0: "batch", 1: "src_len"},
        "attention_mask": {0: "batch", 1: "src_len"},
        "last_hidden_state": {0: "batch", 1: "src_len"}
    },
    opset_version=17
)

# 2. Export Decoder (NO CACHE)
class DecoderNoCacheWrapper(torch.nn.Module):
    def __init__(self, decoder, lm_head):
        super().__init__()
        self.decoder = decoder
        self.lm_head = lm_head
    def forward(self, input_ids, encoder_hidden_states, encoder_attention_mask):
        outputs = self.decoder(
            input_ids=input_ids,
            encoder_hidden_states=encoder_hidden_states,
            encoder_attention_mask=encoder_attention_mask,
            use_cache=False
        )
        logits = self.lm_head(outputs.last_hidden_state)
        return logits

print("Exporting Decoder (No Cache)...")
decoder = model.get_decoder()
lm_head = model.get_output_embeddings()
decoder_wrapper = DecoderNoCacheWrapper(decoder, lm_head)

dummy_decoder_input_ids = torch.ones((1, 5), dtype=torch.long)
dummy_encoder_hidden_states = torch.randn((1, 10, model.config.encoder_embed_dim))

torch.onnx.export(
    decoder_wrapper,
    (dummy_decoder_input_ids, dummy_encoder_hidden_states, dummy_attention_mask),
    os.path.join(output_dir, "decoder_model.onnx"),
    input_names=["input_ids", "encoder_hidden_states", "encoder_attention_mask"],
    output_names=["logits"],
    dynamic_axes={
        "input_ids": {0: "batch", 1: "tgt_len"},
        "encoder_hidden_states": {0: "batch", 1: "src_len"},
        "encoder_attention_mask": {0: "batch", 1: "src_len"},
        "logits": {0: "batch", 1: "tgt_len"}
    },
    opset_version=17
)

# 3. Quantization
print("Quantizing models to INT8...")
for model_name in ["encoder_model.onnx", "decoder_model.onnx"]:
    path = os.path.join(output_dir, model_name)
    quant_path = os.path.join(output_dir, model_name.replace(".onnx", "_int8.onnx"))
    print(f"Quantizing {model_name}...")
    quantize_dynamic(path, quant_path, weight_type=QuantType.QUInt8)

print("Saving Tokenizer...")
tokenizer.save_pretrained(output_dir)

print("Export complete.")
