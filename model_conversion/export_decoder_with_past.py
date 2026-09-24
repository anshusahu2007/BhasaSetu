import os
import torch
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic

# Path to the source model
model_id = r"C:\Users\anshu\.cache\huggingface\hub\models--ai4bharat--indictrans2-indic-indic-dist-320M\snapshots\ffb7582b6d43791f1fb26b2153fc065f2e9ea575"
output_dir = "onnx_output"

print("Loading model and tokenizer...")
tokenizer = AutoTokenizer.from_pretrained(model_id, trust_remote_code=True)
model = AutoModelForSeq2SeqLM.from_pretrained(model_id, trust_remote_code=True)
model.eval()

class DecoderWithPastWrapper(torch.nn.Module):
    def __init__(self, decoder, lm_head, num_layers):
        super().__init__()
        self.decoder = decoder
        self.lm_head = lm_head
        self.num_layers = num_layers
    def forward(self, input_ids, encoder_hidden_states, encoder_attention_mask, *past_key_values):
        # Reconstruct past_key_values
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
        # Flatten pkvs
        flat_pkvs = []
        for layer in outputs.past_key_values:
            for tensor in layer:
                flat_pkvs.append(tensor)
        return logits, *flat_pkvs

print("Exporting Decoder With Past...")
decoder = model.get_decoder()
lm_head = model.get_output_embeddings()
num_layers = model.config.decoder_layers

dummy_decoder_input_ids = torch.ones((1, 1), dtype=torch.long)
dummy_encoder_hidden_states = torch.randn((1, 10, model.config.encoder_embed_dim))
dummy_attention_mask = torch.ones((1, 10), dtype=torch.long)

# Run once to get dummy pasts
with torch.no_grad():
    outputs = decoder(
        input_ids=dummy_decoder_input_ids,
        encoder_hidden_states=dummy_encoder_hidden_states,
        encoder_attention_mask=dummy_attention_mask,
        use_cache=True
    )
    init_pkvs = outputs.past_key_values

flat_init_pkvs = []
for layer in init_pkvs:
    for tensor in layer:
        flat_init_pkvs.append(tensor)

past_names = [f"past.{i}.{name}" for i in range(num_layers) for name in ["self_key", "self_value", "cross_key", "cross_value"]]
present_names = [f"present.{i}.{name}" for i in range(num_layers) for name in ["self_key", "self_value", "cross_key", "cross_value"]]

dynamic_axes_dec = {
    "input_ids": {0: "batch"},
    "encoder_hidden_states": {0: "batch", 1: "src_len"},
    "encoder_attention_mask": {0: "batch", 1: "src_len"},
    "logits": {0: "batch"}
}
for name in past_names:
    if "cross" in name:
        dynamic_axes_dec[name] = {0: "batch", 2: "src_len"}
    else:
        dynamic_axes_dec[name] = {0: "batch", 2: "past_len"}
for name in present_names:
    if "cross" in name:
        dynamic_axes_dec[name] = {0: "batch", 2: "src_len"}
    else:
        dynamic_axes_dec[name] = {0: "batch", 2: "total_len"}

wrapper = DecoderWithPastWrapper(decoder, lm_head, num_layers)

# To avoid the treespec error, we provide the inputs as a single flat tuple matching the signature
inputs = (dummy_decoder_input_ids, dummy_encoder_hidden_states, dummy_attention_mask, *flat_init_pkvs)

torch.onnx.export(
    wrapper,
    inputs,
    os.path.join(output_dir, "decoder_with_past_model.onnx"),
    input_names=["input_ids", "encoder_hidden_states", "encoder_attention_mask"] + past_names,
    output_names=["logits"] + present_names,
    dynamic_axes=dynamic_axes_dec,
    opset_version=17
)

print("Quantizing decoder_with_past...")
quantize_dynamic(
    os.path.join(output_dir, "decoder_with_past_model.onnx"),
    os.path.join(output_dir, "decoder_with_past_model_int8.onnx"),
    weight_type=QuantType.QUInt8
)

print("Decoder With Past Export complete.")
