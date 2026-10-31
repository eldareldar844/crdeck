package com.example.crdeck

import android.graphics.*
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Card(val name: String, val icon: String)

object DeckFinder {
    // Прокси RoyaleAPI: ключ нужно создать с IP 45.79.218.79
    private const val BASE = "https://proxy.royaleapi.dev/v1"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val pool = Executors.newCachedThreadPool()

    private fun get(url: String, key: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        if (key != null) c.setRequestProperty("Authorization", "Bearer $key")
        val code = c.responseCode
        if (code != 200) throw Exception("HTTP $code")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    // Без диакритики и регистра: n ň ñ ń = n, o ò ó ô = o и т.д.
    fun norm(s: String): String {
        val d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val sb = StringBuilder()
        for (c in d) {
            when (c) {
                'ø', 'Ø' -> sb.append('o')
                'đ', 'Đ', 'ð', 'Ð' -> sb.append('d')
                'ł', 'Ł' -> sb.append('l')
                'ß' -> sb.append("ss")
                'æ', 'Æ' -> sb.append("ae")
                'œ', 'Œ' -> sb.append("oe")
                'ı' -> sb.append('i')
                else -> if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
            }
        }
        return sb.toString()
    }

    // Ещё грубее: похожие символы, которые OCR путает (i, l, 1 — одно и то же; 0 и o — тоже)
    private fun skel(s: String): String = norm(s).map {
        when (it) { 'i', '1', 'l' -> 'l'; '0' -> 'o'; else -> it }
    }.joinToString("")

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun similar(a: String, b: String): Boolean {
        val x = skel(a); val y = skel(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return lev(x, y) <= maxOf(1, y.length / 4)
    }

    // Надписи «нет клана» на разных языках (включая то, как их портит OCR)
    private val noClan = listOf(
        "неклане", "noclan", "notinaclan", "notinclan", "sinclan", "keinclan", "pasdeclan",
        "senzaclan", "semcla", "klanyok", "kjiahe", "无部落", "クランなし", "클랜없음", "ليسفيعشيرة", "بلاعشيرة"
    )

    fun isNoClan(s: String): Boolean {
        val n = norm(s)
        if (n.isEmpty()) return true
        return noClan.any { n.contains(it) || (n.length < 16 && lev(n, it) <= 2) }
    }

    private fun findInClan(key: String, nick: String, clan: String): String? {
        val items = JSONObject(get("$BASE/clans?name=${enc(clan)}&limit=5", key)).getJSONArray("items")
        val n = minOf(items.length(), 3)
        if (n == 0) return null
        val jobs = (0 until n).map { i ->
            Callable<String> {
                val tag = items.getJSONObject(i).getString("tag")
                val members = JSONObject(get("$BASE/clans/${enc(tag)}", key)).getJSONArray("memberList")
                var best: String? = null
                var bestScore = Int.MAX_VALUE
                for (m in 0 until members.length()) {
                    val mem = members.getJSONObject(m)
                    if (similar(mem.getString("name"), nick)) {
                        val sc = lev(skel(mem.getString("name")), skel(nick))
                        if (sc < bestScore) { bestScore = sc; best = mem.getString("tag") }
                    }
                }
                best ?: throw Exception("no")
            }
        }
        return try { pool.invokeAny(jobs, 12, TimeUnit.SECONDS) } catch (e: Exception) { null }
    }

    private fun playerName(key: String, tag: String): String? =
        try { JSONObject(get("$BASE/players/${enc(tag)}", key)).getString("name") } catch (e: Exception) { null }

    private fun lastBattle(key: String, tag: String): String =
        try { JSONArray(get("$BASE/players/${enc(tag)}/battlelog", key)).getJSONObject(0).getString("battleTime") }
        catch (e: Exception) { "" }

    // Поиск без клана: поиск по имени на сайте RoyaleAPI (через WebView, его даёт сервис)
    private fun findGlobal(key: String, nick: String, site: (String) -> List<Pair<String, String>>): String? {
        val urls = listOf("https://royaleapi.com/player/${Uri.encode(nick)}")
        for (u in urls) {
            val res = site(u)
            if (res.isEmpty()) continue
            val pre = res.filter { skel(it.second).contains(skel(nick)) }.ifEmpty { res.take(5) }.take(8)
            val ok = ArrayList<Pair<String, Int>>()
            for ((tag, _) in pre) {
                val name = playerName(key, tag) ?: continue
                if (similar(name, nick)) ok.add(tag to lev(skel(name), skel(nick)))
            }
            if (ok.isEmpty()) continue
            val min = ok.minOf { it.second }
            val best = ok.filter { it.second == min }.take(4)
            if (best.size == 1) return best[0].first
            // несколько тёзок — берём того, кто играл позже всех
            return best.maxByOrNull { lastBattle(key, it.first) }?.first
        }
        return null
    }

    fun find(
        key: String,
        cands: List<Pair<String, String?>>,
        site: (String) -> List<Pair<String, String>>,
        status: (String) -> Unit
    ): List<Card> {
        if (key.isBlank()) throw Exception("нет API ключа")
        var tag: String? = null
        val withClan = cands.filter { !it.second.isNullOrBlank() }.take(3)
        if (withClan.isNotEmpty()) {
            status("ищу в клане…")
            val jobs = withClan.map { c ->
                Callable<String> { findInClan(key, c.first, c.second!!) ?: throw Exception("no") }
            }
            tag = try { pool.invokeAny(jobs, 15, TimeUnit.SECONDS) } catch (e: Exception) { null }
        }
        if (tag == null) {
            status("ищу на сайте…")
            for (c in cands.distinctBy { norm(it.first) }.take(1)) {
                tag = try { findGlobal(key, c.first, site) } catch (e: Exception) { null }
                if (tag != null) break
            }
        }
        if (tag == null) throw Exception("игрок не найден")

        val log = JSONArray(get("$BASE/players/${enc(tag)}/battlelog", key))
        if (log.length() == 0) throw Exception("нет боёв")
        val battle = log.getJSONObject(0)
        for (side in listOf("team", "opponent")) {
            val arr = battle.optJSONArray(side) ?: continue
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                if (p.getString("tag") == tag) {
                    val cards = p.getJSONArray("cards")
                    return (0 until cards.length()).map {
                        val c = cards.getJSONObject(it)
                        Card(c.getString("name"), c.getJSONObject("iconUrls").getString("medium"))
                    }
                }
            }
        }
        throw Exception("колода не найдена")
    }
}

object DeckImage {
    fun compose(cards: List<Card>): Bitmap {
        val pool = Executors.newFixedThreadPool(8)
        val icons = pool.invokeAll(cards.map { c ->
            Callable<Bitmap?> {
                try {
                    URL(c.icon).openStream().use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) { null }
            }
        }).map { it.get() }
        pool.shutdown()

        val cw = 160; val ch = 192; val cols = 4
        val rows = (cards.size + cols - 1) / cols
        val bmp = Bitmap.createBitmap(cw * cols, ch * rows, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.rgb(25, 30, 45))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f; textAlign = Paint.Align.CENTER }
        icons.forEachIndexed { i, ic ->
            val x = (i % cols) * cw
            val y = (i / cols) * ch
            if (ic != null) cv.drawBitmap(ic, null, Rect(x + 4, y + 4, x + cw - 4, y + ch - 4), paint)
            else cv.drawText(cards[i].name, x + cw / 2f, y + ch / 2f, text)
        }
        return bmp
    }
}
