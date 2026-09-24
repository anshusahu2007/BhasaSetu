import os
import torch
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic

# Path to the source model
model_id = r"C:\Users\anshu\.cache\huggingface\hub\models--ai4bharat--indictrans2-indic-indic-dist-320M\snapshots\ffb7582b6d43791f1fb26b2153fc065f2e9ea575"
output_dir = "onnx_output"
os.makedirs(output_dir, exist_ok=True)

print("Loading model and tokenizer...")
tokenizer = AutoTokenizer.from_pretrained(model_id, trust_remote_code=True)
model = AutoModelForSeq2SeqLM.from_pretrained(model_id, trust_remote_code=True)
model.eval()

# Helper for Encoder export
class EncoderWrapper(torch.nn.Module):
    def __init__(self, encoder):
        super().__init__()
        self.encoder = encoder
    def forward(self, input_ids, attention_mask):
        outputs = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        return outputs.last_hidden_state

# Helper for Decoder export (without past)
class DecoderWrapper(torch.nn.Module):
    def __init__(self, decoder, lm_head):
        super().__init__()
        self.decoder = decoder
        self.lm_head = lm_head
    def forward(self, input_ids, encoder_hidden_states, encoder_attention_mask):
        outputs = self.decoder(
            input_ids=input_ids,
            encoder_hidden_states=encoder_hidden_states,
            encoder_attention_mask=encoder_attention_mask,
            use_cache=True
        )
        logits = self.lm_head(outputs.last_hidden_state)
        flat_pkvs = []
        for layer in outputs.past_key_values:
            for tensor in layer:
                flat_pkvs.append(tensor)
        return logits, *flat_pkvs

# Helper for Decoder export (with past)
class DecoderWithPastWrapper(torch.nn.Module):
    def __init__(self, decoder, lm_head, num_layers):
        super().__init__()
        self.decoder = decoder
        self.lm_head = lm_head
        self.num_layers = num_layers
    def forward(self, input_ids, encoder_hidden_states, encoder_attention_mask, *past_key_values):
        pkvs = []
        for i in range(self.num_layers):
            pkvs.append(past_key_values[i*4 : (i+1)*4])

        outputs = self.decoder(
            input_ids=input_ids,
            encoder_hidden_states=encoder_hidden_states,
            encoder_attention_mask=encoder_attention_mask,
            past_key_values=tuple(pkvs),
            use_cache=True
        )
        logits = self.lm_head(outputs.last_hidden_state)
        flat_pkvs = []
        for layer in outputs.past_key_values:
            for tensor in layer:
                flat_pkvs.append(tensor)
        return logits, *flat_pkvs

# Dummy inputs for tracing
batch_size = 1
src_len = 10
input_ids = torch.ones((batch_size, src_len), dtype=torch.long)
attention_mask = torch.ones((batch_size, src_len), dtype=torch.long)

print("Exporting Encoder...")
encoder_wrapper = EncoderWrapper(model.get_encoder())
torch.onnx.export(
    encoder_wrapper,
    (input_ids, attention_mask),
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

print("Exporting Decoder (initial)...")
decoder_input_ids = torch.ones((batch_size, 1), dtype=torch.long)
hidden_size = model.config.encoder_embed_dim
encoder_hidden_states = torch.randn((batch_size, src_len, hidden_size))

decoder = model.get_decoder()
lm_head = model.get_output_embeddings()
num_layers = model.config.decoder_layers

with torch.no_grad():
    outputs = decoder(
        input_ids=decoder_input_ids,
        encoder_hidden_states=encoder_hidden_states,
        encoder_attention_mask=attention_mask,
        use_cache=True
    )
    init_pkvs = outputs.past_key_values

pkv_names = []
for i in range(num_layers):
    for name in ["self_key", "self_value", "cross_key", "cross_value"]:
        pkv_names.append(f"present.{i}.{name}")

decoder_wrapper = DecoderWrapper(decoder, lm_head)
# We wrap the export to handle potential failures
try:
    torch.onnx.export(
        decoder_wrapper,
        (decoder_input_ids, encoder_hidden_states, attention_mask),
        os.path.join(output_dir, "decoder_model.onnx"),
        input_names=["input_ids", "encoder_hidden_states", "encoder_attention_mask"],
        output_names=["logits"] + pkv_names,
        dynamic_axes={
            "input_ids": {0: "batch"},
            "encoder_hidden_states": {0: "batch", 1: "src_len"},
            "encoder_attention_mask": {0: "batch", 1: "src_len"},
            "logits": {0: "batch"}
        },
        opset_version=17
    )
except Exception as e:
    print(f"Decoder initial export failed: {e}")

print("Exporting Decoder (with past)...")
flat_init_pkvs = []
for layer in init_pkvs:
    for tensor in layer:
        flat_init_pkvs.append(tensor)

past_names = []
for i in range(num_layers):
    for name in ["self_key", "self_value", "cross_key", "cross_value"]:
        past_names.append(f"past.{i}.{name}")

dynamic_axes_pkv = {}
for name in past_names:
    if "cross" in name:
        dynamic_axes_pkv[name] = {0: "batch", 2: "src_len"}
    else:
        dynamic_axes_pkv[name] = {0: "batch", 2: "past_len"}

for name in pkv_names:
    if "cross" in name:
         dynamic_axes_pkv[name] = {0: "batch", 2: "src_len"}
    else:
         dynamic_axes_pkv[name] = {0: "batch", 2: "total_len"}

decoder_with_past_wrapper = DecoderWithPastWrapper(decoder, lm_head, num_layers)

# Try using a list for inputs instead of a tuple to avoid treespec issues
inputs = (decoder_input_ids, encoder_hidden_states, attention_mask, *flat_init_pkvs)

try:
    torch.onnx.export(
        decoder_with_past_wrapper,
        inputs,
        os.path.join(output_dir, "decoder_with_past_model.onnx"),
        input_names=["input_ids", "encoder_hidden_states", "encoder_attention_mask"] + past_names,
        output_names=["logits"] + pkv_names,
        dynamic_axes={
            "input_ids": {0: "batch"},
            "encoder_hidden_states": {0: "batch", 1: "src_len"},
            "encoder_attention_mask": {0: "batch", 1: "src_len"},
            "logits": {0: "batch"},
            **dynamic_axes_pkv
        },
        opset_version=17
    )
except Exception as e:
    print(f"Decoder with past export failed: {e}")

print("Quantizing models to INT8...")
for model_name in ["encoder_model.onnx", "decoder_model.onnx", "decoder_with_past_model.onnx"]:
    path = os.path.join(output_dir, model_name)
    if not os.path.exists(path):
        continue
    quant_path = os.path.join(output_dir, model_name.replace(".onnx", "_int8.onnx"))
    print(f"Quantizing {model_name}...")
    try:
        quantize_dynamic(path, quant_path, weight_type=QuantType.QUInt8)
    except Exception as e:
        print(f"Quantization failed for {model_name}: {e}")

print("Export complete.")
