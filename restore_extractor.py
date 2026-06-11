import re
import os

with open('../server/controllers/articleController.js', 'r', encoding='utf-8') as f:
    js_code = f.read()

kt_map_str = ""
match = re.search(r'const HEBREW_MAP = \{(.*?)\};', js_code, re.DOTALL)
if match:
    map_inner = match.group(1)
    pairs = re.findall(r'(0x[0-9a-fA-F]+)\s*:\s*\'(.)\'', map_inner)
    kt_map = '    private val hebrewMap = mapOf(\n'
    chunks = []
    for chunk in [pairs[i:i+4] for i in range(0, len(pairs), 4)]:
        chunk_str = ', '.join([f"{k} to '{v}'" for k, v in chunk])
        chunks.append('        ' + chunk_str)
    kt_map += ',\n'.join(chunks) + '\n    )'
    kt_map_str = kt_map

FILE_PATH = "app/src/main/java/com/example/goodstart/util/ChabadPdfExtractor.kt"

content = f"""package com.example.goodstart.util

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * מחלץ טקסט מ-PDF בדיוק כמו AdminPage.jsx:
 *
 *  - processTextPosition: אוסף כל התו עם מיקום X/Y/fontSize
 *  - מיון X יורד בתור שורה -> סדר RTL עברי נכון
 *  - hebrewMap + flipBrackets + reversed() per-item
 *  - fontSize < 11pt -> הסרת הערות שוליים
 *  - חיתוך 10% עליון (מעמוד 2) -> הסרת כותרות ריצה
 *  - gap > 4 -> הוספת רווח בין מילים
 *  - שחזור פסקאות עם . : ;
 *  - עיבוד ב-Batch של BATCH_SIZE עמודים -> מונע קריסה עם קבצים גדולים
 */
class ChabadPdfExtractor : PDFTextStripper() {{

{kt_map_str}

    private val RUNNING_HEADERS = setOf(
        "ספר המאמרים", "תשכ\\"ד", "ש\\"פ צו",
        "שבת הגדול", "ניסן ה'תשכ\\"ד"
    )

    // ── Internal state ────────────────────────────────────────────────────────
    private data class Item(
        val x: Float, val y: Float, val w: Float,
        val fontSize: Float, val text: String
    )

    private val pageItems   = mutableMapOf<Int, MutableList<Item>>()
    private val pageHeights = mutableMapOf<Int, Float>()

    init {{ sortByPosition = true }}

    // ── PDFTextStripper hooks ──────────────────────────────────────────────────

    override fun startPage(page: PDPage) {{
        super.startPage(page)
        // PDFTextStripper's currentPageNo is ALREADY the absolute 1-based physical page number of the document!
        val absPage = currentPageNo
        pageItems[absPage]   = mutableListOf()
        pageHeights[absPage] = page.mediaBox.height
    }}

    override fun processTextPosition(text: TextPosition) {{
        val raw = text.unicode ?: return
        if (raw.isBlank()) return

        // Fix encoding + flip brackets + reverse (visual->logical order per item)
        val fixed = raw
            .map  {{ hebrewMap[it.code] ?: it }}
            .map  {{ flipBrackets(it) }}
            .reversed()
            .joinToString("")

        if (fixed.isBlank()) return

        val absPage = currentPageNo
        pageItems[absPage]?.add(
            Item(
                x        = text.x,
                y        = text.y,
                w        = text.width,
                fontSize = text.fontSizeInPt,
                text     = fixed
            )
        )
    }}

    /** Suppress default text output — we collect everything in processTextPosition. */
    override fun writeString(text: String, textPositions: List<TextPosition>) {{ }}

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * @param onProgress  (processedPages, totalPages) called after each batch  
     */
    fun extract(
        document: PDDocument,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Pair<String, Int> {{
        val pageCount = document.numberOfPages
        val allLines  = mutableListOf<String>()

        var processed = 0
        while (processed < pageCount) {{
            val batchStart = processed + 1
            val batchEnd   = minOf(processed + BATCH_SIZE, pageCount)

            // Reset batch state
            pageItems.clear()
            pageHeights.clear()
            startPage   = batchStart
            endPage     = batchEnd

            getText(document)   // triggers startPage + processTextPosition for each page

            // Collect lines from this batch
            for (pageNum in batchStart..batchEnd) {{
                allLines += buildPageLines(pageNum)
            }}

            processed = batchEnd
            onProgress?.invoke(processed, pageCount)
        }}

        // ── Line-level filtering (AdminPage.jsx lines 149–168) ───────────────
        val cleanLines = allLines
            .map {{ it.trim() }}
            .filter {{ line ->
                if (line.isEmpty())                             return@filter false
                if (line.matches(Regex("^\\\\d{{1,4}}$")))         return@filter false
                if (RUNNING_HEADERS.any {{ line.contains(it) }}) return@filter false
                if (line.contains("__") || line.contains("--")) return@filter false
                if (line.length < 2)                            return@filter false
                true
            }}
            .map {{ it.replace(Regex("\\\\s{{2,}}"), " ") }}

        // ── Paragraph reconstruction (AdminPage.jsx lines 171–183) ───────────
        val paragraphs  = mutableListOf<String>()
        val currentPara = mutableListOf<String>()
        for (line in cleanLines) {{
            currentPara.add(line)
            if (line.endsWith('.') || line.endsWith(':') || line.endsWith(';')) {{
                paragraphs.add(currentPara.joinToString(" "))
                currentPara.clear()
            }}
        }}
        if (currentPara.isNotEmpty()) paragraphs.add(currentPara.joinToString(" "))

        return Pair(paragraphs.joinToString("\\n\\n"), pageCount)
    }}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildPageLines(pageNum: Int): List<String> {{
        val items      = pageItems[pageNum] ?: return emptyList()

        // ״למעלה להשאיר!״ -> לא נוגעים בכותרות (הורדנו את חיתוך ה-topCrop).
        // ״תעשה כמו מקודם, הגבלת גודל גופן״ -> מסננים טקסט קטן בלבד.
        val filtered = items.filter {{ item ->
            if (item.text.trim().isEmpty())                      return@filter false
            if (item.fontSize in 0.01f..10.99f)                  return@filter false  // footnotes
            true
        }}
        if (filtered.isEmpty()) return emptyList()

        // Group by y-proximity (same visual line)
        val sortedByY  = filtered.sortedBy {{ it.y }}
        val lineGroups = mutableListOf<MutableList<Item>>()
        for (item in sortedByY) {{
            val last = lineGroups.lastOrNull()
            if (last == null || kotlin.math.abs(item.y - last.first().y) > 5f)  
                lineGroups.add(mutableListOf(item))
            else
                last.add(item)
        }}

        // Build each line: sort RTL (x descending), gap -> space
        return lineGroups.map {{ group ->
            val rtl = group.sortedByDescending {{ it.x }}
            buildString {{
                var lastLeft = Float.MIN_VALUE
                for (item in rtl) {{
                    if (lastLeft != Float.MIN_VALUE) {{
                        val gap = lastLeft - (item.x + item.w)
                        // רווח עברי: מינימום 1.5f או 15% מגודל הגופן
                        val threshold = minOf(1.5f, item.fontSize * 0.15f)      
                        if (gap > threshold) append(' ')
                    }}
                    append(item.text)
                    lastLeft = item.x
                }}
            }}
        }}
    }}

    private fun flipBrackets(c: Char) = when (c) {{
        '(' -> ')'; ')' -> '('
        '[' -> ']'; ']' -> '['
        '{{' -> '}}'; '}}' -> '{{'
        '<' -> '>'; '>' -> '<'
        else -> c
    }}

    companion object {{
        // הגדלנו מ-50 ל-2500 עמודים בבת אחת
        private const val BATCH_SIZE = 2500
    }}
}}
"""

with open(FILE_PATH, 'w', encoding='utf-8') as f:
    f.write(content)

print("Restored ChabadPdfExtractor.kt via python")
