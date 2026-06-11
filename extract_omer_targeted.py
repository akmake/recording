"""
Targeted decompilation of Omer classes from APK.
Uses androguard but only loads specific classes instead of full analysis.
"""
from androguard.core.dex import DEX
import os, sys

apk_dir = r"C:\Users\yosef dahan\Documents\GitHub\AppHome\_apk_extract"
output_dir = r"C:\Users\yosef dahan\Documents\GitHub\AppHome\_omer_decompiled"
os.makedirs(output_dir, exist_ok=True)

# Target class name fragments
TARGETS = [
    "OmerHelper",
    "OmerMarkReceiver",
    "OmerNightReceiver",
    "OmerNusach",
    "OmerScreen",
    "OmerNusachScreen",
    "OmerViewModel",
    "OmerState",
    "OmerDayEntry",
    "OmerDayNusach",
]

# Load only the relevant dex (classes9.dex where we found the references)
dex_files = ["classes9.dex"]
# Also check other dex files just in case
for f in sorted(os.listdir(apk_dir)):
    if f.endswith('.dex') and f not in dex_files:
        dex_files.append(f)

for dex_name in dex_files:
    dex_path = os.path.join(apk_dir, dex_name)
    if not os.path.exists(dex_path):
        continue
    
    print(f"\n--- Processing {dex_name} ---")
    try:
        with open(dex_path, 'rb') as f:
            dex = DEX(f.read())
    except Exception as e:
        print(f"  Error loading: {e}")
        continue
    
    for cls in dex.get_classes():
        class_name = cls.get_name()
        # Check if class matches our targets (skip synthetic lambdas)
        if not any(t in class_name for t in TARGETS):
            continue
        if "ExternalSyntheticLambda" in class_name:
            continue
            
        # Get clean name for output file
        short_name = class_name.split('/')[-1].rstrip(';')
        print(f"\n  Found: {class_name}")
        
        try:
            source = cls.get_source()
            if source and len(source.strip()) > 10:
                out_file = os.path.join(output_dir, f"{short_name}.java")
                with open(out_file, 'w', encoding='utf-8') as f:
                    f.write(f"// Decompiled from: {class_name}\n")
                    f.write(f"// Source DEX: {dex_name}\n\n")
                    f.write(source)
                print(f"    -> Saved to {short_name}.java ({len(source)} chars)")
            else:
                print(f"    -> No meaningful source")
        except Exception as e:
            print(f"    -> Decompilation error: {e}")

print(f"\n\nDone. Check output in: {output_dir}")
