from huggingface_hub import hf_hub_download
import os
import torch

assets_dir = 'app/src/main/assets'
os.makedirs(assets_dir, exist_ok=True)

print("Downloading gguf...")
gguf_path = hf_hub_download(repo_id="neuphonic/neutts-2e-q4-gguf", filename="neutts-2e-Q4_0.gguf", local_dir=assets_dir)
print(f"Downloaded gguf to {gguf_path}")

print("Downloading onnx...")
onnx_path = hf_hub_download(repo_id="neuphonic/neucodec-onnx-decoder-int8", filename="model.onnx", local_dir=assets_dir)
print(f"Downloaded onnx to {onnx_path}")

print("Extracting emily.pt...")
pt_path = r'C:\Users\alex\.gemini\antigravity\scratch\neutts\samples\emily.pt'
codes = torch.load(pt_path, map_location='cpu', weights_only=True)
with open(os.path.join(assets_dir, 'emily.codes'), 'w') as f:
    f.write(','.join(map(str, codes.flatten().tolist())))

txt_path = r'C:\Users\alex\.gemini\antigravity\scratch\neutts\samples\emily.txt'
with open(txt_path, 'r') as f:
    text = f.read()
with open(os.path.join(assets_dir, 'emily.txt'), 'w') as f:
    f.write(text)

print("Done.")
