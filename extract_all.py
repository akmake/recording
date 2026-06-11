"""
Extract ONLY goodstart app classes from classes9.dex (where Omer code lives)
and also scan all dex files for goodstart classes.
Uses androguard DEX parser (no full analysis needed).
"""
from androguard.core.dex import DEX
import os

apk_dir = r"C:\Users\yosef dahan\Documents\GitHub\AppHome\_apk_extract"
output_dir = r"C:\Users\yosef dahan\Documents\GitHub\AppHome\_decompiled"
os.makedirs(output_dir, exist_ok=True)

PACKAGE = "com/example/goodstart"

# Process each dex file
for dex_name in sorted(os.listdir(apk_dir)):
    if not dex_name.endswith('.dex'):
        continue
    
    dex_path = os.path.join(apk_dir, dex_name)
    print(f"\n=== {dex_name} ({os.path.getsize(dex_path):,} bytes) ===")
    
    try:
        with open(dex_path, 'rb') as f:
            dex = DEX(f.read(), using_api=None)
    except Exception as e:
        print(f"  Error: {e}")
        continue
    
    count = 0
    for cls in dex.get_classes():
        cn = cls.get_name()
        if PACKAGE not in cn:
            continue
        if "$ExternalSyntheticLambda" in cn:
            continue
            
        count += 1
        # Extract package-relative path
        # e.g. Lcom/example/goodstart/util/OmerHelper; -> util/OmerHelper
        rel = cn.replace("L" + PACKAGE + "/", "").rstrip(";")
        short = rel.split("/")[-1]
        
        # Create subdirectory structure
        sub_dir = os.path.join(output_dir, os.path.dirname(rel)) if "/" in rel else output_dir
        os.makedirs(sub_dir, exist_ok=True)
        
        try:
            source = cls.get_source()
            if source and len(source.strip()) > 20:
                out_file = os.path.join(sub_dir, f"{short}.java")
                with open(out_file, 'w', encoding='utf-8') as f:
                    f.write(f"// Decompiled from: {cn}\n")
                    f.write(f"// DEX: {dex_name}\n\n")
                    f.write(source)
                print(f"  OK: {rel} ({len(source):,} chars)")
            else:
                print(f"  EMPTY: {rel}")
        except Exception as e:
            print(f"  FAIL: {rel} -> {e}")
    
    print(f"  Total goodstart classes: {count}")

print(f"\nOutput: {output_dir}")
