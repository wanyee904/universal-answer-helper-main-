package com.example.answerhelper.answer

import android.content.Context
import com.example.answerhelper.data.Answer
import com.example.answerhelper.data.Question
import com.example.answerhelper.data.QuestionKind
import com.example.answerhelper.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 Chat Completions 接口。
 * 按题型给出不同格式的答案：选择题字母、判断题 是/否、大题完整文本。
 */
object AiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun ask(ctx: Context, question: Question): Answer? = withContext(Dispatchers.IO) {
        val key = Prefs.get(ctx, "ai_key").trim()
        if (key.isEmpty()) return@withContext null
        val base = Prefs.get(ctx, "ai_base", "https://api.deepseek.com/v1").trim().trimEnd('/')
        val model = Prefs.get(ctx, "ai_model", "deepseek-chat").trim()

        val (system, user) = when (question.kind) {
            QuestionKind.CHOICE -> {
                val n = question.options.size.coerceIn(2, 26)
                val opts = question.options.joinToString("\n") { "${it.label}. ${it.text}" }
                val rawHint = if (question.rawText.isNotBlank() && question.options.any { it.text.isBlank() }) {
                    "\n题目原文如下：\n${question.rawText}\n请把选项拆分成恰好 $n 项填入options数组（数量不能增删）。拆分规则：\n1、选项不能为空，每一项都必须填写内容，不能留空；\n2、可根据句式相似度拆分选项；\n3、识别文本中空格处“有概率”是拆分点。"
                } else {
                    ""
                }
                "你是答题助手。选择题请选出最可能的选项；多选题把字母连写，例如AB。options数组必须恰好包含 $n 项，不能自行增删数量，且每一项都不能为空、不能留空；已给出的选项内容保持不变，空选项必须根据原文补全最合理的内容。只返回JSON，不要其他文字。格式：{\"answer\":\"A\",\"reason\":\"简短理由\",\"options\":[\"选项A内容\",\"选项B内容\"]}" to
                    "题目：${question.stem}$rawHint\n选项：\n$opts"
            }
            QuestionKind.JUDGE -> {
                "你是答题助手。判断题只能回答“是”或“否”。只返回JSON，不要其他文字。格式：{\"answer\":\"是\",\"reason\":\"简短理由\"}" to
                    "题目：${question.stem}"
            }
            QuestionKind.ESSAY -> {
                "你是答题助手。这是一道大题，请给出完整、准确的文字答案。只返回JSON，不要其他文字。格式：{\"answer\":\"答案内容\",\"reason\":\"简短依据\"}" to
                    "题目：${question.query}"
            }
            QuestionKind.TRANSLATE -> {
                "你是翻译助手。请翻译用户给出的文本：若源文本主要是中文，翻译成英文；否则翻译成中文。保持原文排版（段落、换行、列表等格式尽量保留）。只返回JSON，不要其他文字。格式：{\"answer\":\"译文\",\"reason\":\"\"}" to
                    "文本：\n${question.stem}"
            }
        }

        try {
            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.1)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", system) })
                    put(JSONObject().apply { put("role", "user"); put("content", user) })
                })
            }
            val req = Request.Builder()
                .url("$base/chat/completions")
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val text = resp.body?.string() ?: return@withContext null
                parse(text, question)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parse(json: String, question: Question): Answer? {
        try {
            val root = JSONObject(json)
            val content = root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            val cleaned = content.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = JSONObject(cleaned.substring(start, end + 1))
            val raw = obj.optString("answer", "")
            val reason = obj.optString("reason", "")
            return when (question.kind) {
                QuestionKind.CHOICE -> {
                    val letters = raw.uppercase().filter { it in 'A'..'Z' }
                    val valid = letters.filter { l ->
                        question.options.any { it.label == l.toString() }
                    }
                    if (valid.isEmpty()) {
                        null
                    } else {
                        val optArr = obj.optJSONArray("options")
                        val opts = if (optArr != null) {
                            (0 until optArr.length()).mapNotNull { i ->
                                optArr.optString(i).trim().ifBlank { null }
                            }
                        } else {
                            emptyList()
                        }
                        Answer(valid, options = opts, reason = reason, confidence = 0.95f, source = "AI")
                    }
                }
                QuestionKind.JUDGE -> {
                    val t = raw.trim()
                    val label = when {
                        t.contains("是") && !t.contains("否") -> "A"
                        t.contains("否") && !t.contains("是") -> "B"
                        t.contains("正确") && !t.contains("错误") -> "A"
                        t.contains("错误") && !t.contains("正确") -> "B"
                        t.contains("对") && !t.contains("错") -> "A"
                        t.contains("错") && !t.contains("对") -> "B"
                        t.equals("true", ignoreCase = true) -> "A"
                        t.equals("false", ignoreCase = true) -> "B"
                        t.uppercase() == "A" -> "A"
                        t.uppercase() == "B" -> "B"
                        else -> return null
                    }
                    Answer(label, text = if (label == "A") "是" else "否", reason = reason, confidence = 0.95f, source = "AI")
                }
                QuestionKind.ESSAY -> {
                    if (raw.isBlank()) null
                    else Answer("", text = raw, reason = reason, confidence = 0.95f, source = "AI")
                }
                QuestionKind.TRANSLATE -> {
                    if (raw.isBlank()) null
                    else Answer("", text = raw, reason = reason, confidence = 0.95f, source = "AI翻译")
                }
            }
        } catch (_: Exception) {
            return null
        }
    }
}
