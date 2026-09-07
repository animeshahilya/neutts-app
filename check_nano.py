import urllib.request
import json
try:
    req = urllib.request.urlopen("https://huggingface.co/api/models/neuphonic/neutts-nano-q4-gguf/tree/main")
    print("Success")
except Exception as e:
    print("Error:", e)
