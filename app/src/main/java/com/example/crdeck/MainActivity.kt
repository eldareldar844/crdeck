package com.example.crdeck

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var keyEt: EditText
    private lateinit var topEt: EditText
    private lateinit var botEt: EditText
    private lateinit var permTv: TextView

    private val cBg1 = Color.parseColor("#0A0E1A")
    private val cBg2 = Color.parseColor("#17103A")
    private val cCard = Color.parseColor("#121930")
    private val cStroke = Color.parseColor("#2A3560")
    private val cAccent = Color.parseColor("#7C5CFF")
    private val cAccent2 = Color.parseColor("#00D1FF")
    private val cText = Color.parseColor("#EEF1FF")
    private val cMuted = Color.parseColor("#8A93B5")
    private val cGood = Color.parseColor("#3DDC97")
    private val cBad = Color.parseColor("#FF6B6B")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
            if (stroke > 0) setStroke(dp(stroke), strokeColor)
        }

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun label(t: String, size: Float = 13f, color: Int = cMuted) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
    }

    private fun card(title: String, vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 18, 1, cStroke)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(TextView(this@MainActivity).apply {
                text = title.uppercase(); textSize = 12f; letterSpacing = 0.12f
                typeface = Typeface.DEFAULT_BOLD; setTextColor(cAccent2)
            })
            for (v in views) addView(v)
        }

    private fun input(hint: String, value: String, number: Boolean) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 15f
        setTextColor(cText)
        setHintTextColor(cMuted)
        isSingleLine = true
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
        background = rounded(Color.parseColor("#0C1122"), 12, 1, cStroke)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = lp(8)
    }

    private fun btn(t: String, primary: Boolean) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        setTextColor(if (primary) Color.WHITE else cAccent2)
        background = if (primary)
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cAccent, cAccent2))
                .apply { cornerRadius = dp(14).toFloat() }
        else rounded(Color.TRANSPARENT, 14, 1, cAccent2)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(12) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = cBg1
        window.navigationBarColor = cBg2
        val p = getSharedPreferences("cr", MODE_PRIVATE)

        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(cBg1, cBg2))
        }

        // Водяной знак на фоне
        val wm = TextView(this).apply {
            text = "EURUS CR"
            textSize = 70f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            letterSpacing = 0.1f
            setTextColor(Color.argb(16, 255, 255, 255))
            rotation = -22f
            gravity = Gravity.CENTER
            isSingleLine = true
        }
        root.addView(wm, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(36), dp(18), dp(28))
        }

        // Шапка
        val title = TextView(this).apply {
            text = "EURUS CR"
            textSize = 36f
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            letterSpacing = 0.12f
            setTextColor(cText)
        }
        title.post {
            title.paint.shader = LinearGradient(0f, 0f, title.width.toFloat().coerceAtLeast(1f), 0f,
                cAccent, cAccent2, Shader.TileMode.CLAMP)
            title.invalidate()
        }
        col.addView(title)
        col.addView(label("Deck Spy · колода противника в один тап", 14f).apply { setPadding(0, dp(2), 0, dp(18)) })

        // Статус разрешения
        permTv = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Color.parseColor("#0C1122"), 12, 1, cStroke)
        }
        col.addView(permTv, lp())

        // API ключ
        keyEt = input("Вставь API ключ", p.getString("key", "") ?: "", false)
        val ipTv = label("IP для ключа: 45.79.218.79  (нажми, чтобы скопировать)", 12f).apply {
            setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ip", "45.79.218.79"))
                Toast.makeText(this@MainActivity, "IP скопирован", Toast.LENGTH_SHORT).show()
            }
        }
        col.addView(card("API ключ", label("developer.clashroyale.com → My Account → Create Key", 12f).apply { setPadding(0, dp(6), 0, 0) }, keyEt, ipTv), lp(14))

        // Область ника
        topEt = input("0", p.getInt("top", 0).toString(), true)
        botEt = input("15", p.getInt("bottom", 15).toString(), true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val l = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Сверху, %")); addView(topEt)
            }
            val r = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Снизу, %")); addView(botEt)
            }
            addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            addView(r, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        }
        col.addView(card("Область ника на экране",
            label("Какая часть экрана (по высоте) читается. Подбери, если ник читается неверно.", 12f).apply { setPadding(0, dp(6), 0, dp(4)) },
            row), lp(14))

        // Кнопки
        val b1 = btn("1. Разрешить поверх экрана", false).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        val b2 = btn("2. Запустить", true).apply {
            setOnClickListener {
                save()
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Сначала шаг 1", Toast.LENGTH_SHORT).show()
                } else {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), 1)
                }
            }
        }
        col.addView(b1)
        col.addView(b2)

        col.addView(label("Открой игру → в бою нажми 🔍 в маленьком окошке", 12f).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0)
        }, lp())
        col.addView(label("eurus cr", 12f, Color.argb(150, 255, 255, 255)).apply {
            gravity = Gravity.CENTER; letterSpacing = 0.2f; setPadding(0, dp(20), 0, 0)
        }, lp())

        root.addView(ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(col)
        })
        setContentView(root)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        val ok = Settings.canDrawOverlays(this)
        permTv.text = if (ok) "●  Окно поверх экрана: разрешено" else "●  Окно поверх экрана: не разрешено"
        permTv.setTextColor(if (ok) cGood else cBad)
    }

    private fun save() {
        getSharedPreferences("cr", MODE_PRIVATE).edit()
            .putString("key", keyEt.text.toString().trim())
            .putInt("top", topEt.text.toString().toIntOrNull() ?: 0)
            .putInt("bottom", botEt.text.toString().toIntOrNull() ?: 15)
            .apply()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", res).putExtra("data", data)
            startForegroundService(i)
            Toast.makeText(this, "Запущено. Открой игру", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }
    }
}
