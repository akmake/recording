"""
Extract all Omer-related strings from the APK DEX files.
Since we can't decompile properly, extract ALL readable strings containing omer/Omer references
to understand the full data structure and logic.
"""
import os
import re
import struct

apk_dir = r"C:\Users\yosef dahan\Documents\GitHub\AppHome\_apk_extract"

def extract_strings_from_dex(dex_path):
    """Extract all string constants from a DEX file."""
    with open(dex_path, 'rb') as f:
        data = f.read()
    
    if data[:4] != b'dex\n':
        return []
    
    # Parse DEX header
    string_ids_size = struct.unpack_from('<I', data, 56)[0]
    string_ids_off = struct.unpack_from('<I', data, 60)[0]
    
    strings = []
    for i in range(string_ids_size):
        offset = struct.unpack_from('<I', data, string_ids_off + i * 4)[0]
        # Read ULEB128 length
        pos = offset
        length = 0
        shift = 0
        while True:
            b = data[pos]
            length |= (b & 0x7f) << shift
            pos += 1
            if (b & 0x80) == 0:
                break
            shift += 7
        # Read MUTF-8 string
        end = data.index(0, pos)
        try:
            s = data[pos:end].decode('utf-8', errors='replace')
            strings.append(s)
        except:
            pass
    
    return strings

# Process all DEX files
all_strings = []
for fname in sorted(os.listdir(apk_dir)):
    if fname.endswith('.dex'):
        print(f"Processing {fname}...")
        strings = extract_strings_from_dex(os.path.join(apk_dir, fname))
        all_strings.extend(strings)

# Filter for Omer-related strings
print(f"\nTotal strings: {len(all_strings)}")
print(f"\n{'='*80}")
print("OMER-RELATED STRINGS")
print(f"{'='*80}\n")

omer_strings = []
for s in all_strings:
    s_lower = s.lower()
    if any(kw in s_lower for kw in ['omer', 'sefirat', 'sefira']) or \
       any(kw in s for kw in ['עומר', 'ספירת', 'ספירה', 'לעומר']):
        omer_strings.append(s)

# Sort and deduplicate
omer_strings = sorted(set(omer_strings))
for s in omer_strings:
    print(f"  {s}")

print(f"\n\nTotal Omer strings: {len(omer_strings)}")
