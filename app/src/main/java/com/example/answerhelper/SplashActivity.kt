package com.example.answerhelper

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity

/** 启动页：播放 1 秒 logo + 电路动画后进入主界面 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val view = findViewById<SplashLogoView>(R.id.splash_view)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            interpolator = DecelerateInterpolator()
            addUpdateListener { view.setProgress(it.animatedValue as Float) }
            start()
        }

        view.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 1080L)
    }
}
