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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    @Volatile private var want = false
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

    private fun onFrame(rd: ImageReader) {
        val img: Image? = try { rd.acquireLatestImage() } catch (e: Exception) { null }
        if (img == null) return
        if (!want) { img.close(); return }
        want = false
        val bmp = try { toBitmap(img) } finally { img.close() }
        process(bmp)
    }

    private fun toBitmap(img: Image): Bitmap {
        val pl = img.planes[0]
        val rowPad = pl.rowStride - pl.pixelStride * img.width
        val b = Bitmap.createBitmap(img.width + rowPad / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        b.copyPixelsFromBuffer(pl.buffer)
        return Bitmap.createBitmap(b, 0, 0, img.width, img.height)
    }

    private fun process(bmp: Bitmap) {
        val prefs = getSharedPreferences("cr", Context.MODE_PRIVATE)
        val top = prefs.getInt("top", 0).coerceIn(0, 95)
        val bottom = prefs.getInt("bottom", 15).coerceIn(top + 3, 100)
        val y0 = bmp.height * top / 100
        val y1 = bmp.height * bottom / 100
        val crop = Bitmap.createBitmap(bmp, 0, y0, bmp.width, y1 - y0)
        setStatus("читаю экран…")
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { res ->
                val lines = res.textBlocks.flatMap { it.lines }.map { it.text.trim() }
                    .filter { it.length >= 2 && !Regex("^[\\d:\\s.,/]+$").matches(it) }
                val nick = lines.getOrNull(0)
                val clan = lines.getOrNull(1)
                if (nick == null) { setStatus("ник не найден"); return@addOnSuccessListener }
                setStatus("$nick | ${clan ?: "—"}")
                val key = prefs.getString("key", "") ?: ""
                bg.execute {
                    try {
                        val cards = DeckFinder.find(key, nick, clan)
                        val deck = DeckImage.compose(cards)
                        main.post { showDeck(deck) }
                    } catch (e: Exception) {
                        setStatus("✖ ${e.message}")
                    }
                }
            }
            .addOnFailureListener { setStatus("ошибка OCR") }
    }

    private fun setStatus(t: String) { main.post { status.text = t } }

    private fun showDeck(b: Bitmap) {
        val w = dp(230)
        deckView.layoutParams = LinearLayout.LayoutParams(w, w * b.height / b.width)
        deckView.setImageBitmap(b)
        deckView.visibility = View.VISIBLE
        status.text = "тап по колоде — скрыть"
    }

    private fun buildOverlay() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val handle = TextView(this).apply { text = "✥"; textSize = 20f; setTextColor(Color.WHITE); setPadding(dp(6), 0, dp(10), 0) }
        val go = Button(this).apply {
            text = "🔍"; textSize = 14f; minWidth = 0; minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { want = true; setStatus("жду кадр…") }
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
        vd?.release(); reader?.close(); projection?.stop()
        bg.shutdown()
        super.onDestroy()
    }
}
