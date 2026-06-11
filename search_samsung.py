import urllib.request
import urllib.parse
import re
import json

url = 'https://html.duckduckgo.com/html/'
data = urllib.parse.urlencode({'q': '"Now Brief" Samsung'}).encode('utf-8')
req = urllib.request.Request(url, data=data, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'})
try:
    html = urllib.request.urlopen(req).read().decode('utf-8')
    snippets = re.findall(r'<a class="result__snippet[^>]*>([^<]+)</a>', html, flags=re.IGNORECASE)
    if not snippets:
        snippets = re.findall(r'result__snippet[^>]*>(.*?)</a>', html, flags=re.IGNORECASE|re.DOTALL)
    for s in snippets[:5]:
        print(re.sub(r'<[^>]+>', '', s).strip())
except Exception as e:
    print(e)
