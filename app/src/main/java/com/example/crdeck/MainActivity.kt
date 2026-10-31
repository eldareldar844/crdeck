package com.example.crdeck

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var keyEt: EditText
    private lateinit var topEt: EditText
    private lateinit var botEt: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("cr", MODE_PRIVATE)
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * d).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, (12 * d).toInt(), 0, 0) }

        keyEt = EditText(this).apply { hint = "API ключ (developer.clashroyale.com)"; setText(p.getString("key", "")) }
        topEt = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(p.getInt("top", 0).toString()) }
        botEt = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(p.getInt("bottom", 15).toString()) }

        val b1 = Button(this).apply {
            text = "1. Разрешить поверх экрана"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        val b2 = Button(this).apply {
            text = "2. Запустить"
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
        col.addView(label("API ключ (IP для ключа: 45.79.218.79)"))
        col.addView(keyEt)
        col.addView(label("Область ника, % высоты экрана: сверху"))
        col.addView(topEt)
        col.addView(label("Область ника, % высоты экрана: снизу"))
        col.addView(botEt)
        col.addView(b1)
        col.addView(b2)
        setContentView(ScrollView(this).apply { addView(col) })

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        }
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
