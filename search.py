import urllib.request, urllib.parse, re
req = urllib.request.Request('https://html.duckduckgo.com/html/', data=urllib.parse.urlencode({'q': 'Samsung One UI 7 Now Brief third party app notification'}).encode('utf-8'), headers={'User-Agent': 'Mozilla/5.0'})
html = urllib.request.urlopen(req).read().decode('utf-8')
print('\n---\n'.join(re.findall(r'result__snippet[^>]*>(.*?)</a>', html, flags=re.IGNORECASE|re.DOTALL)[:5]))
