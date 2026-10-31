package com.example.crdeck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object Ocr {
    /** Два куска экрана: строка с ником и строка с кланом (уже увеличенные). */
    class Crops(val nick: Bitmap, val clan: Bitmap)

    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

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

    /** Светлые пиксели (буквы) -> чёрные, остальное -> белое, плюс белая рамка. Так буквы с обводкой читаются лучше. */
    private fun binarize(src: Bitmap, thr: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val l = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
            px[i] = if (l > thr) Color.BLACK else Color.WHITE
        }
        val pad = 24
        val out = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        out.setPixels(px, 0, w, pad, pad, w, h)
        return out
    }

    private fun variants(c: Crops): List<Crops> = listOf(
        c,
        Crops(binarize(c.nick, 140), binarize(c.clan, 140)),
        Crops(binarize(c.nick, 190), binarize(c.clan, 190))
    )

    /** Картинка «что видит приложение» для диагностики: оригинал и чёрно-белый вариант друг под другом. */
    fun debugImage(c: Crops): Bitmap {
        val parts = listOf(c.nick, c.clan, binarize(c.nick, 140), binarize(c.clan, 140))
        val w = parts.maxOf { it.width }
        val h = parts.sumOf { it.height }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.DKGRAY)
        val cv = Canvas(out)
        var y = 0f
        for (p in parts) { cv.drawBitmap(p, 0f, y, null); y += p.height }
        return out
    }

    /** Варианты (ник, клан?), лучшие — первыми. Только латиница. */
    fun candidates(c: Crops): List<Pair<String, String?>> {
        val out = LinkedHashMap<String, Pair<String, String?>>()
        val cnt = HashMap<String, Int>()
        for (v in variants(c)) {
            val nt = latin.process(InputImage.fromBitmap(v.nick, 0))
            val ct = latin.process(InputImage.fromBitmap(v.clan, 0))
            var nick: String? = null
            var clanRaw: String? = null
            try { nick = best(linesOf(Tasks.await(nt))) } catch (_: Exception) {}
            try { clanRaw = best(linesOf(Tasks.await(ct))) } catch (_: Exception) {}
            if (nick == null || DeckFinder.norm(nick).isEmpty()) continue
            val clan = clanRaw?.takeUnless { DeckFinder.isNoClan(it) }
            val k = DeckFinder.norm(nick) + "|" + DeckFinder.norm(clan ?: "")
            cnt[k] = (cnt[k] ?: 0) + 1
            out.putIfAbsent(k, nick to clan)
        }
        return out.entries.sortedByDescending { cnt[it.key] ?: 0 }.map { it.value }.take(5)
    }
}
