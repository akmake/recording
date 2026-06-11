"""
Approach: Load ALL dex files together so cross-references work,
then decompile only goodstart classes.
"""
from androguard.misc import AnalyzeAPK
import os, sys

apk_path = r"C:\Users\yosef dahan\Documents\GitHub\AppHome\app-debug.apk"
output_dir = r"C:\Users\yosef dahan\Documents\GitHub\AppHome\_decompiled2"
os.makedirs(output_dir, exist_ok=True)

PACKAGE = "com/example/goodstart"

print("Loading APK (this takes a few minutes)...")
sys.stdout.flush()
a, d_list, dx = AnalyzeAPK(apk_path)
print(f"Loaded {len(d_list)} DEX files. Extracting goodstart classes...")
sys.stdout.flush()

total = 0
ok = 0
fail = 0

for dex in d_list:
    for cls in dex.get_classes():
        cn = cls.get_name()
        if PACKAGE not in cn:
            continue
        if "$ExternalSyntheticLambda" in cn:
            continue
        # Skip inner lambda classes for cleaner output
        if "$lambda$" in cn and "$$inlined$" in cn:
            continue
            
        total += 1
        rel = cn.replace("L" + PACKAGE + "/", "").rstrip(";")
        short = rel.split("/")[-1]
        
        sub_dir = os.path.join(output_dir, os.path.dirname(rel)) if "/" in rel else output_dir
        os.makedirs(sub_dir, exist_ok=True)
        
        try:
            source = cls.get_source()
            if source and len(source.strip()) > 20:
                out_file = os.path.join(sub_dir, f"{short}.java")
                with open(out_file, 'w', encoding='utf-8') as f:
                    f.write(f"// Decompiled from: {cn}\n\n")
                    f.write(source)
                ok += 1
                if "Omer" in cn or "omer" in cn:
                    print(f"  ** OMER: {rel} ({len(source):,} chars)")
                elif ok % 20 == 0:
                    print(f"  Progress: {ok}/{total}...")
                sys.stdout.flush()
            else:
                fail += 1
        except Exception as e:
            fail += 1

print(f"\nDone! OK={ok}, FAIL={fail}, TOTAL={total}")
print(f"Output: {output_dir}")
