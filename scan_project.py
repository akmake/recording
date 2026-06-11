import os
import tkinter as tk
from tkinter import filedialog, messagebox
from datetime import datetime

# עומק מקסימלי לסריקה (אנדרואיד דורש עומק גדול יותר בגלל מבנה החבילות)
MAX_DEPTH = 12

# פריטים שמותר לסרוק ברמה העליונה של הפרויקט
ALLOWED_ROOT_ITEMS = {"app", "gradle", "build.gradle", "settings.gradle", "gradle.properties", "README.md"}

# תיקיות שיש לדלג עליהן (קבצי build וזבל של ה-IDE)
SKIP_DIRS = {
    "node_modules", ".git", "__pycache__", "build", ".gradle",
    ".idea", "bin", "out", "captures", ".externalNativeBuild", "release"
}

# קבצים שיש לדלג עליהם
SKIP_FILES = {
    "gradlew", "gradlew.bat", "local.properties", "google-services.json",
    ".DS_Store", "desktop.ini", "output-metadata.json"
}

# סיומות של קבצים שאת התוכן שלהם נרצה לקרוא (התאמה לאנדרואיד)
PRINT_CONTENT_EXTENSIONS = {".java", ".kt", ".xml", ".gradle", ".md", ".json", ".properties", ".txt"}


def scan_directory(path, indent=0, output_lines=None, current_depth=0, print_content=False):
    if output_lines is None:
        output_lines = []

    if current_depth > MAX_DEPTH:
        output_lines.append("  " * indent + "🔽 ... (העומק הגיע למקסימום)")
        return output_lines

    try:
        items = sorted(os.listdir(path))
    except Exception as e:
        output_lines.append("  " * indent + f"[שגיאה בגישה]: {e}")
        return output_lines

    for item in items:
        full_path = os.path.join(path, item)

        # רמה 0: סינון פריטים שלא קשורים ישירות לקוד המקור של הפרויקט
        if indent == 0 and item not in ALLOWED_ROOT_ITEMS:
            continue

        # דילוג על תיקיות לא רלוונטיות
        if os.path.isdir(full_path) and item in SKIP_DIRS:
            continue

        # דילוג על קבצים לא רלוונטיים
        if not os.path.isdir(full_path) and item in SKIP_FILES:
            continue

        if os.path.isdir(full_path):
            output_lines.append("  " * indent + f"📁 {item}/")
            scan_directory(full_path, indent + 1, output_lines, current_depth + 1, print_content)
        else:
            ext = os.path.splitext(item)[1].lower()
            if ext not in PRINT_CONTENT_EXTENSIONS:
                continue

            output_lines.append("  " * indent + f"📄 {item}")

            if print_content:
                try:
                    with open(full_path, "r", encoding="utf-8", errors="ignore") as f:
                        output_lines.append("  " * (indent + 1) + "--- START CONTENT ---")
                        for line in f:
                            output_lines.append("  " * (indent + 1) + line.rstrip())
                        output_lines.append("  " * (indent + 1) + "--- END CONTENT ---")
                except Exception as e:
                    output_lines.append("  " * (indent + 1) + f"[שגיאה בקריאה: {e}]")

    return output_lines


def ask_include_content():
    root = tk.Tk()
    root.withdraw()
    result = messagebox.askyesno(
        "האם לכלול תוכן קבצים?",
        "האם לכלול את תוכן קבצי הקוד (Java, Kotlin, XML) בתוך קובץ הטקסט שייווצר?"
    )
    root.destroy()
    return result


def main():
    root = tk.Tk()
    root.withdraw()

    # נתיב ברירת מחדל לפרויקט שלך
    initial_dir = r"C:\Users\yosef\Documents\GitHub\AppHome"
    folder_path = filedialog.askdirectory(title="בחר תיקיית פרויקט לסריקה", initialdir=initial_dir)

    if not folder_path:
        print("❌ לא נבחרה תיקייה.")
        return

    include_content = ask_include_content()
    print(f"\n📂 סורק את הפרויקט בנתיב: {folder_path}")

    structure_lines = scan_directory(folder_path, print_content=include_content)

    print("\n📋 הסריקה הסתיימה. שומר תוצאות...")

    timestamp = datetime.now().strftime("%d%m%y%H%M")
    filename = f"GoodStart_Scan_{timestamp}.txt"

    # נתיבי שמירה
    desktop_path = os.path.join(os.path.expanduser("~"), "Desktop")
    custom_path = r"G:\האחסון שלי\update"

    output_file_desktop = os.path.join(desktop_path, filename)

    paths_to_save = [output_file_desktop]
    if os.path.exists(custom_path):
        paths_to_save.append(os.path.join(custom_path, filename))

    try:
        content_to_write = "\n".join(structure_lines)
        for p in paths_to_save:
            with open(p, "w", encoding="utf-8") as f:
                f.write(content_to_write)
            print(f"✅ הקובץ נשמר בהצלחה בנתיב: {p}")

        messagebox.showinfo("סיום בהצלחה", f"הסריקה הסתיימה.\nהקובץ נשמר ב-{len(paths_to_save)} מיקומים.")

    except Exception as e:
        print(f"\n❌ שגיאה בשמירה: {e}")
        messagebox.showerror("שגיאה בשמירה", f"לא הצלחתי לשמור את הקובץ:\n{e}")


if __name__ == "__main__":
    main()
