import sys
content = open('app/src/main/java/com/example/goodstart/ui/screen/HomeScreen.kt', 'r', encoding='utf-8').read()
old_code = """                if (!study.label.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = study.label,
                        fontSize = 13.sp,"""
new_code = """                if (!study.label.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (study.key == "tehillim") HebrewDate.formatTehillimLabel(study.label) else study.label,
                        fontSize = 13.sp,"""
if old_code in content:
    content = content.replace(old_code, new_code)
    open('app/src/main/java/com/example/goodstart/ui/screen/HomeScreen.kt', 'w', encoding='utf-8').write(content)
    print('Updated HomeScreen.kt')
else:
    print('Could not find old_code in HomeScreen.kt')
