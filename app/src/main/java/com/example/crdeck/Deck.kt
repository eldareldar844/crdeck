package com.example.crdeck

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class Card(val name: String, val icon: String)

object DeckFinder {
    // Прокси RoyaleAPI: ключ нужно создать с IP 45.79.218.79
    private const val BASE = "https://proxy.royaleapi.dev/v1"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, key: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        if (key != null) c.setRequestProperty("Authorization", "Bearer $key")
        val code = c.responseCode
        if (code != 200) throw Exception("HTTP $code")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

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
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return lev(x, y) <= maxOf(1, y.length / 4)
    }

    private fun findInClan(key: String, nick: String, clan: String): String? {
        val items = JSONObject(get("$BASE/clans?name=${enc(clan)}&limit=5", key)).getJSONArray("items")
        for (i in 0 until items.length()) {
            val tag = items.getJSONObject(i).getString("tag")
            val members = JSONObject(get("$BASE/clans/${enc(tag)}", key)).getJSONArray("memberList")
            var best: String? = null
            var bestScore = Int.MAX_VALUE
            for (m in 0 until members.length()) {
                val mem = members.getJSONObject(m)
                if (similar(mem.getString("name"), nick)) {
                    val s = lev(norm(mem.getString("name")), norm(nick))
                    if (s < bestScore) { bestScore = s; best = mem.getString("tag") }
                }
            }
            if (best != null) return best
        }
        return null
    }

    // Запасной вариант без клана: поиск на сайте RoyaleAPI (может блокироваться Cloudflare)
    private fun findBySite(key: String, nick: String): String? {
        val html = get("https://royaleapi.com/players/search?q=${enc(nick)}", null)
        val tags = Regex("/player/([0-9A-Z]{4,})").findAll(html).map { it.groupValues[1] }.distinct().take(6).toList()
        for (t in tags) {
            try {
                val name = JSONObject(get("$BASE/players/${enc("#$t")}", key)).getString("name")
                if (similar(name, nick)) return "#$t"
            } catch (_: Exception) {}
        }
        return null
    }

    fun find(key: String, nick: String, clan: String?): List<Card> {
        if (key.isBlank()) throw Exception("нет API ключа")
        var tag: String? = null
        if (!clan.isNullOrBlank()) tag = try { findInClan(key, nick, clan) } catch (e: Exception) { null }
        if (tag == null) tag = try { findBySite(key, nick) } catch (e: Exception) { null }
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
