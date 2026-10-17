package com.example.answerhelper.answer

import android.content.Context
import com.example.answerhelper.data.Answer
import com.example.answerhelper.data.Question
import com.example.answerhelper.data.QuestionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 答案优先级：本地题库 → 会话缓存 → 联网搜索与 AI 并行。
 * 大题直接走 AI。
 */
object AnswerEngine {

    private val cache = ConcurrentHashMap<String, Answer>()

    suspend fun solve(ctx: Context, question: Question): Answer? {
        QuestionBank.load(ctx)
        QuestionBank.search(question.query)?.let { (label, conf) ->
            return Answer(label, reason = "本地题库命中", confidence = conf, source = "本地题库")
        }
        val key = question.query.trim()
        cache[key]?.let { return it }

        if (question.kind == QuestionKind.ESSAY || question.kind == QuestionKind.TRANSLATE) {
            val a = AiClient.ask(ctx, question)
            if (a != null) cache[key] = a
            return a
        }

        var webResult: Answer? = null
        var aiResult: Answer? = null
        return coroutineScope {
            val webJob = launch(Dispatchers.IO) {
                webResult = WebSearchClient.search(question)
            }
            val aiJob = launch(Dispatchers.IO) {
                aiResult = AiClient.ask(ctx, question)
            }

            val webDeadline = System.currentTimeMillis() + 8000
            while (webResult == null && System.currentTimeMillis() < webDeadline) {
                delay(150)
            }
            if (webResult != null) {
                aiJob.cancel()
                val a = webResult!!
                cache[key] = a
                return@coroutineScope a
            }

            val aiDone = withTimeoutOrNull(25000) {
                aiJob.join()
                aiResult != null
            }
            webJob.cancel()
            if (aiDone == true && aiResult != null) {
                val a = aiResult!!
                cache[key] = a
                a
            } else {
                aiJob.cancel()
                null
            }
        }
    }
}
