package com.example.answerhelper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 使用教程：无障碍授权、后台保活、DeepSeek API 创建 */
class TutorialActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)

        findViewById<Button>(R.id.btn_tut_a11y).setOnClickListener {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "无法打开无障碍设置，请手动进入系统设置")
        }
        findViewById<Button>(R.id.btn_tut_app_info).setOnClickListener {
            open(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                "无法打开应用详情，请手动进入系统设置"
            )
        }
        findViewById<Button>(R.id.btn_tut_battery).setOnClickListener {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), "无法打开电池优化设置，请手动进入系统设置")
        }
        findViewById<Button>(R.id.btn_tut_deepseek).setOnClickListener {
            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys")), "没有可用的浏览器")
        }
        findViewById<Button>(R.id.btn_tut_a11y_top).setOnClickListener {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "无法打开无障碍设置，请手动进入系统设置")
        }
        findViewById<Button>(R.id.btn_tut_back).setOnClickListener { finish() }
    }

    private fun open(intent: Intent, fallback: String) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, fallback, Toast.LENGTH_LONG).show()
        }
    }
}
