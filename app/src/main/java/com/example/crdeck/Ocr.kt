package com.example.crdeck

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object Ocr {
    /** Два куска экрана: строка с ником и строка с кланом (уже увеличенные). */
    class Crops(val nick: Bitmap, val clan: Bitmap)

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

    private fun linesOf(t: Text): List<String> =
        t.textBlocks.flatMap { b -> b.lines }.map { l -> l.text.trim() }

    // из всех прочитанных строк берём самую «содержательную»
    private fun best(lines: List<String>): String? =
        lines.filter { l -> l.any { it.isLetterOrDigit() } }
            .maxByOrNull { l -> l.count { it.isLetterOrDigit() } }

    fun warmUp() {
        try {
            val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            Tasks.await(latin.process(InputImage.fromBitmap(b, 0)))
        } catch (_: Exception) {}
    }

    /** Варианты (ник, клан?). extra=false — быстрый (латиница), true — остальные языки. */
    fun candidates(c: Crops, extra: Boolean): List<Pair<String, String?>> {
        val recs = if (extra) others else listOf(latin)
        val ni = InputImage.fromBitmap(c.nick, 0)
        val ci = InputImage.fromBitmap(c.clan, 0)
        val nt = recs.map { it.process(ni) }
        val ct = recs.map { it.process(ci) }
        val out = LinkedHashMap<String, Pair<String, String?>>()
        val cnt = HashMap<String, Int>()
        for (i in recs.indices) {
            var nick: String? = null
            var clanRaw: String? = null
            try { nick = best(linesOf(Tasks.await(nt[i]))) } catch (_: Exception) {}
            try { clanRaw = best(linesOf(Tasks.await(ct[i]))) } catch (_: Exception) {}
            if (nick == null || DeckFinder.norm(nick).isEmpty()) continue
            val clan = clanRaw?.takeUnless { DeckFinder.isNoClan(it) }
            val k = DeckFinder.norm(nick) + "|" + DeckFinder.norm(clan ?: "")
            cnt[k] = (cnt[k] ?: 0) + 1
            out.putIfAbsent(k, nick to clan)
        }
        return out.entries.sortedByDescending { cnt[it.key] ?: 0 }.map { it.value }.take(5)
    }
}
