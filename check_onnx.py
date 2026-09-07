import urllib.request
import json
req = urllib.request.urlopen("https://search.maven.org/solrsearch/select?q=g:com.microsoft.onnxruntime+AND+a:onnxruntime-android&core=gav&rows=1&wt=json")
print(json.loads(req.read().decode())['response']['docs'][0]['v'])
