package com.example.crdeck

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.net.URL

object Ocr {
    // ML Kit: латиница, китайский, японский, корейский, деванагари.
    // Tesseract: русский, арабский, английский (ML Kit их не умеет).
    private val recs by lazy {
        listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        )
    }

    private val skip = Regex("^[\\d:\\s.,/+\\-]+$")

    private fun clean(lines: List<String>) = lines.map { it.trim() }
        .filter { it.length >= 2 && !skip.matches(it) && it.any { c -> c.isLetter() } }


    /** Возвращает варианты (ник, клан?) от разных распознавателей, лучшие — первыми. */
    fun candidates(ctx: Context, bmp: Bitmap, progress: (String) -> Unit): List<Pair<String, String?>> {
        val sets = ArrayList<List<String>>()
        progress("читаю экран…")
        for (r in recs) {
            try {
                val t = Tasks.await(r.process(InputImage.fromBitmap(bmp, 0)))
                sets.add(clean(t.textBlocks.flatMap { b -> b.lines }.map { l -> l.text }))
            } catch (_: Exception) {}
        }

        val out = LinkedHashMap<String, Pair<String, String?>>()
        val cnt = HashMap<String, Int>()
        for (ls in sets) {
            val nick = ls.getOrNull(0) ?: continue
            if (DeckFinder.norm(nick).isEmpty()) continue
            val clan = ls.getOrNull(1)?.takeUnless { DeckFinder.isNoClan(it) }
            val k = DeckFinder.norm(nick) + "|" + DeckFinder.norm(clan ?: "")
            cnt[k] = (cnt[k] ?: 0) + 1
            out.putIfAbsent(k, nick to clan)
        }
        return out.entries.sortedByDescending { cnt[it.key] ?: 0 }.map { it.value }.take(6)
    }
}
