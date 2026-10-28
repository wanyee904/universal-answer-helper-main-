package com.example.answerhelper.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.LayoutInflater
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.answerhelper.R
import com.example.answerhelper.answer.AiClient
import com.example.answerhelper.answer.AnswerEngine
import com.example.answerhelper.data.Answer
import com.example.answerhelper.data.OptionBox
import com.example.answerhelper.data.Question
import com.example.answerhelper.data.QuestionKind
import com.example.answerhelper.translate.PageParagraph
import com.example.answerhelper.translate.PageTextExtractor
import com.example.answerhelper.translate.TextNode
import com.example.answerhelper.translate.PageTranslation
import com.example.answerhelper.translate.TranslateBatchClient
import com.example.answerhelper.translate.TranslatePipeline
import com.example.answerhelper.util.ClipStore
import com.example.answerhelper.util.Prefs
import com.example.answerhelper.vision.ScreenshotOcr
import com.example.answerhelper.vision.XwLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 万能答题助手：通过无障碍服务直接读取当前界面的文本节点，
 * 自动识别题目与选项，调用答案引擎求解，并直接点击选项节点。
 * 不截图、不 OCR、不限定某个 App。
 */
class AutoAnswerService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoAnswerService? = null

        /** 两次截屏之间的最小间隔（系统对 takeScreenshot 有频率限制，撞上会直接失败）。 */
        private const val OCR_MIN_INTERVAL_MS = 1100L
    }

    /** 译文的数据来源：无障碍直接提取（A11Y）或截屏 OCR 兜底（OCR）。 */
    private enum class TranslateSource { A11Y, OCR }

    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var busy = false
    private var editing = false
    private var pauseUntil = 0L
    private var lastReadAt = 0L

    private var lastTitle = ""
    private var lastSeenAt = 0L
    private var lastQuestion: Question? = null
    private var currentKind = QuestionKind.CHOICE
    private val detectedQuestions = mutableListOf<Question>()
    private var currentIndex = 0
    private var manualIndex = -1

    // 悬浮窗
    private var overlayView: View? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var ballView: ImageView? = null
    private var ballLp: WindowManager.LayoutParams? = null
    private var tvResult: TextView? = null
    private var tvQuestion: TextView? = null
    private var tvReason: TextView? = null
    private var optionsContainer: LinearLayout? = null
    private var btnCollapse: Button? = null
    private var btnTypeChoice: Button? = null
    private var btnTypeJudge: Button? = null
    private var btnTypeEssay: Button? = null
    private var btnTypeTranslate: Button? = null
    private var btnTypeCloze: Button? = null
    private var btnTypeClipboard: Button? = null
    private var choiceModeRow: LinearLayout? = null
    private var btnChoiceSingle: Button? = null
    private var btnChoiceMulti: Button? = null
    private val multiPrefs = mutableMapOf<String, Boolean>()
    private val ANSWER_COLOR = 0xFF2E7D32.toInt()
    private var editArea: LinearLayout? = null
    private var etStem: EditText? = null
    private var etSub: EditText? = null
    private var tvJudgeOptions: TextView? = null
    private var optionsEditContainer: LinearLayout? = null
    private var optionsEditActions: LinearLayout? = null
    private var optionCountRow: LinearLayout? = null
    private var etOptionCount: EditText? = null
    private var lastFocusedEditor: EditText? = null
    private var clipPanel: LinearLayout? = null
    private var clipList: LinearLayout? = null
    private var collapsed = false

    private val optionEditors = mutableListOf<EditText>()
    private var optionEditCount = 0

    private val readRunnable = Runnable { readAndSolve() }

    // ---------- 翻译状态 ----------

    private var overlayTranslateController: OverlayTranslateController? = null
    private var translatePipeline: TranslatePipeline? = null

    /** 当前页面的段落快照（含屏幕坐标）：整篇翻译的输入，也用于"同一页"复用判断。 */
    private var lastTranslateParagraphs: List<PageParagraph> = emptyList()

    private var translateActive = false

    /** 每次整篇翻译自增；旧轮次的进度回调靠它丢弃。 */
    private var translateToken = 0

    /** 每次截屏采集自增。与 translateToken 分开：截屏重采不该把在飞的翻译轮次判死。 */
    private var ocrToken = 0

    private var translateJob: Job? = null

    /** 当前译文的数据来源（无障碍提取 / 截屏 OCR 兜底），只用于日志与提示。 */
    private var translateSource = TranslateSource.A11Y

    /** 截屏通道的在途任务：同一时刻最多一次截屏 + 识别。 */
    private var ocrJob: Job? = null

    /** 截屏在飞标记：系统限制两次截屏的最小间隔，连发第二次会拿到 errorCode=3。 */
    private var ocrBusy = false

    /** 截屏在飞期间又来了一次采集请求：等这次结束后再补一次，而不是并发撞车。 */
    private var ocrPending = false

    /** 上一次真正发起截屏的时间戳（用于满足系统的最小间隔限制）。 */
    private var lastScreenshotAt = 0L

    /** 是否有整篇翻译轮次在飞：在飞时忽略重复的识别请求，避免同一页被翻两遍。 */
    private var translateRoundBusy = false

    /** 上一轮整篇译文，用于同一页重复识别时直接复用，不再重翻。 */
    private var lastPageTranslation: PageTranslation? = null

    /** 上一轮整篇翻译的段落文本快照：用来判断页面到底有没有变。 */
    private var lastRoundTexts: List<String> = emptyList()

    private fun optionCount(): Int =
        (Prefs.get(this, "option_count", "4").toIntOrNull() ?: 4).coerceIn(2, 26)

    private fun autoReadEnabled(): Boolean = Prefs.getBool(this, "auto_read", false)

    private fun readCooldownMs(): Long =
        (Prefs.get(this, "read_cooldown", "5").toIntOrNull() ?: 5).coerceIn(2, 120) * 1000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        XwLog.init(this)
        startClipboardWatcher()
    }

    /** 监听系统剪贴板：运行期间用户复制的文字自动存入应用剪贴板 */
    private fun startClipboardWatcher() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.addPrimaryClipChangedListener {
                try {
                    val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        if (ClipStore.load(this).firstOrNull() != text.trim()) {
                            ClipStore.add(this, text)
                            if (clipPanel?.visibility == View.VISIBLE) rebuildClipList()
                        }
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        // 屏蔽悬浮窗自身引发的事件，防自我触发
        if (event.packageName?.toString() == packageName) return
        // 翻译不跟随源页面滚动：滚动事件一律忽略，弹窗内容只由用户点「识别」决定
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        // 答题的自动识别轮询（与翻译无关，保留）
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            if (autoReadEnabled() && !busy && !editing &&
                System.currentTimeMillis() - lastReadAt >= readCooldownMs()
            ) {
                handler.removeCallbacks(readRunnable)
                handler.postDelayed(readRunnable, 500)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        stopHelper()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopHelper()
        super.onDestroy()
    }

    fun startHelper() {
        if (scope != null) return
        showOverlay()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope?.launch {
            while (isActive) {
                if (autoReadEnabled() && !busy && !editing &&
                    System.currentTimeMillis() >= pauseUntil &&
                    System.currentTimeMillis() - lastReadAt >= readCooldownMs()
                ) {
                    readAndSolve()
                }
                delay(1000)
            }
        }
        updateOverlay("已启动，切换到答题页面自动识别", null, null)
    }

    fun stopHelper() {
        scope?.cancel()
        scope = null
        busy = false
        editing = false
        removeBall()
        removeOverlay()
    }

    // ---------- 读取与识别 ----------

    private fun readAndSolve(force: Boolean = false) {
        if (busy || editing) return
        if (System.currentTimeMillis() < pauseUntil) return
        if (!force && !autoReadEnabled()) return
        if (!force && System.currentTimeMillis() - lastReadAt < readCooldownMs()) return
        lastReadAt = System.currentTimeMillis()

        // 优先读取当前聚焦/活动窗口，其次才是其他窗口
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        val orderedWindows = mutableListOf<android.view.accessibility.AccessibilityWindowInfo>()
        for (w in windows) if (w.isFocused || w.isActive) orderedWindows.add(w)
        for (w in windows) if (!orderedWindows.contains(w)) orderedWindows.add(w)
        for (w in orderedWindows) {
            w.root?.let { r -> if (roots.none { it === r }) roots.add(r) }
        }
        if (roots.isEmpty()) {
            updateOverlay("未获取到界面，请停留在答题页面", null, null)
            return
        }

        var blocks: List<Question> = emptyList()
        for (r in roots) {
            blocks = extractBlocks(r)
            if (blocks.isNotEmpty()) break
        }
        // 只保留已开启的功能类型
        blocks = blocks.filter { isKindEnabled(it.kind) }
        if (blocks.isEmpty()) {
            if (currentKind == QuestionKind.TRANSLATE) {
                // 无障碍提取为空（微信文章页这类不暴露节点的页面）→ 截屏 OCR 兜底
                startTranslateViaOcr(force)
                return
            }
            updateOverlay("未识别到已开启题型的题目（可在主页开启更多功能）", null, null)
            return
        }
        // 应用用户手动设置的“单选/多选”，避免自动重读覆盖手动选择
        blocks = blocks.map { b ->
            val pref = multiPrefs[stemKey(b.stem)]
            if (pref == null) b else b.copy(stem = withMulti(b.stem, pref))
        }
        // 页面变化（题目列表变了）才重置为第 1 题；否则保持用户手动选择的题
        val changed = blocks.map { it.query } != detectedQuestions.map { it.query }
        if (changed) {
            manualIndex = -1
            currentIndex = 0
        } else if (manualIndex >= 0 && manualIndex < blocks.size) {
            currentIndex = manualIndex
        } else if (currentIndex >= blocks.size) {
            currentIndex = 0
        }
        detectedQuestions.clear()
        detectedQuestions.addAll(blocks)

        val q = detectedQuestions[currentIndex]
        val now = System.currentTimeMillis()
        if (q.query == lastTitle && now - lastSeenAt < 15000) return
        lastTitle = q.query
        lastSeenAt = now
        currentKind = q.kind
        updateOverlay(questionStatus(q), q, null)
        solveAndShow(q)
    }

    private fun questionStatus(q: Question): String {
        val prefix = if (detectedQuestions.size > 1) "共${detectedQuestions.size}题 第${currentIndex + 1}题 · " else ""
        return "${prefix}识别到${q.kind.label}，正在搜索答案…"
    }

    private fun showDetected(index: Int) {
        if (detectedQuestions.isEmpty()) return
        manualIndex = index.coerceIn(0, detectedQuestions.size - 1)
        currentIndex = manualIndex
        val q = detectedQuestions[currentIndex]
        lastTitle = q.query
        lastSeenAt = System.currentTimeMillis()
        currentKind = q.kind
        updateOverlay(questionStatus(q), q, null)
        solveAndShow(q)
    }

    private fun prevQuestion() = showDetected(currentIndex - 1)

    private fun nextQuestion() = showDetected(currentIndex + 1)

    private data class TextItem(
        val text: String,
        val node: AccessibilityNodeInfo,
        val bounds: Rect
    )

    private fun collectItems(node: AccessibilityNodeInfo, out: MutableList<TextItem>) {
        val t = node.text?.toString()?.trim() ?: ""
        val keep = t.length in 2..4000 ||
            (t.length == 1 && t[0] in 'A'..'H') ||
            t in listOf("对", "错", "是", "否")
        if (keep) {
            val b = Rect()
            node.getBoundsInScreen(b)
            out.add(TextItem(t, node, b))
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectItems(it, out) }
        }
    }

    /**
     * 翻译通道的采集：整棵树递归，不设长度上下限（collectItems 的 2..4000 过滤只服务选择题解析），
     * 只要节点自身有非空文本就以屏幕坐标收下，零宽/零高节点不可见，跳过。
     */
    private fun collectAllTextNodes(node: AccessibilityNodeInfo, out: MutableList<TextNode>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                out.add(TextNode(text, bounds.left, bounds.top, bounds.right, bounds.bottom))
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllTextNodes(it, out) }
        }
    }

    /** 整页文本还原：节点 -> 逻辑行 -> 段落（段内换行、段间空行由 PageTextExtractor 负责）。 */
    private fun extractPageParagraphs(root: AccessibilityNodeInfo): List<PageParagraph> {
        val nodes = mutableListOf<TextNode>()
        collectAllTextNodes(root, nodes)
        return PageTextExtractor.extract(nodes, dp(6), dp(12))
    }

    private fun extractBlocks(root: AccessibilityNodeInfo): List<Question> {
        val items = mutableListOf<TextItem>()
        collectItems(root, items)
        // 翻译模式不吃 collectItems 的长度过滤，页面里没有“题目文本”时也要继续走整页通道
        if (items.isEmpty() && currentKind != QuestionKind.TRANSLATE) return emptyList()
        val raw = items.joinToString("\n") { it.text }

        parseCloze(items, raw)?.let { return listOf(it) }

        if (currentKind == QuestionKind.TRANSLATE && isKindEnabled(QuestionKind.TRANSLATE)) {
            // 整页识别：无长度上限、保留排版，段落快照交给整篇翻译（块级缓存挂在管线上）
            val paragraphs = extractPageParagraphs(root)
            lastTranslateParagraphs = paragraphs
            if (paragraphs.isEmpty()) return emptyList()
            translateSource = TranslateSource.A11Y
            val pageText = PageTextExtractor.toPageText(paragraphs)
            return listOf(Question(QuestionKind.TRANSLATE, stem = pageText, rawText = pageText))
        }

        val sameNodeRegex = Regex("^\\s*([A-H])\\s*(?:[.、．:：]\\s*|\\s+)(.*)$")
        val labelOnlyRegex = Regex("^\\s*([A-H])\\s*[.、．:：]?\\s*$")

        data class Group(val stemIdx: Int, val options: MutableList<OptionBox>)

        val groups = mutableListOf<Group>()
        var cur: Group? = null
        var pendingLabel: String? = null
        var lastWasContent = false

        for ((i, item) in items.withIndex()) {
            val smRaw = sameNodeRegex.matchEntire(item.text)
            // 只有标号的节点（如 "A、" "A."）交给分离解析，否则内容会丢失
            val sm = if (smRaw != null && smRaw.groupValues[2].trim().isNotEmpty()) smRaw else null
            val lm = if (sm == null) labelOnlyRegex.matchEntire(item.text) else null
            if (sm != null) {
                if (cur != null && cur.options.size >= 2 && !lastWasContent) {
                    groups.add(cur)
                    cur = null
                }
                if (cur == null) cur = Group(stemIndexBefore(items, i), mutableListOf())
                cur.options.add(
                    OptionBox(
                        sm.groupValues[1],
                        sm.groupValues[2].trim().ifBlank { item.text },
                        item.bounds.centerX(), item.bounds.centerY(), Rect(item.bounds), item.node
                    )
                )
                pendingLabel = null
                lastWasContent = true
            } else if (lm != null) {
                if (cur != null && cur.options.size >= 2 && !lastWasContent) {
                    groups.add(cur)
                    cur = null
                }
                if (cur == null) cur = Group(stemIndexBefore(items, i), mutableListOf())
                pendingLabel = lm.groupValues[1]
                lastWasContent = false
            } else {
                if (pendingLabel != null && cur != null) {
                    cur.options.add(
                        OptionBox(
                            pendingLabel!!, item.text,
                            item.bounds.centerX(), item.bounds.centerY(), Rect(item.bounds), item.node
                        )
                    )
                    pendingLabel = null
                    lastWasContent = true
                } else {
                    lastWasContent = false
                }
            }
        }
        if (cur != null && cur.options.size >= 2) groups.add(cur)

        val blocks = groups.mapNotNull { g ->
            val stemRaw = items.getOrNull(g.stemIdx)?.text?.trim()
            if (stemRaw == null || stemRaw.length < 2) return@mapNotNull null
            // 清理题干前缀，如 "1.[单选题] " → ""
            val stem = stemRaw.replace(Regex("^\\d+\\s*[.、]?\\s*\\[[^]]*]\\s*"), "").trim()
            if (stem.length < 2) return@mapNotNull null
            if (isJudge(g.options)) {
                Question(
                    QuestionKind.JUDGE, stem = stem,
                    options = listOf(
                        OptionBox("A", "是", g.options[0].centerX, g.options[0].centerY, Rect(), g.options[0].node),
                        OptionBox("B", "否", g.options[1].centerX, g.options[1].centerY, Rect(), g.options[1].node)
                    ),
                    rawText = raw
                )
            } else {
                Question(QuestionKind.CHOICE, stem = stem, options = g.options, rawText = raw)
            }
        }
        if (blocks.isNotEmpty()) return blocks
        // 页面没有暴露选项标号时，按“题干 + 连续短文本”兜底推断
        return inferFromTexts(items, raw)
    }

    /** 选词填空（完型）：一段带编号空的文章 + A)..O) 词库 */
    private fun parseCloze(items: List<TextItem>, raw: String): Question? {
        if (!isKindEnabled(QuestionKind.CLOZE)) return null
        val hasMarker = items.any {
            it.text.contains("完型") || it.text.contains("完形") ||
                it.text.contains("Word Bank", ignoreCase = true) ||
                it.text.contains("Fill in the blanks", ignoreCase = true)
        }
        if (!hasMarker) return null

        val bank = linkedMapOf<String, String>()
        val pairRegex = Regex(
            "([A-O])\\s*[)）.、:：]\\s*((?:[A-Za-z][A-Za-z'’\\-]*)(?:\\s+(?![A-O]\\s*[)）.、:：])[A-Za-z][A-Za-z'’\\-]*){0,3})"
        )
        for (it in items) {
            for (m in pairRegex.findAll(it.text)) {
                val label = m.groupValues[1]
                val word = m.groupValues[2].trim()
                if (label !in bank) bank[label] = word
            }
        }
        if (bank.size < 4) return null

        val passage = items
            .filter { it.text.length > 80 }
            .maxByOrNull { t -> Regex("\\s\\d{1,2}\\s").findAll(t.text).count() }
            ?.text ?: return null
        if (Regex("\\s\\d{1,2}\\s").findAll(passage).count() < 2) return null

        val options = bank.map { (l, w) -> OptionBox(l, w, 0, 0, Rect()) }
        return Question(QuestionKind.CLOZE, stem = passage, options = options, rawText = raw)
    }

    private fun parseClozeAnswer(text: String): List<Pair<Int, String>> =
        Regex("(\\d{1,2})\\s*[=:：]\\s*([A-O])").findAll(text)
            .map { it.groupValues[1].toInt() to it.groupValues[2] }
            .distinctBy { it.first }
            .toList()

    private fun findNodeByTextExact(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.trim() == text) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findNodeByTextExact(c, text)?.let { return it }
        }
        return null
    }

    private fun collectLetterNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val t = node.text?.toString()?.trim()
        if (t != null && t.length == 1 && t[0] in 'A'..'O') out.add(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectLetterNodes(it, out) }
    }

    private fun clickClozeBlank(blank: Int, letter: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val container = findNodeByTextExact(root, blank.toString())
        if (container != null) {
            val target = findNodeByTextExact(container, letter)
                ?: container.parent?.let { findNodeByTextExact(it, letter) }
            if (target != null && clickNode(target)) return true
        }
        val letters = mutableListOf<AccessibilityNodeInfo>()
        collectLetterNodes(root, letters)
        val idx = (blank - 1) * 15 + (letter[0] - 'A')
        return clickNode(letters.getOrNull(idx))
    }

    private fun inferFromTexts(items: List<TextItem>, raw: String): List<Question> {
        val scoreLike = Regex("[（(]\\s*\\d+(?:\\.\\d+)?\\s*分\\s*[）)]")
        val blankLike = Regex("[（(]\\s*[)）]")
        val stemLike = { t: String ->
            t.contains("？") || t.contains("?") ||
                scoreLike.containsMatchIn(t) ||
                blankLike.containsMatchIn(t) ||
                Regex("_{2,}").containsMatchIn(t)
        }
        val sectionHeader = Regex("^[一二三四五六七八九十]+、\\s*(.+)")
        val progressLike = Regex("^此题已选择\\s*\\d+\\s*/\\s*\\d+\\s*项$")
        val prefixLike = Regex("^\\d+\\s*[.、]\\s*\\[[^\\]]*]\\s*")
        val skipWords = setOf(
            "提交", "保存", "保存草稿", "下一节", "上一节", "上一页", "下一页",
            "由此作答", "待完成", "大纲", "WPS表单"
        )

        var sectionKind = ""
        val result = mutableListOf<Question>()
        var i = 0
        while (i < items.size) {
            val rawStem = items[i].text.trim()

            val sec = sectionHeader.matchEntire(rawStem)
            if (sec != null) {
                sectionKind = sec.groupValues[1]
                i++
                continue
            }

            if (!stemLike(rawStem)) {
                i++
                continue
            }

            val stem = rawStem
                .replace(prefixLike, "")
                .replace(scoreLike, "")
                .trim()
            if (stem.length < 2) {
                i++
                continue
            }

            val judge = sectionKind.contains("判断") ||
                rawStem.contains("[判断]") || rawStem.contains("判断题")
            val multi = sectionKind.contains("多选") ||
                rawStem.contains("[多选]") || rawStem.contains("多选题")
            val stemForAi = if (multi) "（多选题）$stem" else stem

            var j = i + 1
            while (j < items.size) {
                val s = items[j].text.trim()
                if (s == rawStem || s == stem || progressLike.matches(s)) {
                    j++
                    continue
                }
                break
            }

            val opts = mutableListOf<OptionBox>()
            var label = 'A'
            while (j < items.size && opts.size < 26) {
                val s = items[j].text.trim()
                if (stemLike(s) || sectionHeader.containsMatchIn(s) || progressLike.matches(s)) break
                if (s in skipWords) {
                    j++
                    continue
                }
                if (s.length in 1..60) {
                    opts.add(
                        OptionBox(
                            label.toString(), s,
                            items[j].bounds.centerX(), items[j].bounds.centerY(),
                            Rect(items[j].bounds), items[j].node
                        )
                    )
                    label++
                    j++
                } else {
                    j++
                }
            }

            if (opts.size >= 2) {
                val isJudge = judge || isJudge(opts)
                val finalOpts = if (isJudge) {
                    listOf(
                        OptionBox("A", "是", opts[0].centerX, opts[0].centerY, Rect(), opts[0].node),
                        OptionBox("B", "否", opts[1].centerX, opts[1].centerY, Rect(), opts[1].node)
                    )
                } else {
                    opts
                }
                result.add(
                    Question(
                        if (isJudge) QuestionKind.JUDGE else QuestionKind.CHOICE,
                        stem = if (isJudge) stem else stemForAi,
                        options = finalOpts,
                        rawText = raw
                    )
                )
            } else if (judge) {
                result.add(
                    Question(
                        QuestionKind.JUDGE, stem = stem,
                        options = listOf(
                            OptionBox("A", "是", 0, 0, Rect()),
                            OptionBox("B", "否", 0, 0, Rect())
                        ),
                        rawText = raw
                    )
                )
            }
            i = j
        }
        return result
    }

    private fun stemIndexBefore(items: List<TextItem>, idx: Int): Int {
        val limit = items.getOrNull(idx)?.bounds?.top ?: 0
        val labelLike = Regex("^\\s*[A-H]\\s*[.、．:：]?\\s*$")
        var best = -1
        for (j in idx - 1 downTo 0) {
            val t = items[j].text
            if (t.length < 2) continue
            if (labelLike.matches(t)) continue
            if (limit > 0 && items[j].bounds.bottom > limit) continue
            if (best < 0 || items[j].bounds.bottom > items[best].bounds.bottom) best = j
        }
        return if (best >= 0) best else (idx - 1).coerceAtLeast(0)
    }

    private fun isJudge(options: List<OptionBox>): Boolean {
        if (options.size != 2) return false
        val a = options[0].text
        val b = options[1].text
        return (a.contains("正确") && b.contains("错误")) ||
            (a.contains("错误") && b.contains("正确")) ||
            (a.contains("是") && b.contains("否")) ||
            (a.contains("否") && b.contains("是")) ||
            (a.contains("对") && b.contains("错")) ||
            (a.contains("错") && b.contains("对"))
    }

    private fun solveAndShow(question: Question, force: Boolean = false) {
        if (question.kind == QuestionKind.TRANSLATE) {
            // 翻译不走向答题引擎：走整篇管线，块内缓存与并行都在 TranslatePipeline 里
            val fresh = lastTranslateParagraphs.takeIf {
                it.isNotEmpty() && PageTextExtractor.toPageText(it) == question.stem
            }
            val paragraphs = fresh ?: paragraphsFromText(question.stem)
            lastTranslateParagraphs = paragraphs
            startTranslate(paragraphs, force)
            return
        }
        val s = scope ?: return
        s.launch {
            if (editing) return@launch
            val answer = try {
                AnswerEngine.solve(this@AutoAnswerService, question, force)
            } catch (_: Exception) {
                null
            }
            if (editing) return@launch
            if (answer == null) {
                updateOverlay("未找到可靠答案，请手动选择或编辑题目", question, null)
                return@launch
            }
            var q = question
            if (q.kind == QuestionKind.CHOICE && q.options.size < 2) {
                val n = optionCount()
                q = q.copy(options = List(n) { i -> OptionBox(('A' + i).toString(), "", 0, 0, Rect()) })
                lastQuestion = q
            }
            if (q.kind == QuestionKind.CHOICE && answer.options.isNotEmpty()) {
                val aiOpts = answer.options
                val newOptions = q.options.mapIndexed { i, opt ->
                    if (opt.text.isBlank() && i < aiOpts.size && aiOpts[i].isNotBlank()) {
                        opt.copy(text = aiOpts[i])
                    } else opt
                }
                if (newOptions != q.options) {
                    q = q.copy(options = newOptions)
                    lastQuestion = q
                }
            }
            updateOverlay(answerHeadline(q, answer), q, answer)
            val autoClick = Prefs.getBool(this@AutoAnswerService, "auto_mode", false)
            if (autoClick &&
                (q.kind == QuestionKind.CHOICE || q.kind == QuestionKind.JUDGE) &&
                answer.confidence >= 0.6f
            ) {
                choose(answer.label)
            }
        }
    }

    private fun hasMulti(stem: String): Boolean =
        Regex("^\\s*[（(【]\\s*多选题\\s*[）)】]").containsMatchIn(stem)

    private fun stemKey(stem: String): String =
        stem.replace(Regex("^\\s*[（(【]\\s*多选题\\s*[）)】]\\s*"), "").trim()

    private fun withMulti(stem: String, multi: Boolean): String {
        val stripped = stemKey(stem)
        return if (multi) "（多选题）$stripped" else stripped
    }

    private fun setMultiChoice(multi: Boolean) {
        val q = lastQuestion
        if (q == null) {
            updateOverlay("请先识别题目", null, null)
            return
        }
        if (q.kind != QuestionKind.CHOICE) return
        if (editing) {
            val cur = etStem?.text?.toString() ?: ""
            val newStem = withMulti(cur, multi)
            etStem?.setText(newStem)
            multiPrefs[stemKey(newStem)] = multi
            btnChoiceSingle?.text = if (!multi) "●单选题" else "单选题"
            btnChoiceMulti?.text = if (multi) "●多选题" else "多选题"
            return
        }
        val newStem = withMulti(q.stem, multi)
        if (newStem == q.stem) return
        val newQ = q.copy(stem = newStem)
        lastQuestion = newQ
        multiPrefs[stemKey(newStem)] = multi
        if (currentIndex in detectedQuestions.indices) detectedQuestions[currentIndex] = newQ
        lastTitle = newQ.query
        lastSeenAt = System.currentTimeMillis()
        renderTypeButtons(newQ.kind)
        updateOverlay("已切换为${if (multi) "多选题" else "单选题"}，正在重新解题…", newQ, null)
        solveAndShow(newQ, force = true)
    }

    private fun resolveCurrent() {
        val q = lastQuestion
        if (q == null) {
            updateOverlay("暂无可重新解题的题目，请先识别", null, null)
            return
        }
        if (editing) {
            updateOverlay("请先完成编辑", null, null)
            return
        }
        lastSeenAt = System.currentTimeMillis()
        updateOverlay("重新解题中（已跳过缓存与题库）…", q, null)
        solveAndShow(q, force = true)
    }

    private fun answerHeadline(q: Question?, a: Answer): String = when (q?.kind) {
        QuestionKind.ESSAY -> "答案：${a.text.ifBlank { a.label }}（${a.source}）"
        QuestionKind.TRANSLATE -> "译文（${a.source}）"
        QuestionKind.CLOZE -> "答案：${a.text}（${a.source}）"
        QuestionKind.JUDGE -> "推荐：${judgeText(a.label)}（${a.source}）"
        else -> "推荐：${a.label}（${a.source}）"
    }

    private fun judgeText(label: String) = when (label) {
        "A" -> "是"
        "B" -> "否"
        else -> label
    }

    private fun answerLabelSet(q: Question?, a: Answer?): Set<String> {
        if (a == null) return emptySet()
        if (q?.kind == QuestionKind.JUDGE) {
            return when (a.label.uppercase()) {
                "A", "是", "正确", "对", "TRUE" -> setOf("A")
                "B", "否", "错误", "错", "FALSE" -> setOf("B")
                else -> emptySet()
            }
        }
        val letters = a.label.toCharArray().map { it.toString() }.toSet()
        // 单选题只允许一个答案
        if (q != null && q.kind == QuestionKind.CHOICE && !hasMulti(q.stem)) {
            return letters.take(1).toSet()
        }
        return letters
    }

    /** 单选题选项着色：推荐=绿，其余按接近度 黄绿→琥珀→橙→红；必错选项不着色 */
    private fun optionColors(q: Question, a: Answer?): Map<String, Int> {
        if (a == null || q.kind != QuestionKind.CHOICE) return emptyMap()
        if (hasMulti(q.stem)) {
            return answerLabelSet(q, a).associateWith { ANSWER_COLOR }
        }
        val wrong = a.wrong.map { it.uppercase() }.toSet()
        val rank = (listOf(a.label.uppercase().replace(" ", "").take(1)) + a.rank.map { it.uppercase() })
            .filter { it.length == 1 }
            .distinct()
            .filter { it !in wrong }
        val palette = intArrayOf(
            0xFF2E7D32.toInt(),
            0xFF7CB342.toInt(),
            0xFFF9A825.toInt(),
            0xFFEF6C00.toInt(),
            0xFFC62828.toInt()
        )
        return rank.mapIndexed { idx, l -> l to palette[idx.coerceAtMost(palette.size - 1)] }.toMap()
    }

    // ---------- 点击 ----------

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var p = node.parent
        while (p != null) {
            if (p.isClickable) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            p = p.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = findNodeByText(c, text)
            if (found != null) return found
        }
        return null
    }

    private fun choose(label: String) {
        val q = lastQuestion ?: return
        lastSeenAt = System.currentTimeMillis()
        setCollapsed(true)
        pauseUntil = System.currentTimeMillis() + 6000
        val s = scope ?: return
        s.launch {
            delay(250)
            for (ch in label.toCharArray()) {
                val opt = q.options.firstOrNull { it.label == ch.toString() } ?: continue
                var ok = clickNode(opt.node)
                if (!ok) {
                    val root = rootInActiveWindow
                    val n = root?.let { findNodeByText(it, opt.text.take(8)) }
                    ok = clickNode(n)
                }
                if (!ok && opt.centerX > 0 && opt.centerY > 0) {
                    ok = tapAt(opt.centerX.toFloat(), opt.centerY.toFloat())
                }
                delay(400)
            }
        }
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ---------- 题型切换 ----------

    private fun onTypeButton(kind: QuestionKind) {
        if (!isKindEnabled(kind)) return
        if (editing) {
            currentKind = kind
            renderTypeButtons(kind)
            applyEditUiForKind(kind, lastQuestion?.options ?: emptyList())
        } else {
            switchKindAndSolve(kind)
        }
    }

    private fun switchKindAndSolve(kind: QuestionKind) {
        if (kind == QuestionKind.TRANSLATE) {
            // 切到翻译=重新识别整页：清掉旧缓存与段落，再走一次采集
            currentKind = kind
            renderTypeButtons(kind)
            resetTranslateCache()
            updateOverlay("已切换为翻译，正在识别整页文本…", null, null)
            readAndSolve(force = true)
            return
        }
        val q = lastQuestion
        if (q == null) {
            currentKind = kind
            renderTypeButtons(kind)
            updateOverlay("已选择${kind.label}，请先识别题目", null, null)
            return
        }
        val newQ = when (kind) {
            QuestionKind.CHOICE -> {
                val opts = if (q.options.size >= 2) q.options.take(26)
                else List(optionCount()) { i -> OptionBox(('A' + i).toString(), "", 0, 0, Rect()) }
                q.copy(kind = QuestionKind.CHOICE, subQuestion = "", options = opts)
            }
            QuestionKind.JUDGE -> {
                val old = q.options
                val opts = if (old.size >= 2) {
                    listOf(old[0].copy(label = "A", text = "是"), old[1].copy(label = "B", text = "否"))
                } else {
                    listOf(OptionBox("A", "是", 0, 0, Rect()), OptionBox("B", "否", 0, 0, Rect()))
                }
                q.copy(kind = QuestionKind.JUDGE, stem = withMulti(q.stem, false), subQuestion = "", options = opts)
            }
            QuestionKind.ESSAY -> q.copy(kind = QuestionKind.ESSAY, stem = withMulti(q.stem, false), options = emptyList())
            QuestionKind.TRANSLATE -> q.copy(
                kind = QuestionKind.TRANSLATE, stem = withMulti(q.stem, false),
                subQuestion = "", options = emptyList()
            )
            QuestionKind.CLOZE -> q.copy(kind = QuestionKind.CLOZE, subQuestion = "")
        }
        currentKind = kind
        lastQuestion = newQ
        lastTitle = newQ.query
        lastSeenAt = System.currentTimeMillis()
        updateOverlay("已切换为${kind.label}，正在搜索答案…", newQ, null)
        solveAndShow(newQ)
    }

    private fun isKindEnabled(kind: QuestionKind): Boolean = when (kind) {
        QuestionKind.CHOICE -> Prefs.getBool(this, "kind_choice", true)
        QuestionKind.JUDGE -> Prefs.getBool(this, "kind_judge", true)
        QuestionKind.ESSAY -> Prefs.getBool(this, "kind_essay", true)
        QuestionKind.CLOZE -> Prefs.getBool(this, "kind_cloze", true)
        QuestionKind.TRANSLATE -> Prefs.getBool(this, "kind_translate", true)
    }

    private fun renderTypeButtons(kind: QuestionKind) {
        val choiceOn = isKindEnabled(QuestionKind.CHOICE)
        val judgeOn = isKindEnabled(QuestionKind.JUDGE)
        val essayOn = isKindEnabled(QuestionKind.ESSAY)
        val clozeOn = isKindEnabled(QuestionKind.CLOZE)
        val translateOn = isKindEnabled(QuestionKind.TRANSLATE)

        btnTypeChoice?.text = if (kind == QuestionKind.CHOICE) "●选择题" else "选择题"
        btnTypeJudge?.text = if (kind == QuestionKind.JUDGE) "●判断题" else "判断题"
        btnTypeEssay?.text = if (kind == QuestionKind.ESSAY) "●大题" else "大题"
        btnTypeCloze?.text = if (kind == QuestionKind.CLOZE) "●完型填空" else "完型填空"
        val panelOpen = clipPanel?.visibility == View.VISIBLE
        btnTypeClipboard?.text = if (panelOpen) "●剪贴板" else "剪贴板"
        btnTypeClipboard?.visibility =
            if (Prefs.getBool(this, "kind_clipboard", true)) View.VISIBLE else View.GONE
        btnTypeTranslate?.text = if (kind == QuestionKind.TRANSLATE) "●翻译" else "翻译"

        btnTypeChoice?.visibility = if (choiceOn) View.VISIBLE else View.GONE
        btnTypeJudge?.visibility = if (judgeOn) View.VISIBLE else View.GONE
        btnTypeEssay?.visibility = if (essayOn) View.VISIBLE else View.GONE
        btnTypeCloze?.visibility = if (clozeOn) View.VISIBLE else View.GONE
        btnTypeTranslate?.visibility = if (translateOn) View.VISIBLE else View.GONE

        val isChoice = kind == QuestionKind.CHOICE && choiceOn
        choiceModeRow?.visibility = if (isChoice) View.VISIBLE else View.GONE
        val multi = hasMulti(lastQuestion?.stem ?: etStem?.text?.toString() ?: "")
        btnChoiceSingle?.text = if (isChoice && !multi) "●单选题" else "单选题"
        btnChoiceMulti?.text = if (isChoice && multi) "●多选题" else "多选题"
    }

    // ---------- 编辑 ----------

    private fun startEdit() {
        val manual = lastQuestion == null
        val q = lastQuestion ?: Question(
            currentKind,
            stem = "",
            options = if (currentKind == QuestionKind.CHOICE) {
                List(optionCount()) { i -> OptionBox(('A' + i).toString(), "", 0, 0, Rect()) }
            } else {
                emptyList()
            }
        )
        editing = true
        currentKind = q.kind
        renderTypeButtons(q.kind)
        etStem?.setText(q.stem)
        etSub?.setText(q.subQuestion)
        applyEditUiForKind(q.kind, q.options)
        setEditUi(true)
        setCollapsed(true)
        lastFocusedEditor = etStem
        updateOverlay(
            if (manual) "手动编辑：粘贴或输入题目后点确认" else "编辑题目中，确认后开始解题",
            null, null
        )
    }

    private fun applyEditUiForKind(kind: QuestionKind, options: List<OptionBox>) {
        etStem?.hint = when (kind) {
            QuestionKind.TRANSLATE -> "要翻译的文本"
            QuestionKind.CLOZE -> "文章原文"
            QuestionKind.ESSAY -> "主题干区"
            else -> "题干区"
        }
        etSub?.visibility = if (kind == QuestionKind.ESSAY) View.VISIBLE else View.GONE
        tvJudgeOptions?.visibility = if (kind == QuestionKind.JUDGE) View.VISIBLE else View.GONE
        val choice = kind == QuestionKind.CHOICE
        choiceModeRow?.visibility = if (choice) View.VISIBLE else View.GONE
        val multi = hasMulti(etStem?.text?.toString() ?: "")
        btnChoiceSingle?.text = if (choice && !multi) "●单选题" else "单选题"
        btnChoiceMulti?.text = if (choice && multi) "●多选题" else "多选题"
        optionCountRow?.visibility = if (choice) View.VISIBLE else View.GONE
        optionsEditContainer?.visibility = if (choice) View.VISIBLE else View.GONE
        optionsEditActions?.visibility = if (choice) View.VISIBLE else View.GONE
        if (choice) {
            val count = optionCount()
            etOptionCount?.setText(count.toString())
            rebuildToCount(count, options.map { it.text })
        }
    }

    private fun rebuildToCount(count: Int, texts: List<String>) {
        optionEditors.clear()
        optionsEditContainer?.removeAllViews()
        optionEditCount = count.coerceIn(2, 26)
        for (i in 0 until optionEditCount) {
            addOptionRow(texts.getOrNull(i) ?: "")
        }
        etOptionCount?.setText(optionEditCount.toString())
    }

    private fun applyOptionCount() {
        val n = etOptionCount?.text?.toString()?.trim()?.toIntOrNull() ?: return
        val count = n.coerceIn(2, 26)
        Prefs.set(this, "option_count", count.toString())
        rebuildToCount(count, currentEditorTexts())
    }

    private fun addOptionRow(text: String) {
        val container = optionsEditContainer ?: return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            this.text = ('A' + container.childCount).toString() + ". "
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
        }
        val et = EditText(this).apply {
            setText(text)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            isLongClickable = true
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) lastFocusedEditor = this }
        }
        row.addView(label)
        row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(row)
        optionEditors.add(et)
    }

    private fun currentEditorTexts(): List<String> = optionEditors.map { it.text.toString().trim() }

    private fun activeEditor(): EditText? = lastFocusedEditor ?: etStem

    private fun dpInt(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toggleClipPanel() {
        val panel = clipPanel ?: return
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
        } else {
            panel.visibility = View.VISIBLE
            rebuildClipList()
        }
        renderTypeButtons(currentKind)
    }

    /** 复制到系统（输入法）剪贴板；应用内同时保留 */
    private fun copyClipItem(text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("题目", text))
            Toast.makeText(this, "已复制到输入法剪贴板", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 每条内容：与「单选题」按钮同高，单行超出省略 */
    private fun clipChip(text: String): Button =
        Button(this).apply {
            this.text = text.replace("\n", " ")
            textSize = 11f
            minHeight = 0
            minWidth = 0
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dpInt(12), 0, dpInt(12), 0)
            background = pillBackground(0x1FFFFFFF, 14f)
            setTextColor(0xFFF2F5EF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpInt(32)
            ).apply { topMargin = dpInt(6) }
            setOnClickListener { copyClipItem(text) }
        }

    private fun rebuildClipList() {
        val container = clipList ?: return
        container.removeAllViews()
        val items = ClipStore.load(this)
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无内容：复制任意文字后会自动存入"
                setTextColor(0xFFAFB4A9.toInt())
                textSize = 10f
            })
            return
        }
        items.forEach { value -> container.addView(clipChip(value)) }
    }

    private fun selectAllEditorText() {
        val et = activeEditor() ?: return
        et.requestFocus()
        et.setSelection(0, et.text.length)
    }

    private fun copyEditorText() {
        val et = activeEditor() ?: return
        val content = et.text ?: return
        val start = minOf(et.selectionStart, et.selectionEnd).coerceIn(0, content.length)
        val end = maxOf(et.selectionStart, et.selectionEnd).coerceIn(0, content.length)
        val piece = if (end > start) content.subSequence(start, end) else content
        if (piece.isEmpty()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("题目", piece))
        Toast.makeText(this, "已复制 ${piece.length} 字", Toast.LENGTH_SHORT).show()
    }

    private fun pasteIntoEditor() {
        val et = activeEditor() ?: return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return
        val pasted = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        if (pasted.isEmpty()) return
        val content = et.text ?: return
        val start = minOf(et.selectionStart, et.selectionEnd).coerceIn(0, content.length)
        val end = maxOf(et.selectionStart, et.selectionEnd).coerceIn(0, content.length)
        content.replace(start, end, pasted)
        et.setSelection((start + pasted.length).coerceAtMost(content.length))
        Toast.makeText(this, "已粘贴", Toast.LENGTH_SHORT).show()
    }

    private fun addOptionRow() {
        val newCount = (optionEditCount + 1).coerceAtMost(26)
        Prefs.set(this, "option_count", newCount.toString())
        rebuildToCount(newCount, currentEditorTexts())
    }

    private fun removeOptionRow() {
        val newCount = (optionEditCount - 1).coerceAtLeast(2)
        Prefs.set(this, "option_count", newCount.toString())
        rebuildToCount(newCount, currentEditorTexts())
    }

    private fun confirmEdit() {
        try {
            val stem = etStem?.text?.toString()?.trim() ?: ""
            if (stem.isEmpty()) {
                updateOverlay("内容不能为空", null, null)
                return
            }
            if (currentKind == QuestionKind.CHOICE) applyOptionCount()
            val sub = etSub?.text?.toString()?.trim() ?: ""
            val old = lastQuestion?.options ?: emptyList()
            val options = when (currentKind) {
                QuestionKind.CHOICE -> currentEditorTexts().mapIndexed { i, t ->
                    val o = old.getOrNull(i)
                    OptionBox(('A' + i).toString(), t, o?.centerX ?: 0, o?.centerY ?: 0, o?.box ?: Rect(), o?.node)
                }
                QuestionKind.JUDGE -> listOf(
                    OptionBox("A", "是", 0, 0, Rect(), old.getOrNull(0)?.node),
                    OptionBox("B", "否", 0, 0, Rect(), old.getOrNull(1)?.node)
                )
                QuestionKind.CLOZE -> old
                QuestionKind.ESSAY, QuestionKind.TRANSLATE -> emptyList()
            }
            val newQ = Question(currentKind, stem, sub, options, lastQuestion?.rawText ?: "")
            setEditUi(false)
            editing = false
            lastQuestion = newQ
            if (currentIndex in detectedQuestions.indices) detectedQuestions[currentIndex] = newQ
            lastTitle = newQ.query
            lastSeenAt = System.currentTimeMillis()
            updateOverlay("已更新，正在处理…", newQ, null)
            solveAndShow(newQ)
        } catch (e: Exception) {
            setEditUi(false)
            editing = false
            updateOverlay("处理失败：${e.message}", null, null)
        }
    }

    private fun cancelEdit() {
        setEditUi(false)
        editing = false
        updateOverlay("已取消编辑", lastQuestion, null)
    }

    private fun setEditUi(visible: Boolean) {
        editArea?.visibility = if (visible) View.VISIBLE else View.GONE
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val lp = overlayLp ?: return
        if (visible) {
            lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            overlayView?.let { wm.updateViewLayout(it, lp) }
            etStem?.requestFocus()
            etStem?.postDelayed({
                if (etStem?.hasFocus() == true) {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(etStem, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 250)
        } else {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etStem?.windowToken, 0)
            etStem?.clearFocus()
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            overlayView?.let { wm.updateViewLayout(it, lp) }
        }
    }

    // ---------- 悬浮窗 ----------

    private fun showOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        tvResult = view.findViewById(R.id.tv_result)
        tvQuestion = view.findViewById(R.id.tv_question)
        tvReason = view.findViewById(R.id.tv_reason)
        optionsContainer = view.findViewById(R.id.options_container)
        btnCollapse = view.findViewById(R.id.btn_collapse)
        btnTypeChoice = view.findViewById(R.id.btn_type_choice)
        btnTypeJudge = view.findViewById(R.id.btn_type_judge)
        btnTypeEssay = view.findViewById(R.id.btn_type_essay)
        btnTypeTranslate = view.findViewById(R.id.btn_type_translate)
        btnTypeCloze = view.findViewById(R.id.btn_type_cloze)
        btnTypeClipboard = view.findViewById(R.id.btn_type_clipboard)
        choiceModeRow = view.findViewById(R.id.choice_mode_row)
        btnChoiceSingle = view.findViewById(R.id.btn_choice_single)
        btnChoiceMulti = view.findViewById(R.id.btn_choice_multi)
        editArea = view.findViewById(R.id.edit_area)
        etStem = view.findViewById(R.id.et_stem)
        etSub = view.findViewById(R.id.et_sub)
        tvJudgeOptions = view.findViewById(R.id.tv_judge_options)
        optionsEditContainer = view.findViewById(R.id.options_edit_container)
        optionsEditActions = view.findViewById(R.id.options_edit_actions)
        optionCountRow = view.findViewById(R.id.option_count_row)
        etOptionCount = view.findViewById(R.id.et_option_count)
        clipPanel = view.findViewById(R.id.clip_panel)
        clipList = view.findViewById(R.id.clip_list)
        view.findViewById<MaxHeightScrollView>(R.id.content_scroll)?.maxHeight =
            resources.displayMetrics.heightPixels / 3

        // “框题”按钮在本版本不需要，隐藏
        view.findViewById<Button>(R.id.btn_region)?.visibility = View.GONE

        etStem?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) lastFocusedEditor = etStem }
        etSub?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) lastFocusedEditor = etSub }
        etOptionCount?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                lastFocusedEditor = etOptionCount
            } else {
                applyOptionCount()
            }
        }
        etOptionCount?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyOptionCount()
                etOptionCount?.clearFocus()
                true
            } else {
                false
            }
        }

        view.findViewById<Button>(R.id.btn_capture).setOnClickListener {
            resetTranslateCache()
            readAndSolve(force = true)
        }
        view.findViewById<Button>(R.id.btn_prev).setOnClickListener { prevQuestion() }
        view.findViewById<Button>(R.id.btn_next).setOnClickListener { nextQuestion() }
        view.findViewById<Button>(R.id.btn_edit).setOnClickListener { startEdit() }
        view.findViewById<Button>(R.id.btn_edit_confirm).setOnClickListener { confirmEdit() }
        view.findViewById<Button>(R.id.btn_edit_cancel).setOnClickListener { cancelEdit() }
        btnTypeClipboard?.setOnClickListener { toggleClipPanel() }
        view.findViewById<Button>(R.id.btn_sel_all).setOnClickListener { selectAllEditorText() }
        view.findViewById<Button>(R.id.btn_copy).setOnClickListener { copyEditorText() }
        view.findViewById<Button>(R.id.btn_paste).setOnClickListener { pasteIntoEditor() }
        view.findViewById<Button>(R.id.btn_add_option).setOnClickListener { addOptionRow() }
        view.findViewById<Button>(R.id.btn_remove_option).setOnClickListener { removeOptionRow() }
        btnTypeChoice?.setOnClickListener { onTypeButton(QuestionKind.CHOICE) }
        btnTypeJudge?.setOnClickListener { onTypeButton(QuestionKind.JUDGE) }
        btnTypeEssay?.setOnClickListener { onTypeButton(QuestionKind.ESSAY) }
        btnTypeTranslate?.setOnClickListener { onTypeButton(QuestionKind.TRANSLATE) }
        btnTypeCloze?.setOnClickListener { onTypeButton(QuestionKind.CLOZE) }
        renderTypeButtons(currentKind)
        view.findViewById<Button>(R.id.btn_resolve).setOnClickListener { resolveCurrent() }
        btnChoiceSingle?.setOnClickListener { setMultiChoice(false) }
        btnChoiceMulti?.setOnClickListener { setMultiChoice(true) }
        btnCollapse?.setOnClickListener { collapseToBall() }
        view.findViewById<Button>(R.id.btn_close).setOnClickListener { stopHelper() }

        // 译文区控制器：只接管 overlay_root 内的 translate_card 子树，不改动既有控件
        OverlayTranslateController(view).also { controller ->
            controller.setMode(translateModePref())
            controller.setOnModeChange { mode -> Prefs.set(this, "translate_mode", mode.name) }
            overlayTranslateController = controller
        }

        overlayLp = lp
        wm.addView(view, lp)
        overlayView = view
        renderTypeButtons(currentKind)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        overlayLp = null
        // 译文区随窗口一起消失：清掉在途任务与缓存，避免下次进入还残留旧译文
        translateJob?.cancel()
        translateJob = null
        ocrJob?.cancel()
        ocrJob = null
        ocrBusy = false
        ocrPending = false
        translateToken++
        ocrToken++
        translatePipeline?.invalidate()
        translatePipeline = null
        lastTranslateParagraphs = emptyList()
        lastPageTranslation = null
        lastRoundTexts = emptyList()
        translateRoundBusy = false
        translateActive = false
        overlayTranslateController = null
    }

    private fun updateOverlay(status: String, question: Question?, answer: Answer?) {
        // 翻译模式只更新状态时 question 为 null，不能据此判定“已经离开翻译”：
        // 否则每条进度/提示都会把 translateActive 打成 false 并清空缓存，
        // 译文回来时被丢弃（表现为点了“识别”什么都不出）。
        val translateOn = question?.kind == QuestionKind.TRANSLATE ||
            (question == null && currentKind == QuestionKind.TRANSLATE)
        setTranslateUi(translateOn)
        tvResult?.text = status
        // 翻译模式下正文交给译文区，题干区让位
        tvQuestion?.text = if (translateOn) "" else question?.let {
            when (it.kind) {
                QuestionKind.ESSAY ->
                    if (it.subQuestion.isBlank()) it.stem else it.stem + "\n问题：" + it.subQuestion
                else -> it.stem
            }
        } ?: ""
        val container = optionsContainer
        if (container == null) return
        container.removeAllViews()
        if (question != null) {
            lastQuestion = question
            currentKind = question.kind
            renderTypeButtons(question.kind)
            val correct = answerLabelSet(question, answer)
            when (question.kind) {
                QuestionKind.CHOICE -> {
                    val colors = optionColors(question, answer)
                    for (opt in question.options) {
                        container.addView(
                            makeOptionButton(opt.label + ". " + opt.text, opt.label, colors[opt.label])
                        )
                    }
                }
                QuestionKind.JUDGE -> {
                    container.addView(makeOptionButton("A. 是", "A", if ("A" in correct) ANSWER_COLOR else null))
                    container.addView(makeOptionButton("B. 否", "B", if ("B" in correct) ANSWER_COLOR else null))
                }
                QuestionKind.CLOZE -> {
                    val pairs = parseClozeAnswer(answer?.text ?: "")
                    if (pairs.isEmpty()) {
                        tvQuestion?.text = "文章：\n" + question.stem.take(300)
                    } else {
                        for ((blank, letter) in pairs) {
                            container.addView(makeBlankButton(blank, letter))
                        }
                    }
                }
                QuestionKind.ESSAY, QuestionKind.TRANSLATE -> Unit
            }
        }
        tvReason?.text = if (translateOn) "" else answer?.let {
            "来源：" + it.source + "  置信度：" + (it.confidence * 100).toInt() + "%  理由：" + it.reason
        } ?: ""
        setCollapsed(question == null && answer == null && collapsed)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun pillBackground(color: Int, radiusDp: Float = 12f): Drawable {
        val solid = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(color)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), solid, null)
    }

    private fun makeOptionButton(text: String, label: String, highlightColor: Int? = null): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            minHeight = 0
            setPadding(16, 9, 16, 9)
            background = if (highlightColor != null) {
                setTextColor(0xFFFFFFFF.toInt())
                pillBackground(highlightColor)
            } else {
                setTextColor(0xFFF2F5EF.toInt())
                pillBackground(0x1FFFFFFF)
            }
            setOnClickListener { choose(label) }
        }
    }

    private fun makeBlankButton(blank: Int, letter: String): Button {
        return Button(this).apply {
            text = "第" + blank + "空 → " + letter
            textSize = 12f
            minHeight = 0
            setPadding(16, 9, 16, 9)
            background = pillBackground(ANSWER_COLOR)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { clickClozeBlank(blank, letter) }
        }
    }

    private fun setCollapsed(c: Boolean) {
        collapsed = c
        val visible = if (c) View.GONE else View.VISIBLE
        tvQuestion?.visibility = if (tvQuestion?.text?.isNotEmpty() == true) visible else View.GONE
        optionsContainer?.visibility = if ((optionsContainer?.childCount ?: 0) > 0) visible else View.GONE
        tvReason?.visibility = if (tvReason?.text?.isNotEmpty() == true) visible else View.GONE
        btnCollapse?.text = if (c) "展开" else "收起"
    }

    // ---------- 翻译 ----------

    /**
     * 悬浮窗在屏幕上的底边绝对 y（未显示时返回 0）。
     * 悬浮窗是 TYPE_APPLICATION_OVERLAY + gravity=TOP，窗口顶边不在 y=0（被状态栏顶下一截），
     * 所以“要不要裁 / 裁多少”必须用屏幕绝对坐标，只取 height 会漏掉悬浮窗最后一行。
     */
    private fun overlayBottomPx(): Int {
        val view = overlayView ?: return 0
        if (view.visibility != View.VISIBLE) return 0
        val height = view.height
        if (height <= 0) return 0
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val top = if (loc[1] > 0) loc[1] else 0
        return top + height
    }

    /** 状态栏高度（px）：隐藏悬浮窗截屏时用来把状态栏排除在正文之外。 */
    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val value = if (id > 0) resources.getDimensionPixelSize(id) else 0
        return (value + dp(4)).coerceAtLeast(0)
    }

    /** 等 UI 真正画完若干帧：临时隐藏悬浮窗后必须等合成器出新帧，否则截到的还是旧画面。 */
    private suspend fun awaitFrames(count: Int) {
        repeat(count) {
            suspendCancellableCoroutine<Unit> { cont ->
                Choreographer.getInstance().postFrameCallback {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    /**
     * 截屏 OCR -> TextNode -> 段落，坐标保持整屏像素；任何异常都退化成空列表。
     *
     * 悬浮窗自身的画面必须排除，否则按钮/译文会被 OCR 成“原文”再翻一遍（自反馈污染）：
     * - 悬浮窗占小半屏：按它的屏幕底边裁掉顶部，不闪屏；
     * - 悬浮窗盖住大半屏（翻译卡片展开时）：截屏前临时隐藏窗口，截完立刻恢复，
     *   否则裁完只剩很窄一条，反而会把译文卡片的下半部分当成正文。
     */
    private suspend fun captureOcrParagraphs(): List<PageParagraph> {
        val screenHeight = resources.displayMetrics.heightPixels
        val bottom = overlayBottomPx()
        val hideOverlay = bottom * 2 > screenHeight
        // 悬浮窗底边按 widget 高度测出来会少最后一行的几像素（按钮文字下半截仍被截进画面，
        // 会被 OCR 成"选择题/判断题/大题/翻译"混进正文），所以再留一截安全边距。
        val margin = dp(16)
        // 隐藏悬浮窗时整屏参与识别，但状态栏（时间/运营商）不是正文，按状态栏高度裁掉
        val skipTopPx = if (hideOverlay) {
            statusBarHeightPx()
        } else {
            (bottom + margin).coerceIn(0, screenHeight / 2)
        }
        XwLog.d {
            "ocrCapture: overlayBottom=" + bottom + " screenH=" + screenHeight +
                " hide=" + hideOverlay + " skipTopPx=" + skipTopPx
        }

        val view = overlayView
        // 隐藏/恢复必须包在同一个 try/finally 里：中途被取消（新一轮采集/resetTranslateCache）
        // 也要把悬浮窗恢复可见，否则一次取消就把悬浮窗永久藏起来了。
        if (hideOverlay) view?.visibility = View.INVISIBLE
        val nodes = try {
            if (hideOverlay) awaitFrames(2)
            ScreenshotOcr.capture(this, skipTopPx)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        } finally {
            if (hideOverlay && view != null) view.visibility = View.VISIBLE
        }
        XwLog.d { "a11y->ocr: textNodes=" + nodes.size }
        if (nodes.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            try {
                val paragraphs = PageTextExtractor.extract(nodes, dp(6), dp(12))
                XwLog.d { "a11y->ocr: paragraphs=" + paragraphs.size }
                paragraphs
            } catch (e: Exception) {
                XwLog.d { "a11y->ocr: extract threw " + e.javaClass.simpleName + ": " + e.message }
                emptyList()
            }
        }
    }

    /**
     * 截屏 OCR 兜底：在协程里截屏 + 识别（不阻塞主线程），拿到段落后再走与无障碍完全一致的
     * PageTextExtractor -> TranslatePipeline -> 译文区 管线。
     * translateToken 负责丢弃过期结果，ocrBusy/ocrPending 保证同一时刻只有一次截屏在飞。
     */
    private fun startTranslateViaOcr(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            updateOverlay("该页面不提供文本，Android 11 以下无法截屏识别", lastQuestion, null)
            return
        }
        val s = scope ?: return
        // 系统限制截屏频率：上一张还在飞时再发一次会被拒（errorCode=3 INTERVAL_TIME_SHORT）。
        // 之前是“取消旧的 + 立刻开新的”，正好撞上这个限制，结果就是用户看到的“未取到文字”。
        if (ocrBusy) {
            ocrPending = true
            XwLog.d { "ocrBranch: busy -> pending" }
            return
        }
        ocrBusy = true
        val token = ++ocrToken
        XwLog.d { "ocrBranch: enter force=" + force + " thread=" + Thread.currentThread().name }
        ocrJob = s.launch {
            try {
                val since = System.currentTimeMillis() - lastScreenshotAt
                if (since < OCR_MIN_INTERVAL_MS) delay(OCR_MIN_INTERVAL_MS - since)
                val paragraphs = captureOcrParagraphs()
                lastScreenshotAt = System.currentTimeMillis()
                if (token != ocrToken) {
                    XwLog.d { "ocrBranch: stale capture token=" + token + " now=" + ocrToken }
                    return@launch
                }
                if (paragraphs.isEmpty()) {
                    XwLog.d { "ocrBranch: paragraphs empty -> 未取到文字" }
                    updateOverlay("截屏识别未取到文字，请确认正文已显示后重试", lastQuestion, null)
                    return@launch
                }
                translateSource = TranslateSource.OCR
                lastTranslateParagraphs = paragraphs
                XwLog.d { "ocrBranch: paragraphs=" + paragraphs.size + " source=OCR -> startTranslate" }
                startTranslate(paragraphs, force)
            } finally {
                ocrBusy = false
                if (ocrPending) {
                    // 在飞期间用户又点了一次识别：这次结束后补一次（截屏频率由 OCR_MIN_INTERVAL_MS 兜住）
                    ocrPending = false
                    XwLog.d { "ocrBranch: flush pending -> retry once" }
                    startTranslateViaOcr()
                }
            }
        }
    }

    /** 管线实例跨滚动复用：段落级缓存挂在实例上，重建实例等于每次都重新翻整页。 */
    private fun requireTranslatePipeline(): TranslatePipeline {
        translatePipeline?.let { return it }
        val created = TranslatePipeline(
            translator = { text ->
                // 逐段通道：AiClient.ask 已内置翻译提示词与 JSON 解析；null 视为失败，由管线重试/回退原文
                val started = System.currentTimeMillis()
                val answer = AiClient.ask(this@AutoAnswerService, Question(QuestionKind.TRANSLATE, stem = text))
                XwLog.d {
                    "single: len=" + text.length + " cost=" + (System.currentTimeMillis() - started) +
                        "ms ok=" + (answer?.text?.isNotBlank() == true)
                }
                answer?.text ?: throw IllegalStateException("翻译请求失败")
            },
            batchTranslator = { segments ->
                // 批量通道：一次请求翻一批相邻段落，按一行 <<<SEG>>> 分隔回填；null = 退回逐段
                val started = System.currentTimeMillis()
                val result = TranslateBatchClient.translate(this@AutoAnswerService, segments)
                XwLog.d {
                    "batch: segs=" + segments.size + " chars=" + segments.sumOf { it.length } +
                        " cost=" + (System.currentTimeMillis() - started) + "ms parsed=" + (result?.size ?: -1)
                }
                result
            }
        )
        translatePipeline = created
        return created
    }

    /** bind() 会把模式重置为 TRANSLATED，所以每次 bind 都要把持久化模式传进去。 */
    private fun translateModePref(): OverlayTranslateController.Mode =
        try {
            OverlayTranslateController.Mode.valueOf(Prefs.get(this, "translate_mode", "TRANSLATED"))
        } catch (_: IllegalArgumentException) {
            OverlayTranslateController.Mode.TRANSLATED
        }

    /** 手动编辑/兜底文本没有屏幕坐标：按空行切段，保证仍能分段翻译。 */
    private fun paragraphsFromText(text: String): List<PageParagraph> =
        text.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { i, t -> PageParagraph(i, t, 0, 0, t.count { ch -> ch == '\n' } + 1) }

    /** 进入/退出翻译 UI；退出即清缓存与在途任务，避免下次进入直接展示旧译文。 */
    private fun setTranslateUi(active: Boolean) {
        if (active == translateActive) return
        translateActive = active
        if (!active) resetTranslateCache()
    }

    /** 重新识别 / 离开翻译模式 / 关闭悬浮窗时调用：清空块级缓存与段落快照。 */
    private fun resetTranslateCache() {
        // 兜底：任何路径取消采集后都要让悬浮窗回到可见
        overlayView?.visibility = View.VISIBLE
        translateJob?.cancel()
        translateJob = null
        ocrJob?.cancel()
        ocrJob = null
        ocrBusy = false
        ocrPending = false
        translateToken++
        ocrToken++
        translateSource = TranslateSource.A11Y
        translateRoundBusy = false
        lastPageTranslation = null
        lastRoundTexts = emptyList()
        translatePipeline?.invalidate()
        lastTranslateParagraphs = emptyList()
        overlayTranslateController?.clear()
    }

    /**
     * 整篇翻译：整页段落一次性交给 [TranslatePipeline]，块内按预算合并、缓存命中即跳过，
     * 输出按段落顺序拼成整篇文本。翻译期间只有整篇文本在增长，没有任何滚动触发的重翻。
     */
    private fun startTranslate(paragraphs: List<PageParagraph>, force: Boolean = false) {
        if (paragraphs.isEmpty()) {
            updateOverlay("未识别到文本，请对准页面后重试", lastQuestion, null)
            return
        }
        val texts = paragraphs.map { it.text }
        if (translateRoundBusy) {
            // 慢请求还在飞：忽略重复请求（没有滚动跟随，等待期间不会再有自动刷新）
            XwLog.d { "translate: busy -> ignore paragraphs=" + paragraphs.size }
            return
        }
        val cached = lastPageTranslation
        if (!force && cached != null && texts == lastRoundTexts) {
            // 页面文本没变（重复点识别 / 同一页重读）：直接复用上一轮整篇译文
            XwLog.d { "translate: same page -> reuse segments=" + cached.segmentCount }
            showPageTranslation(cached)
            return
        }
        val s = scope ?: return
        val pipeline = requireTranslatePipeline()
        if (force) pipeline.invalidate()
        val token = ++translateToken
        XwLog.d {
            "translate: start token=" + token + " paragraphs=" + paragraphs.size +
                " chars=" + paragraphs.sumOf { it.text.length } + " force=" + force +
                " source=" + translateSource + " active=" + translateActive
        }
        translateRoundBusy = true
        lastRoundTexts = texts
        translateJob = s.launch {
            val page = try {
                withContext(Dispatchers.Default) {
                    pipeline.translatePage(paragraphs) { partial ->
                        handler.post {
                            if (token != translateToken || !translateActive) return@post
                            // 进度只让整篇文本逐批变长 + 状态行计数，不做逐段上屏
                            showPageTranslation(partial)
                            tvResult?.text =
                                "正在翻译 " + partial.segmentCount + "/" + paragraphs.size + " 段"
                        }
                    }
                }
            } catch (e: CancellationException) {
                translateRoundBusy = false
                throw e
            } catch (_: Exception) {
                PageTranslation.EMPTY
            }
            if (token != translateToken) {
                XwLog.d { "translate: drop stale token=" + token + " now=" + translateToken }
                translateRoundBusy = false
                return@launch
            }
            translateRoundBusy = false
            XwLog.d {
                "translate: done token=" + token + " segments=" + page.segmentCount +
                    " failed=" + page.failedCount + " chars=" + page.translated.length
            }
            if (page.isEmpty) {
                tvResult?.text = "未识别到文本，请对准页面后重试"
                return@launch
            }
            lastPageTranslation = page
            showPageTranslation(page)
            tvResult?.text = "译文 " + page.segmentCount + " 段" +
                if (page.failedCount > 0) {
                    " · " + page.failedCount + " 段翻译失败（已回退原文）"
                } else {
                    ""
                }
        }
    }

    /** 把整篇原文/译文交给悬浮窗译文区：两块整篇文本，内容与滚动位置都由用户自己掌握。 */
    private fun showPageTranslation(page: PageTranslation) {
        if (!translateActive) return
        overlayTranslateController?.bind(page)
    }

    // ---------- 悬浮球 ----------

    private fun collapseToBall() {
        if (editing) cancelEdit()
        overlayView?.visibility = View.GONE
        showBall()
    }

    private fun expandFromBall() {
        overlayView?.visibility = View.VISIBLE
        removeBall()
    }

    private fun showBall() {
        if (ballView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val size = (56 * dm.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - size - 20
            y = dm.heightPixels / 3
        }

        val pad = (14 * dm.density).toInt()
        val ball = ImageView(this).apply {
            setImageResource(R.drawable.splash_logo)
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            background = getDrawable(R.drawable.bg_ball)
            elevation = 8 * dm.density
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        wm.addView(ball, lp)
        ballView = ball
        ballLp = lp

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        ball.setOnTouchListener { _, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val l = ballLp ?: return@setOnTouchListener false
                    startX = l.x
                    startY = l.y
                    touchX = e.rawX
                    touchY = e.rawY
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val l = ballLp ?: return@setOnTouchListener false
                    l.x = (startX + (e.rawX - touchX).toInt()).coerceIn(0, dm.widthPixels - size)
                    l.y = (startY + (e.rawY - touchY).toInt()).coerceIn(0, dm.heightPixels - size)
                    ballView?.let { wm.updateViewLayout(it, l) }
                    true
                }
                else -> false
            }
        }
        ball.setOnClickListener { expandFromBall() }
    }

    private fun removeBall() {
        ballView?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        ballView = null
        ballLp = null
    }
}
