package com.example.answerhelper.answer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class BankItem(val q: String, val a: String, val note: String = "")

/**
 * 本地题库：assets/question_bank.json + 应用私有目录 filesDir/question_bank.json
 * 格式：[{"q": "题干", "a": "A 或 AB", "note": "备注"}]
 */
object QuestionBank {

    private val gson = Gson()
    @Volatile private var items: List<BankItem> = emptyList()
    @Volatile private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val all = mutableListOf<BankItem>()
        try {
            val text = ctx.assets.open("question_bank.json").bufferedReader().use { it.readText() }
            all += parse(text)
        } catch (_: Exception) {
        }
        try {
            val f = java.io.File(ctx.filesDir, "question_bank.json")
            if (f.exists()) all += parse(f.readText())
        } catch (_: Exception) {
        }
        items = all
        loaded = true
    }

    private fun parse(text: String): List<BankItem> = try {
        gson.fromJson(text, object : TypeToken<List<BankItem>>() {}.type) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** 精确匹配题干，或题干包含题库条目时命中；返回答案字母与置信度 */
    fun search(title: String): Pair<String, Float>? {
        val q = title.trim()
        if (q.isEmpty()) return null
        for (item in items) {
            val bq = item.q.trim()
            if (bq.isEmpty()) continue
            if (bq == q || (bq.length >= 8 && q.contains(bq))) {
                return item.a.trim().uppercase() to 1.0f
            }
        }
        return null
    }
}
