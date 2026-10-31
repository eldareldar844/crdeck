package com.example.crdeck

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    @Volatile private var want = false
    private var web: WebView? = null
    private lateinit var status: TextView
    private lateinit var deckView: ImageView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if (data == null || root != null) { if (data == null) stopSelf(); return START_NOT_STICKY }
        setupCapture(code, data)
        buildOverlay()
        bg.execute { Ocr.warmUp() }
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.loadUrl("https://royaleapi.com/")
        web = wv
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("cr", "CR Deck", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cr")
            .setContentTitle("CR Deck работает")
            .setSmallIcon(android.R.drawable.ic_menu_search).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun setupCapture(code: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        projection = p
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        val r = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, main)
        vd = p.createVirtualDisplay("crdeck", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main)
        reader = r
    }

    @Volatile private var lastCrop: Ocr.Crops? = null
    @Volatile private var lastCropAt = 0L

    // Держим свежий кусок экрана с ником (обновляем не чаще раза в ~0.8 с), поиск берёт его сразу
    private fun onFrame(rd: ImageReader) {
        val img: Image? = try { rd.acquireLatestImage() } catch (e: Exception) { null }
        if (img == null) return
        try {
            val now = System.currentTimeMillis()
            if (now - lastCropAt >= 800 || want) {
                val c = grabCrop(img)
                lastCrop = c
                lastCropAt = now
                if (want) { want = false; process(c) }
            }
        } catch (_: Exception) {
        } finally {
            img.close()
        }
    }

    // Ник и клан противника в бою стоят слева сверху; размеры считаем от ширины экрана
    private fun grabCrop(img: Image): Ocr.Crops {
        val w = img.width
        val pl = img.planes[0]
        val rowW = pl.rowStride / pl.pixelStride
        val yEnd = (w * 0.215f).toInt().coerceAtMost(img.height)
        val b = Bitmap.createBitmap(rowW, yEnd, Bitmap.Config.ARGB_8888)
        b.copyPixelsFromBuffer(pl.buffer)
        fun part(y0f: Float, y1f: Float): Bitmap {
            val x0 = (w * 0.085f).toInt()
            val x1 = (w * 0.46f).toInt()
            val y0 = (w * y0f).toInt()
            val y1 = (w * y1f).toInt().coerceAtMost(yEnd)
            val c = Bitmap.createBitmap(b, x0, y0, x1 - x0, y1 - y0)
            return Bitmap.createScaledBitmap(c, c.width * 2, c.height * 2, true)
        }
        return Ocr.Crops(part(0.083f, 0.146f), part(0.148f, 0.204f))
    }

    private fun process(crop: Ocr.Crops) {
        val prefs = getSharedPreferences("cr", Context.MODE_PRIVATE)
        setStatus("читаю экран…")
        val t0 = System.currentTimeMillis()
        bg.execute {
            try {
                val key = prefs.getString("key", "") ?: ""
                var cands = Ocr.candidates(crop, false)
                if (cands.isEmpty()) {
                    setStatus("читаю другие языки…")
                    cands = Ocr.candidates(crop, true)
                }
                if (cands.isEmpty()) { setStatus("ник не найден"); return@execute }
                setStatus("${cands[0].first} | ${cands[0].second ?: "нет клана"}")
                val cards = try {
                    DeckFinder.find(key, cands, { url -> webSearch(url) }, { setStatus(it) })
                } catch (e: Exception) {
                    if (e.message != "игрок не найден") throw e
                    setStatus("читаю другие языки…")
                    val c2 = Ocr.candidates(crop, true)
                    if (c2.isEmpty()) throw e
                    setStatus("${c2[0].first} | ${c2[0].second ?: "нет клана"}")
                    DeckFinder.find(key, c2, { url -> webSearch(url) }, { setStatus(it) })
                }
                val deck = DeckImage.compose(cards)
                val sec = (System.currentTimeMillis() - t0) / 1000
                main.post { showDeck(deck, sec) }
            } catch (e: Exception) {
                setStatus("✖ ${e.message}")
            }
        }
    }

    private val jsLinks = "(function(){return JSON.stringify(Array.from(document.querySelectorAll('a[href]'))" +
        ".map(function(a){return [a.getAttribute('href'),(a.textContent||'').trim()]}))})()"

    // Поиск на RoyaleAPI через уже открытый скрытый WebView (защита сайта пройдена при старте)
    private fun webSearch(url: String): List<Pair<String, String>> {
        val latch = CountDownLatch(1)
        val out = ArrayList<Pair<String, String>>()
        main.post {
            val wv = web
            if (wv == null) { latch.countDown(); return@post }
            var started = false
            var tries = 0
            val tagRe = Regex("^/player/([0289PYLQGRJCUV]{3,})/?$")
            fun poll() {
                wv.evaluateJavascript(jsLinks) { v ->
                    try {
                        val arr = JSONArray(JSONArray("[$v]").getString(0))
                        val seen = HashSet<String>()
                        for (i in 0 until arr.length()) {
                            val a = arr.getJSONArray(i)
                            val m = tagRe.find(a.getString(0)) ?: continue
                            if (seen.add(m.groupValues[1])) out.add(("#" + m.groupValues[1]) to a.getString(1))
                        }
                    } catch (_: Exception) {}
                    tries++
                    if (out.isNotEmpty() || tries > 4) latch.countDown() else main.postDelayed({ poll() }, 700)
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    if (!started) { started = true; poll() }
                }
            }
            wv.loadUrl(url)
        }
        latch.await(10, TimeUnit.SECONDS)
        return ArrayList(out)
    }

    private fun setStatus(t: String) { main.post { status.text = t } }

    private fun showDeck(b: Bitmap, sec: Long) {
        val w = dp(230)
        deckView.layoutParams = LinearLayout.LayoutParams(w, w * b.height / b.width)
        deckView.setImageBitmap(b)
        deckView.visibility = View.VISIBLE
        status.text = "готово за ${sec}с, тап по колоде — скрыть"
    }

    private fun buildOverlay() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(210, 10, 14, 26))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.argb(120, 124, 92, 255))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val handle = TextView(this).apply { text = "✥"; textSize = 20f; setTextColor(Color.WHITE); setPadding(dp(6), 0, dp(10), 0) }
        val go = Button(this).apply {
            text = "🔍"; textSize = 14f; minWidth = 0; minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                val c = lastCrop
                if (c != null) {
                    process(c)
                } else {
                    want = true
                    setStatus("жду кадр…")
                    main.postDelayed({
                        if (want) { want = false; setStatus("нет кадров от экрана") }
                    }, 4000)
                }
            }
        }
        status = TextView(this).apply { text = "готов"; textSize = 10f; setTextColor(Color.WHITE); maxWidth = dp(140); setPadding(dp(6), 0, dp(6), 0) }
        val close = TextView(this).apply {
            text = "✕"; textSize = 18f; setTextColor(Color.WHITE); setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { stopSelf() }
        }
        deckView = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            setOnClickListener { visibility = View.GONE; status.text = "готов" }
        }
        row.addView(handle); row.addView(go); row.addView(status); row.addView(close)
        box.addView(row); box.addView(deckView)
        box.addView(TextView(this).apply {
            text = "eurus cr"; textSize = 8f; gravity = Gravity.END
            setTextColor(Color.argb(140, 255, 255, 255)); setPadding(0, 0, dp(6), 0)
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = dp(120) }

        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = sx + (e.rawX - tx).toInt(); lp.y = sy + (e.rawY - ty).toInt()
                        wm.updateViewLayout(box, lp)
                    }
                }
                return true
            }
        })
        wm.addView(box, lp)
        root = box
    }

    override fun onDestroy() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        try { web?.destroy() } catch (_: Exception) {}
        vd?.release(); reader?.close(); projection?.stop()
        bg.shutdown()
        super.onDestroy()
    }
}
