package com.example.answerhelper.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.answerhelper.R
import com.example.answerhelper.answer.AnswerEngine
import com.example.answerhelper.data.Answer
import com.example.answerhelper.data.OptionBox
import com.example.answerhelper.data.Question
import com.example.answerhelper.data.QuestionKind
import com.example.answerhelper.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 万能答题助手：通过无障碍服务直接读取当前界面的文本节点，
 * 自动识别题目与选项，调用答案引擎求解，并直接点击选项节点。
 * 不截图、不 OCR、不限定某个 App。
 */
class AutoAnswerService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoAnswerService? = null
    }

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
    private var ballView: TextView? = null
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
    private var editArea: LinearLayout? = null
    private var etStem: EditText? = null
    private var etSub: EditText? = null
    private var tvJudgeOptions: TextView? = null
    private var optionsEditContainer: LinearLayout? = null
    private var optionsEditActions: LinearLayout? = null
    private var optionCountRow: LinearLayout? = null
    private var etOptionCount: EditText? = null
    private var collapsed = false

    private val optionEditors = mutableListOf<EditText>()
    private var optionEditCount = 0

    private val readRunnable = Runnable { readAndSolve() }

    private fun optionCount(): Int =
        (Prefs.get(this, "option_count", "4").toIntOrNull() ?: 4).coerceIn(2, 26)

    private fun autoReadEnabled(): Boolean = Prefs.getBool(this, "auto_read", true)

    private fun readCooldownMs(): Long =
        (Prefs.get(this, "read_cooldown", "5").toIntOrNull() ?: 5).coerceIn(2, 120) * 1000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
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
        if (blocks.isEmpty()) {
            updateOverlay("未识别到题目，请停留在题目页", null, null)
            return
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
        val keep = t.length in 2..500 ||
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

    private fun extractBlocks(root: AccessibilityNodeInfo): List<Question> {
        val items = mutableListOf<TextItem>()
        collectItems(root, items)
        if (items.isEmpty()) return emptyList()
        val raw = items.joinToString("\n") { it.text }

        if (currentKind == QuestionKind.TRANSLATE) {
            return listOf(Question(QuestionKind.TRANSLATE, stem = raw, rawText = raw))
        }

        val sameNodeRegex = Regex("^\\s*([A-H])\\s*(?:[.、．:：]\\s*|\\s+)(.*)$")
        val labelOnlyRegex = Regex("^\\s*([A-H])\\s*[.、．:：]?\\s*$")

        data class Group(val stemIdx: Int, val options: MutableList<OptionBox>)

        val groups = mutableListOf<Group>()
        var cur: Group? = null
        var pendingLabel: String? = null
        var lastWasContent = false

        for ((i, item) in items.withIndex()) {
            val sm = sameNodeRegex.matchEntire(item.text)
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

    private fun inferFromTexts(items: List<TextItem>, raw: String): List<Question> {
        val stemLike = { t: String ->
            t.contains("？") || t.contains("?") || t.contains("（）") ||
                t.contains("( )") || t.contains("（ ）") || t.endsWith("()") ||
                Regex("_{2,}").containsMatchIn(t)
        }
        val typeTag = Regex("^\\[?(单选题|多选题|判断题|填空|简答|论述)\\]?$")
        val numLike = Regex("^\\d+\\s*[.、]?$")
        val skipWords = setOf("提交", "保存", "下一节", "上一节", "由此作答", "待完成")

        val result = mutableListOf<Question>()
        var i = 0
        while (i < items.size) {
            val stem = items[i].text.trim()
            if (!stemLike(stem) || stem.length < 6) {
                i++
                continue
            }
            val prevTag = if (i >= 1) typeTag.matchEntire(items[i - 1].text.trim())?.groupValues?.get(1) else null
            val prev2Tag = if (i >= 2) typeTag.matchEntire(items[i - 2].text.trim())?.groupValues?.get(1) else null
            val judge = prevTag == "判断题" || prev2Tag == "判断题"

            var j = i + 1
            // 跳过紧跟着的重复题干
            while (j < items.size && items[j].text.trim() == stem) j++

            val opts = mutableListOf<OptionBox>()
            var label = 'A'
            while (j < items.size && opts.size < 26) {
                val s = items[j].text.trim()
                when {
                    s == stem || stemLike(s) -> break
                    typeTag.matches(s) -> j++
                    numLike.matches(s) -> j++
                    s in skipWords -> break
                    s.length in 2..40 -> {
                        opts.add(
                            OptionBox(
                                label.toString(), s,
                                items[j].bounds.centerX(), items[j].bounds.centerY(),
                                Rect(items[j].bounds), items[j].node
                            )
                        )
                        label++
                        j++
                    }
                    else -> j++
                }
            }

            if (opts.size >= 2) {
                val kind = if (judge) QuestionKind.JUDGE else QuestionKind.CHOICE
                val finalOpts = if (judge) {
                    listOf(
                        OptionBox("A", "是", opts[0].centerX, opts[0].centerY, Rect(), opts[0].node),
                        OptionBox("B", "否", opts[1].centerX, opts[1].centerY, Rect(), opts[1].node)
                    )
                } else {
                    opts
                }
                result.add(Question(kind, stem = stem, options = finalOpts, rawText = raw))
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

    private fun solveAndShow(question: Question) {
        val s = scope ?: return
        s.launch {
            if (editing) return@launch
            val answer = try {
                AnswerEngine.solve(this@AutoAnswerService, question)
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

    private fun answerHeadline(q: Question?, a: Answer): String = when (q?.kind) {
        QuestionKind.ESSAY -> "答案：${a.text.ifBlank { a.label }}（${a.source}）"
        QuestionKind.TRANSLATE -> "译文（${a.source}）"
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
        return a.label.toCharArray().map { it.toString() }.toSet()
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
        if (editing) {
            currentKind = kind
            renderTypeButtons(kind)
            applyEditUiForKind(kind, lastQuestion?.options ?: emptyList())
        } else {
            switchKindAndSolve(kind)
        }
    }

    private fun switchKindAndSolve(kind: QuestionKind) {
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
                q.copy(kind = QuestionKind.JUDGE, subQuestion = "", options = opts)
            }
            QuestionKind.ESSAY -> q.copy(kind = QuestionKind.ESSAY, options = emptyList())
            QuestionKind.TRANSLATE -> q.copy(kind = QuestionKind.TRANSLATE, subQuestion = "", options = emptyList())
        }
        currentKind = kind
        lastQuestion = newQ
        lastTitle = newQ.query
        lastSeenAt = System.currentTimeMillis()
        updateOverlay("已切换为${kind.label}，正在搜索答案…", newQ, null)
        solveAndShow(newQ)
    }

    private fun renderTypeButtons(kind: QuestionKind) {
        btnTypeChoice?.text = if (kind == QuestionKind.CHOICE) "●选择题" else "选择题"
        btnTypeJudge?.text = if (kind == QuestionKind.JUDGE) "●判断题" else "判断题"
        btnTypeEssay?.text = if (kind == QuestionKind.ESSAY) "●大题" else "大题"
        btnTypeTranslate?.text = if (kind == QuestionKind.TRANSLATE) "●翻译" else "翻译"
    }

    // ---------- 编辑 ----------

    private fun startEdit() {
        val q = lastQuestion
        if (q == null) {
            updateOverlay("暂无可编辑的题目，请先识别", null, null)
            return
        }
        editing = true
        currentKind = q.kind
        renderTypeButtons(q.kind)
        etStem?.setText(q.stem)
        etSub?.setText(q.subQuestion)
        applyEditUiForKind(q.kind, q.options)
        setEditUi(true)
        setCollapsed(true)
        updateOverlay("编辑题目中，确认后开始解题", null, null)
    }

    private fun applyEditUiForKind(kind: QuestionKind, options: List<OptionBox>) {
        etStem?.hint = when (kind) {
            QuestionKind.TRANSLATE -> "要翻译的文本"
            QuestionKind.ESSAY -> "主题干区"
            else -> "题干区"
        }
        etSub?.visibility = if (kind == QuestionKind.ESSAY) View.VISIBLE else View.GONE
        tvJudgeOptions?.visibility = if (kind == QuestionKind.JUDGE) View.VISIBLE else View.GONE
        val choice = kind == QuestionKind.CHOICE
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
        }
        row.addView(label)
        row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(row)
        optionEditors.add(et)
    }

    private fun currentEditorTexts(): List<String> = optionEditors.map { it.text.toString().trim() }

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
                QuestionKind.ESSAY, QuestionKind.TRANSLATE -> emptyList()
            }
            val newQ = Question(currentKind, stem, sub, options, lastQuestion?.rawText ?: "")
            setEditUi(false)
            editing = false
            lastQuestion = newQ
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
        editArea = view.findViewById(R.id.edit_area)
        etStem = view.findViewById(R.id.et_stem)
        etSub = view.findViewById(R.id.et_sub)
        tvJudgeOptions = view.findViewById(R.id.tv_judge_options)
        optionsEditContainer = view.findViewById(R.id.options_edit_container)
        optionsEditActions = view.findViewById(R.id.options_edit_actions)
        optionCountRow = view.findViewById(R.id.option_count_row)
        etOptionCount = view.findViewById(R.id.et_option_count)
        view.findViewById<MaxHeightScrollView>(R.id.content_scroll)?.maxHeight =
            resources.displayMetrics.heightPixels / 3

        // “框题”按钮在本版本不需要，隐藏
        view.findViewById<Button>(R.id.btn_region)?.visibility = View.GONE

        etOptionCount?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyOptionCount()
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

        view.findViewById<Button>(R.id.btn_capture).setOnClickListener { readAndSolve(force = true) }
        view.findViewById<Button>(R.id.btn_prev).setOnClickListener { prevQuestion() }
        view.findViewById<Button>(R.id.btn_next).setOnClickListener { nextQuestion() }
        view.findViewById<Button>(R.id.btn_edit).setOnClickListener { startEdit() }
        view.findViewById<Button>(R.id.btn_edit_confirm).setOnClickListener { confirmEdit() }
        view.findViewById<Button>(R.id.btn_edit_cancel).setOnClickListener { cancelEdit() }
        view.findViewById<Button>(R.id.btn_add_option).setOnClickListener { addOptionRow() }
        view.findViewById<Button>(R.id.btn_remove_option).setOnClickListener { removeOptionRow() }
        btnTypeChoice?.setOnClickListener { onTypeButton(QuestionKind.CHOICE) }
        btnTypeJudge?.setOnClickListener { onTypeButton(QuestionKind.JUDGE) }
        btnTypeEssay?.setOnClickListener { onTypeButton(QuestionKind.ESSAY) }
        btnTypeTranslate?.setOnClickListener { onTypeButton(QuestionKind.TRANSLATE) }
        btnCollapse?.setOnClickListener { collapseToBall() }
        view.findViewById<Button>(R.id.btn_close).setOnClickListener { stopHelper() }

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
    }

    private fun updateOverlay(status: String, question: Question?, answer: Answer?) {
        tvResult?.text = status
        tvQuestion?.text = question?.let {
            when (it.kind) {
                QuestionKind.ESSAY ->
                    if (it.subQuestion.isBlank()) it.stem else it.stem + "\n问题：" + it.subQuestion
                QuestionKind.TRANSLATE -> "原文：\n" + it.stem
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
                    for (opt in question.options) {
                        container.addView(makeOptionButton("${opt.label}. ${opt.text}", opt.label, opt.label in correct))
                    }
                }
                QuestionKind.JUDGE -> {
                    container.addView(makeOptionButton("A. 是", "A", "A" in correct))
                    container.addView(makeOptionButton("B. 否", "B", "B" in correct))
                }
                QuestionKind.ESSAY, QuestionKind.TRANSLATE -> Unit
            }
        }
        tvReason?.text = answer?.let {
            if (question?.kind == QuestionKind.TRANSLATE) {
                "译文：\n" + it.text
            } else {
                "来源：${it.source}  置信度：${(it.confidence * 100).toInt()}%  理由：${it.reason}"
            }
        } ?: ""
        setCollapsed(question == null && answer == null && collapsed)
    }

    private fun makeOptionButton(text: String, label: String, highlighted: Boolean = false): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            minHeight = 0
            setPadding(8, 6, 8, 6)
            if (highlighted) {
                setBackgroundColor(0xFF2E7D32.toInt())
                setTextColor(0xFFFFFFFF.toInt())
            }
            setOnClickListener { choose(label) }
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
        val size = (48 * dm.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - size - 24
            y = dm.heightPixels / 3
        }
        val ball = TextView(this).apply {
            text = "答"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6FB7299.toInt())
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
                    val s = (48 * resources.displayMetrics.density).toInt()
                    l.x = (startX + (e.rawX - touchX).toInt()).coerceIn(0, resources.displayMetrics.widthPixels - s)
                    l.y = (startY + (e.rawY - touchY).toInt()).coerceIn(0, resources.displayMetrics.heightPixels - s)
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
