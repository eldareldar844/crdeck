package com.example.crdeck

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object Ocr {
    // Быстрый распознаватель: латиница
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    // Медленные: китайский, японский, корейский, деванагари (только если быстрый не помог)
    private val others by lazy {
        listOf(
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        )
    }

    private val skip = Regex("^[\\d:\\s.,/+\\-]+$")

    private fun clean(lines: List<String>) = lines.map { it.trim() }
        .filter { it.length >= 2 && !skip.matches(it) && it.any { c -> c.isLetter() } }

    /** Прогрев быстрого распознавателя при запуске. */
    fun warmUp() {
        try {
            val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            Tasks.await(latin.process(InputImage.fromBitmap(b, 0)))
        } catch (_: Exception) {}
    }

    /** Варианты (ник, клан?). extra=false — только быстрый (латиница), true — остальные языки. */
    fun candidates(bmp: Bitmap, extra: Boolean): List<Pair<String, String?>> {
        val img = InputImage.fromBitmap(bmp, 0)
        val recs = if (extra) others else listOf(latin)
        val tasks = recs.map { it.process(img) }
        val sets = ArrayList<List<String>>()
        for (t in tasks) {
            try {
                val r = Tasks.await(t)
                sets.add(clean(r.textBlocks.flatMap { b -> b.lines }.map { l -> l.text }))
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
        return out.entries.sortedByDescending { cnt[it.key] ?: 0 }.map { it.value }.take(5)
    }
}
