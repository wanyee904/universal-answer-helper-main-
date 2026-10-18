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
                val multi = isMultiChoice(question)
                val rawHint = if (question.rawText.isNotBlank() && question.options.any { it.text.isBlank() }) {
                    "\n题目原文如下：\n${question.rawText}\n请把选项拆分成恰好 $n 项填入options数组（数量不能增删）。拆分规则：\n1、选项不能为空，每一项都必须填写内容，不能留空；\n2、可根据句式相似度拆分选项；\n3、识别文本中空格处“有概率”是拆分点。"
                } else {
                    ""
                }
                val answerRule = if (multi) {
                    "本题为多选题，answer 可以是多个字母连写，例如 AB；rank 与 wrong 返回空数组 []。"
                } else {
                    "本题为单选题，answer 必须且只能是一个字母。同时给出 rank 数组：把全部选项字母按与题意的接近程度从高到低排序，必须包含所有选项字母；给出 wrong 数组：列出必错（明显不符合题意）的选项字母，可为空数组。"
                }
                "你是答题助手。选择题请选出最可能的选项。$answerRule options数组必须恰好包含 $n 项，不能自行增删数量，且每一项都不能为空、不能留空；已给出的选项内容保持不变，空选项必须根据原文补全最合理的内容。只返回JSON，不要其他文字。格式：{\"answer\":\"A\",\"reason\":\"简短理由\",\"rank\":[\"A\",\"C\",\"B\",\"D\"],\"wrong\":[\"D\"],\"options\":[\"选项A内容\",\"选项B内容\"]}" to
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

    private fun isMultiChoice(question: Question): Boolean =
        question.stem.contains("多选题") || question.stem.contains("[多选]") ||
            question.rawText.contains("[多选]")

    private fun parseLetters(arr: JSONArray?, question: Question): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optString(i).trim().uppercase()
            val l = s.firstOrNull { it in 'A'..'Z' }?.toString() ?: return@mapNotNull null
            if (question.options.any { it.label == l }) l else null
        }.distinct()
    }

    /** 单选题出现多个候选时，请 AI 比较并选出与题意最接近的一项 */
    suspend fun pickBest(ctx: Context, question: Question, candidates: List<String>): Answer? =
        withContext(Dispatchers.IO) {
            val key = Prefs.get(ctx, "ai_key").trim()
            if (key.isEmpty()) return@withContext null
            val base = Prefs.get(ctx, "ai_base", "https://api.deepseek.com/v1").trim().trimEnd('/')
            val model = Prefs.get(ctx, "ai_model", "deepseek-chat").trim()
            val opts = question.options.joinToString("\n") { "${it.label}. ${it.text}" }
            val cand = candidates.filter { it.isNotBlank() }.joinToString("、")
            if (cand.isEmpty()) return@withContext null
            val system = "你是答题助手。这是单选题，只能有一个正确答案。候选答案：$cand。请比较候选与题意的契合度，选出最接近的一项；并给出全部选项字母按接近度从高到低的 rank 数组（必须包含所有选项），以及必错的 wrong 数组。只返回JSON，不要其他文字。格式：{\"answer\":\"A\",\"reason\":\"简短理由\",\"rank\":[\"A\",\"C\",\"B\",\"D\"],\"wrong\":[\"D\"]}"
            val user = "题目：${question.stem}\n选项：\n$opts"
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
                    val multi = isMultiChoice(question)
                    val rank = parseLetters(obj.optJSONArray("rank"), question)
                    val wrong = parseLetters(obj.optJSONArray("wrong"), question)
                    val letters = raw.uppercase().filter { it in 'A'..'Z' }
                    var valid = letters.filter { l -> question.options.any { it.label == l.toString() } }
                    if (valid.isEmpty()) {
                        null
                    } else {
                        if (!multi && valid.length > 1) {
                            // 单选题只能有一个答案：优先取 rank 中最接近的候选
                            valid = rank.firstOrNull { valid.contains(it) } ?: valid.first().toString()
                        }
                        val optArr = obj.optJSONArray("options")
                        val opts = if (optArr != null) {
                            (0 until optArr.length()).mapNotNull { i ->
                                optArr.optString(i).trim().ifBlank { null }
                            }
                        } else {
                            emptyList()
                        }
                        Answer(
                            valid, options = opts, reason = reason, confidence = 0.95f, source = "AI",
                            rank = rank, wrong = wrong
                        )
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

    data class TestResult(val ok: Boolean, val message: String)

    /** 主页「测试 AI 连接」用的阻塞式连通测试，需在后台线程调用 */
    fun test(ctx: Context): TestResult {
        val key = Prefs.get(ctx, "ai_key").trim()
        if (key.isEmpty()) return TestResult(false, "未填写 API Key")
        val base = Prefs.get(ctx, "ai_base", "https://api.deepseek.com/v1").trim().trimEnd('/')
        if (base.isEmpty()) return TestResult(false, "未填写接口地址")
        val model = Prefs.get(ctx, "ai_model", "deepseek-chat").trim().ifEmpty { "deepseek-chat" }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "只回复两个字：正常")
                })
            })
            put("max_tokens", 16)
            put("temperature", 0.2)
        }.toString()

        val started = System.currentTimeMillis()
        val req = Request.Builder()
            .url("$base/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val cost = System.currentTimeMillis() - started
                if (resp.isSuccessful) {
                    val content = try {
                        JSONObject(text).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim()
                    } catch (_: Exception) {
                        ""
                    }
                    TestResult(
                        true,
                        "✅ 连接成功\n模型：$model\n耗时：${cost} ms\n返回：${content.take(40).replace("\n", " ")}"
                    )
                } else {
                    val hint = when (resp.code) {
                        401, 403 -> "Key 无效或无权限"
                        404 -> "地址错误（检查是否少了 /v1 或路径拼写）"
                        429 -> "请求过于频繁或额度用尽"
                        in 500..599 -> "服务端错误"
                        else -> "请求被拒绝"
                    }
                    TestResult(false, "❌ 失败 HTTP ${resp.code}\n原因：$hint\n${text.take(160)}")
                }
            }
        } catch (e: Exception) {
            TestResult(false, "❌ 网络错误：${e.message?.take(120) ?: e.javaClass.simpleName}")
        }
    }
}
