import os
import torch

assets_dir = 'app/src/main/assets'
os.makedirs(assets_dir, exist_ok=True)

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
