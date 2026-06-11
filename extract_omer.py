"""Extract Omer-related decompiled code from the debug APK."""
from androguard.misc import AnalyzeAPK
import re

apk_path = r"C:\Users\yosef dahan\Documents\GitHub\AppHome\app-debug.apk"

print("Loading APK...")
a, d, dx = AnalyzeAPK(apk_path)

# Search for classes with "omer" or "Omer" in the name
keywords = ["omer", "Omer", "OmerHelper", "OmerNotif", "Sefir"]

print("\n=== Searching for Omer-related classes ===\n")

for dex in d:
    for cls in dex.get_classes():
        class_name = cls.get_name()
        # Check if class name contains any omer keyword
        if any(kw.lower() in class_name.lower() for kw in keywords):
            print(f"\n{'='*80}")
            print(f"CLASS: {class_name}")
            print(f"{'='*80}")
            src = cls.get_source()
            if src:
                print(src)
            else:
                print("(no source available)")

# Also search in all classes for omer-related string constants
print("\n\n=== Searching for Omer string references ===\n")
for dex in d:
    for s in dex.get_strings():
        s_lower = s.lower()
        if "omer" in s_lower or "עומר" in s or "ספיר" in s or "לעומר" in s:
            print(f"  STRING: {s}")
