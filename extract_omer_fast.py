"""Quick extraction: unzip APK, list classes, find omer strings using dexparser."""
import zipfile
import os
import re

apk_path = r"C:\Users\yosef dahan\Documents\GitHub\AppHome\app-debug.apk"
extract_dir = r"C:\Users\yosef dahan\Documents\GitHub\AppHome\_apk_extract"

# Extract APK as ZIP
os.makedirs(extract_dir, exist_ok=True)
with zipfile.ZipFile(apk_path, 'r') as z:
    z.extractall(extract_dir)

print("APK extracted. Contents:")
for f in os.listdir(extract_dir):
    size = os.path.getsize(os.path.join(extract_dir, f))
    print(f"  {f} ({size:,} bytes)")

# Search for omer-related strings in all dex files
print("\n=== Searching DEX files for Omer strings ===\n")
for fname in os.listdir(extract_dir):
    if fname.endswith('.dex'):
        dex_path = os.path.join(extract_dir, fname)
        with open(dex_path, 'rb') as f:
            data = f.read()
        
        # Search for UTF-8 strings containing omer/Omer
        # DEX strings are stored as MUTF-8
        for pattern in [b'omer', b'Omer', b'OMER', b'\xd7\xa2\xd7\x95\xd7\x9e\xd7\xa8',  # עומר
                        b'\xd7\xa1\xd7\xa4\xd7\x99\xd7\xa8', # ספיר
                        b'\xd7\x9c\xd7\xa2\xd7\x95\xd7\x9e\xd7\xa8']:  # לעומר
            idx = 0
            while True:
                idx = data.find(pattern, idx)
                if idx == -1:
                    break
                # Extract surrounding context (100 bytes around)
                start = max(0, idx - 60)
                end = min(len(data), idx + 100)
                context = data[start:end]
                # Try to decode readable parts
                try:
                    text = context.decode('utf-8', errors='replace')
                    # Clean up non-printable chars
                    text_clean = re.sub(r'[^\x20-\x7e\u0590-\u05ff\u200e\u200f\u202a-\u202e]', '.', text)
                    print(f"  [{fname} @ 0x{idx:x}] ...{text_clean}...")
                except:
                    pass
                idx += 1

# Also search for class names containing Omer
print("\n=== Searching for Omer class references ===\n")
for fname in os.listdir(extract_dir):
    if fname.endswith('.dex'):
        dex_path = os.path.join(extract_dir, fname)
        with open(dex_path, 'rb') as f:
            data = f.read()
        for pattern in [b'OmerHelper', b'OmerNotif', b'OmerCount', b'OmerTrack', b'SefiraCount']:
            idx = 0
            while True:
                idx = data.find(pattern, idx)
                if idx == -1:
                    break
                start = max(0, idx - 40)
                end = min(len(data), idx + 60)
                context = data[start:end]
                try:
                    text = context.decode('utf-8', errors='replace')
                    text_clean = re.sub(r'[^\x20-\x7e\u0590-\u05ff/;]', '.', text)
                    print(f"  [{fname} @ 0x{idx:x}] {text_clean}")
                except:
                    pass
                idx += 1

print("\nDone.")
