package com.example.answerhelper.answer

import android.content.Context
import com.example.answerhelper.data.Answer
import com.example.answerhelper.data.Question
import com.example.answerhelper.data.QuestionKind
import com.example.answerhelper.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 答案优先级：本地题库 → 会话缓存 → 选择题答题策略。
 * 选择题策略：
 *  - 开启「联网+AI」：联网搜索与 AI 并行，两者结果互相校验后合并
 *  - 关闭「联网+AI」：先联网搜索，搜不到再用 AI
 * 大题/翻译直接走 AI。
 */
object AnswerEngine {

    private val cache = ConcurrentHashMap<String, Answer>()

    suspend fun solve(ctx: Context, question: Question, force: Boolean = false): Answer? {
        if (!force) {
            QuestionBank.load(ctx)
            QuestionBank.search(question.query)?.let { (label, conf) ->
                return Answer(label, reason = "本地题库命中", confidence = conf, source = "本地题库")
            }
        }
        val key = question.query.trim()
        if (!force) {
            cache[key]?.let { return it }
        }

        if (question.kind == QuestionKind.ESSAY || question.kind == QuestionKind.TRANSLATE) {
            val a = AiClient.ask(ctx, question)
            if (a != null) cache[key] = a
            return a
        }

        val combo = Prefs.getBool(ctx, "web_ai_combo", true)
        val result = if (combo) solveCombined(ctx, question) else solveWebFirst(ctx, question)
        if (result != null) cache[key] = result
        return result
    }

    /** 联网优先：联网搜索拿不到再等 AI */
    private suspend fun solveWebFirst(ctx: Context, question: Question): Answer? = coroutineScope {
        var webResult: Answer? = null
        var aiResult: Answer? = null
        val webJob = launch(Dispatchers.IO) { webResult = WebSearchClient.search(question) }
        val aiJob = launch(Dispatchers.IO) { aiResult = AiClient.ask(ctx, question) }

        val webDeadline = System.currentTimeMillis() + 8000
        while (webResult == null && System.currentTimeMillis() < webDeadline) {
            delay(150)
        }
        if (webResult != null) {
            aiJob.cancel()
            return@coroutineScope webResult
        }

        val aiDone = withTimeoutOrNull(25000) {
            aiJob.join()
            aiResult != null
        }
        webJob.cancel()
        if (aiDone == true && aiResult != null) {
            aiResult
        } else {
            aiJob.cancel()
            null
        }
    }

    /** 联网 + AI 公用：两者并行，结果互相校验后合并 */
    private suspend fun solveCombined(ctx: Context, question: Question): Answer? = coroutineScope {
        var webResult: Answer? = null
        var aiResult: Answer? = null
        val webJob = launch(Dispatchers.IO) { webResult = WebSearchClient.search(question) }
        val aiJob = launch(Dispatchers.IO) { aiResult = AiClient.ask(ctx, question) }

        withTimeoutOrNull(9000) { webJob.join() }
        if (webResult == null) webJob.cancel()
        withTimeoutOrNull(25000) { aiJob.join() }
        if (aiResult == null) aiJob.cancel()

        val w = webResult
        val a = aiResult
        when {
            w != null && a != null -> merge(ctx, question, w, a)
            w != null -> w
            a != null -> a
            else -> null
        }
    }

    private suspend fun merge(ctx: Context, question: Question, web: Answer, ai: Answer): Answer {
        val multi = question.stem.contains("多选题") || question.rawText.contains("[多选]")
        val wLabel = web.label.uppercase().replace(" ", "")
        val aLabel = ai.label.uppercase().replace(" ", "")

        if (!multi) {
            // 单选题：只能保留一个答案；候选不一致时请 AI 比较选最接近的
            val wFirst = wLabel.take(1)
            val aFirst = aLabel.take(1).ifEmpty { wFirst }
            if (wFirst.isNotEmpty() && aFirst.isNotEmpty() && wFirst != aFirst) {
                val picked = AiClient.pickBest(ctx, question, listOf(wFirst, aFirst))
                if (picked != null) return picked
            }
            val rank = (listOf(aFirst) + ai.rank + listOf(wFirst))
                .filter { it.length == 1 }
                .distinct()
            val same = wFirst == aFirst
            return Answer(
                aFirst,
                text = ai.text,
                options = ai.options,
                reason = if (same) "联网与AI一致。${ai.reason}"
                else "联网 ${wFirst} 与 AI ${aFirst} 不一致，采用 AI：${ai.reason}",
                confidence = if (same) maxOf(web.confidence, ai.confidence).coerceAtMost(0.99f)
                else (ai.confidence * 0.9f).coerceAtMost(0.95f),
                source = if (same) "联网+AI" else "AI(与联网不一致)",
                rank = rank,
                wrong = ai.wrong
            )
        }

        return if (wLabel == aLabel) {
            Answer(
                ai.label,
                text = ai.text,
                options = ai.options,
                reason = "联网与AI一致。${ai.reason}",
                confidence = maxOf(web.confidence, ai.confidence).coerceAtMost(0.99f),
                source = "联网+AI"
            )
        } else if (ai.confidence >= web.confidence) {
            Answer(
                ai.label,
                text = ai.text,
                options = ai.options,
                reason = "联网给出 ${web.label}，AI 给出 ${ai.label}，采用 AI：${ai.reason}",
                confidence = (ai.confidence * 0.9f).coerceAtMost(0.95f),
                source = "AI(与联网不一致)"
            )
        } else {
            Answer(
                web.label,
                text = web.text,
                options = web.options,
                reason = "联网给出 ${web.label}，AI 给出 ${ai.label}，采用联网：${web.reason}",
                confidence = (web.confidence * 0.9f).coerceAtMost(0.95f),
                source = "联网(与AI不一致)"
            )
        }
    }
}
