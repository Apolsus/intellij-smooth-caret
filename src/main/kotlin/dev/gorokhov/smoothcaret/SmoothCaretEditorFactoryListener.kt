package dev.gorokhov.smoothcaret

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.Color

class SmoothCaretEditorFactoryListener : EditorFactoryListener {
    private val settings = service<SmoothCaretService>().getSettings()
    private val highlighters = mutableMapOf<Editor, RangeHighlighter>()

    override fun editorCreated(event: EditorFactoryEvent) {
        setupSmoothCaret(event.editor)
    }

    private fun setupSmoothCaret(editor: Editor) {
        if (!settings.isEnabled) return

        if (shouldSkipEditor(editor)) return

        fun hideDefaultCaret() {
            editor.settings.apply {
                isBlinkCaret = false
                isBlockCursor = false
                lineCursorWidth = 0
                if (settings.replaceDefaultCaret) {
                    isCaretRowShown = false
                }
            }
            editor.colorsScheme.setColor(EditorColors.CARET_COLOR, Color(0, 0, 0, 0))
        }

        hideDefaultCaret()

        val markupModel = editor.markupModel
        val docLength = editor.document.textLength
        val highlighter = markupModel.addRangeHighlighter(
            0, docLength, HighlighterLayer.LAST + 1, null, HighlighterTargetArea.EXACT_RANGE
        )
        // 设置贪婪属性，自动扩展覆盖范围，避免频繁重建
        highlighter.isGreedyToLeft = true
        highlighter.isGreedyToRight = true
        highlighters[editor] = highlighter

        val renderer = SmoothCaretRenderer(settings)
        highlighter.customRenderer = renderer

        val caretListener = object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (settings.isEnabled && editor.contentComponent.isShowing) {
                    hideDefaultCaret()
                    editor.contentComponent.repaint()
                }
            }
        }

        editor.caretModel.addCaretListener(caretListener)
        editor.putUserData(CARET_LISTENER_KEY, caretListener)
    }

    private fun shouldSkipEditor(editor: Editor): Boolean {
        return editor.editorKind != EditorKind.MAIN_EDITOR && editor.editorKind != EditorKind.DIFF
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor

        event.editor.colorsScheme.setColor(EditorColors.CARET_COLOR, null)

        editor.getUserData(CARET_LISTENER_KEY)?.let { listener ->
            editor.caretModel.removeCaretListener(listener)
        }

        highlighters.remove(editor)?.let { highlighter ->
            editor.markupModel.removeHighlighter(highlighter)
        }

        editor.colorsScheme.setColor(EditorColors.CARET_COLOR, null)
    }
}

private val CARET_LISTENER_KEY = com.intellij.openapi.util.Key<CaretListener>("SMOOTH_CARET_LISTENER")
