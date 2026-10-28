package com.example.answerhelper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.answerhelper.auto.AutoAnswerService
import com.example.answerhelper.answer.AiClient
import com.example.answerhelper.util.Prefs
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var etBase: EditText
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var etReadCooldown: EditText
    private lateinit var switchAutoRead: SwitchMaterial
    private lateinit var switchAuto: SwitchMaterial
    private lateinit var switchWebAi: SwitchMaterial
    private lateinit var tvStatus: TextView
    private lateinit var tvAiStatus: TextView
    private lateinit var switchKindChoice: SwitchMaterial
    private lateinit var switchKindJudge: SwitchMaterial
    private lateinit var switchKindEssay: SwitchMaterial
    private lateinit var switchKindCloze: SwitchMaterial
    private lateinit var switchKindTranslate: SwitchMaterial
    private lateinit var switchClozeUnique: SwitchMaterial
    private lateinit var switchKindClipboard: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        etBase = findViewById(R.id.et_base)
        etKey = findViewById(R.id.et_key)
        etModel = findViewById(R.id.et_model)
        etReadCooldown = findViewById(R.id.et_read_cooldown)
        switchAutoRead = findViewById(R.id.switch_auto_read)
        switchAuto = findViewById(R.id.switch_auto)
        switchWebAi = findViewById(R.id.switch_web_ai)
        tvStatus = findViewById(R.id.tv_status)
        tvAiStatus = findViewById(R.id.tv_ai_status)
        switchKindChoice = findViewById(R.id.switch_kind_choice)
        switchKindJudge = findViewById(R.id.switch_kind_judge)
        switchKindEssay = findViewById(R.id.switch_kind_essay)
        switchKindCloze = findViewById(R.id.switch_kind_cloze)
        switchKindTranslate = findViewById(R.id.switch_kind_translate)
        switchClozeUnique = findViewById(R.id.switch_cloze_unique)
        switchKindClipboard = findViewById(R.id.switch_kind_clipboard)

        switchKindCloze.setOnCheckedChangeListener { _, checked ->
            switchClozeUnique.visibility = if (checked) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener { requestStart() }
        findViewById<Button>(R.id.btn_manage_clip).setOnClickListener {
            startActivity(Intent(this, ClipboardActivity::class.java))
        }
        findViewById<Button>(R.id.btn_tutorial).setOnClickListener {
            startActivity(Intent(this, TutorialActivity::class.java))
        }

        findViewById<Button>(R.id.btn_test_ai).setOnClickListener {
            savePrefs()
            val status = tvAiStatus
            status.text = "测试中…"
            Thread {
                val result = AiClient.test(this)
                runOnUiThread { status.text = result.message }
            }.start()
        }

        migrateDefaultsOnce()
        loadPrefs()
    }

    /** 一次性迁移：自动识别与自动点击默认改为关闭 */
    private fun migrateDefaultsOnce() {
        if (!Prefs.getBool(this, "migrated_auto_off", false)) {
            Prefs.setBool(this, "auto_read", false)
            Prefs.setBool(this, "auto_mode", false)
            Prefs.setBool(this, "migrated_auto_off", true)
        }
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val acc = AutoAnswerService.instance != null
        tvStatus.text = "悬浮窗权限：${if (overlay) "已开启" else "未开启，请先开启"}\n" +
            "无障碍服务：${if (acc) "已开启" else "未开启，请先开启"}"
    }

    private fun requestStart() {
        savePrefs()
        if (enabledKindCount() == 0) {
            Toast.makeText(this, "至少开启一项功能后再开始", Toast.LENGTH_SHORT).show()
            return
        }
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

    private fun enabledKindCount(): Int = listOf(
        switchKindChoice, switchKindJudge, switchKindEssay, switchKindCloze, switchKindTranslate
    ).count { it.isChecked }

    private fun savePrefs() {
        Prefs.set(this, "ai_base", etBase.text.toString().trim())
        Prefs.set(this, "ai_key", etKey.text.toString().trim())
        Prefs.set(this, "ai_model", etModel.text.toString().trim())
        Prefs.set(this, "read_cooldown", etReadCooldown.text.toString().trim())
        Prefs.setBool(this, "auto_read", switchAutoRead.isChecked)
        Prefs.setBool(this, "auto_mode", switchAuto.isChecked)
        Prefs.setBool(this, "web_ai_combo", switchWebAi.isChecked)
        Prefs.setBool(this, "kind_choice", switchKindChoice.isChecked)
        Prefs.setBool(this, "kind_judge", switchKindJudge.isChecked)
        Prefs.setBool(this, "kind_essay", switchKindEssay.isChecked)
        Prefs.setBool(this, "kind_cloze", switchKindCloze.isChecked)
        Prefs.setBool(this, "kind_translate", switchKindTranslate.isChecked)
        Prefs.setBool(this, "cloze_unique", switchClozeUnique.isChecked)
        Prefs.setBool(this, "kind_clipboard", switchKindClipboard.isChecked)
    }

    private fun loadPrefs() {
        etBase.setText(Prefs.get(this, "ai_base", "https://api.deepseek.com/v1"))
        etKey.setText(Prefs.get(this, "ai_key"))
        etModel.setText(Prefs.get(this, "ai_model", "deepseek-chat"))
        etReadCooldown.setText(Prefs.get(this, "read_cooldown", "5"))
        switchAutoRead.isChecked = Prefs.getBool(this, "auto_read", false)
        switchAuto.isChecked = Prefs.getBool(this, "auto_mode", false)
        switchWebAi.isChecked = Prefs.getBool(this, "web_ai_combo", true)
        switchKindChoice.isChecked = Prefs.getBool(this, "kind_choice", true)
        switchKindJudge.isChecked = Prefs.getBool(this, "kind_judge", true)
        switchKindEssay.isChecked = Prefs.getBool(this, "kind_essay", true)
        switchKindCloze.isChecked = Prefs.getBool(this, "kind_cloze", true)
        switchKindTranslate.isChecked = Prefs.getBool(this, "kind_translate", true)
        switchClozeUnique.isChecked = Prefs.getBool(this, "cloze_unique", true)
        switchKindClipboard.isChecked = Prefs.getBool(this, "kind_clipboard", true)
        switchClozeUnique.visibility = if (switchKindCloze.isChecked) View.VISIBLE else View.GONE
    }
}
