package com.example.answerhelper.answer

import com.example.answerhelper.data.Answer
import com.example.answerhelper.data.Question
import com.example.answerhelper.data.QuestionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 联网搜索：仅对选择题生效（统计各选项出现次数）。
 * 判断题和大题直接交给 AI。
 */
object WebSearchClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun search(question: Question): Answer? = withContext(Dispatchers.IO) {
        if (question.kind != QuestionKind.CHOICE) return@withContext null
        val query = question.stem.take(80)
        val urls = listOf(
            "https://www.bing.com/search?q=" + URLEncoder.encode("$query bilibili 答案", "UTF-8"),
            "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode("$query bilibili", "UTF-8")
        )
        val corpus = coroutineScope {
            urls.map { u -> async { fetchText(u) } }
                .awaitAll()
                .joinToString("\n")
        }
        if (corpus.isBlank()) return@withContext null
        scoreOptions(question, corpus)
    }

    private fun fetchText(url: String): String = try {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            val html = resp.body?.string() ?: return ""
            val doc = Jsoup.parse(html)
            val snippets = doc.select(
                "li.b_algo, div.b_algo, div.result, .result__body, .ddg-result, .web-result"
            ).joinToString("\n") { it.text() }
            if (snippets.isNotBlank()) snippets
            else doc.body()?.text()?.take(4000) ?: ""
        }
    } catch (_: Exception) {
        ""
    }

    private fun scoreOptions(question: Question, corpus: String): Answer? {
        if (question.options.isEmpty()) return null
        var best = question.options.first()
        var bestScore = 0
        var second = 0
        for (opt in question.options) {
            val short = opt.text.take(10)
            val full = opt.text.take(24)
            var score = 0
            score += countOccurrences(corpus, full) * 3
            score += countOccurrences(corpus, short) * 2
            score += countOccurrences(corpus, "${opt.label}.$full") * 3
            score += countOccurrences(corpus, "${opt.label}、$full") * 3
            if (score > bestScore) {
                second = bestScore
                bestScore = score
                best = opt
            } else if (score > second) {
                second = score
            }
        }
        if (bestScore == 0) return null
        val conf = (bestScore.toFloat() / (bestScore + second + 1)).coerceIn(0.3f, 0.85f)
        return Answer(best.label, reason = "联网搜索结果中该选项相关内容出现次数最多", confidence = conf, source = "联网搜索")
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.length < 2) return 0
        var count = 0
        var idx = 0
        while (true) {
            idx = haystack.indexOf(needle, idx)
            if (idx < 0) break
            count++
            idx += needle.length
        }
        return count
    }
}
