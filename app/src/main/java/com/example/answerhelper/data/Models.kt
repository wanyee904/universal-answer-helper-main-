package com.example.answerhelper.data

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

enum class QuestionKind(val label: String) {
    CHOICE("选择题"),
    JUDGE("判断题"),
    ESSAY("大题"),
    TRANSLATE("翻译")
}

/** 一个选项及其对应的屏幕节点（用于直接点击） */
data class OptionBox(
    val label: String,
    val text: String,
    val centerX: Int,
    val centerY: Int,
    val box: Rect,
    val node: AccessibilityNodeInfo? = null
)

/** 题目 */
data class Question(
    val kind: QuestionKind,
    val stem: String,
    val subQuestion: String = "",
    val options: List<OptionBox> = emptyList(),
    val rawText: String = ""
) {
    val query: String
        get() = when (kind) {
            QuestionKind.ESSAY -> if (subQuestion.isBlank()) stem else "$stem\n$subQuestion"
            else -> stem
        }
}

/** 答案 */
data class Answer(
    val label: String,
    val text: String = "",
    val options: List<String> = emptyList(),
    val reason: String,
    val confidence: Float,
    val source: String
)
