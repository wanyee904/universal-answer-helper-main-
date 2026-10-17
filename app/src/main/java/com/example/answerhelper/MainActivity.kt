package com.example.answerhelper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.answerhelper.auto.AutoAnswerService
import com.example.answerhelper.util.Prefs
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var etBase: EditText
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etReadCooldown: EditText
    private lateinit var switchAutoRead: SwitchMaterial
    private lateinit var switchAuto: SwitchMaterial
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etBase = findViewById(R.id.et_base)
        etKey = findViewById(R.id.et_key)
        etModel = findViewById(R.id.et_model)
        etReadCooldown = findViewById(R.id.et_read_cooldown)
        switchAutoRead = findViewById(R.id.switch_auto_read)
        switchAuto = findViewById(R.id.switch_auto)
        tvStatus = findViewById(R.id.tv_status)

        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener { requestStart() }

        loadPrefs()
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val acc = AutoAnswerService.instance != null
        tvStatus.text = "悬浮窗权限：${if (overlay) "已开启" else "未开启，请先开启"}\n" +
            "无障碍服务：${if (acc) "已开启" else "未开启，请先开启"}"
    }

    private fun requestStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (AutoAnswerService.instance == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        savePrefs()
        AutoAnswerService.instance?.startHelper()
        tvStatus.text = "已启动，切换到答题页面即可自动识别"
    }

    private fun savePrefs() {
        Prefs.set(this, "ai_base", etBase.text.toString().trim())
        Prefs.set(this, "ai_key", etKey.text.toString().trim())
        Prefs.set(this, "ai_model", etModel.text.toString().trim())
        Prefs.set(this, "read_cooldown", etReadCooldown.text.toString().trim())
        Prefs.setBool(this, "auto_read", switchAutoRead.isChecked)
        Prefs.setBool(this, "auto_mode", switchAuto.isChecked)
    }

    private fun loadPrefs() {
        etBase.setText(Prefs.get(this, "ai_base", "https://api.deepseek.com/v1"))
        etKey.setText(Prefs.get(this, "ai_key"))
        etModel.setText(Prefs.get(this, "ai_model", "deepseek-chat"))
        etReadCooldown.setText(Prefs.get(this, "read_cooldown", "5"))
        switchAutoRead.isChecked = Prefs.getBool(this, "auto_read", true)
        switchAuto.isChecked = Prefs.getBool(this, "auto_mode", false)
    }
}
