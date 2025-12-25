package dev.gorokhov.smoothcaret

import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.CaretVisualAttributes
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Point2D
import javax.swing.Timer
import kotlin.math.*

class SmoothCaretRenderer(private val settings: SmoothCaretSettings) : CustomHighlighterRenderer {
    private val caretPositions = mutableMapOf<Caret, CaretPosition>()
    private var timer: Timer? = null
    private var lastEditor: Editor? = null

    private var blinkStartTime = System.currentTimeMillis()
    private var lastMoveTime = System.currentTimeMillis()
    private val resumeBlinkDelay = 100
    private var blinkTimer: Timer? = null
    private var cachedCharWidth: Int = 0
    private var cachedEditor: Editor? = null
    private val staticBlinkValue = BlinkValue(1.0f, 1.0f)

    private var cachedNextChar: Char? = null
    private var cachedNextCharWidth: Int = 0

    private data class CaretPosition(
        var currentX: Double = 0.0, var currentY: Double = 0.0, var targetX: Double = 0.0, var targetY: Double = 0.0
    )

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        if (!settings.isEnabled) return

        if (!editor.contentComponent.hasFocus()) return

        if (lastEditor != editor) {
            lastEditor = editor
            resetAllPositions(editor)
            blinkStartTime = System.currentTimeMillis()

            blinkTimer?.stop()
            blinkTimer = null
        }

        val g2d = g as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val allCarets = editor.caretModel.allCarets

        ensureTimerStarted(editor)
        ensureBlinkTimerStarted(editor)

        val currentTime = System.currentTimeMillis()
        var anyMoving = false

        allCarets.forEach { caret ->
            val point = editor.visualPositionToPoint2D(caret.visualPosition)
            val caretPos = caretPositions.getOrPut(caret) {
                CaretPosition(
                    currentX = point.x,
                    currentY = point.y,
                    targetX = point.x,
                    targetY = point.y
                )
            }

            if (abs(point.x - caretPos.targetX) > 1000 || abs(point.y - caretPos.targetY) > 1000) {
                resetCaretPosition(caretPos, point)
            }

            caretPos.targetX = point.x
            caretPos.targetY = point.y

            val isMoving =
                abs(caretPos.targetX - caretPos.currentX) > 0.01 || abs(caretPos.targetY - caretPos.currentY) > 0.01
            if (isMoving) {
                anyMoving = true
            }
        }

        if (anyMoving) {
            lastMoveTime = currentTime
            blinkStartTime = currentTime
        }

        val timeSinceLastMove = currentTime - lastMoveTime
        val shouldBlink = timeSinceLastMove > resumeBlinkDelay

        val blinkValue = if (settings.blinkingStyle != SmoothCaretSettings.BlinkingStyle.SOLID) {
            if (shouldBlink) {
                val elapsedSinceBlinkStart = currentTime - blinkStartTime - resumeBlinkDelay
                val timeInCycle = if (elapsedSinceBlinkStart >= 0) {
                    (elapsedSinceBlinkStart % settings.blinkInterval.toLong()).toFloat() / settings.blinkInterval.toFloat()
                } else {
                    0f
                }
                calculateBlinkValue(timeInCycle)
            } else {
                staticBlinkValue
            }
        } else {
            staticBlinkValue
        }

        if (blinkValue.opacity <= 0.01f) return

        g2d.color = editor.colorsScheme.defaultForeground
        val caretHeight = editor.lineHeight - (settings.caretHeightMargins * 2)

        val originalComposite = if (blinkValue.opacity < 1.0f) {
            val current = g2d.composite
            g2d.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, blinkValue.opacity)
            current
        } else null

        allCarets.forEach { caret ->
            val caretPos = caretPositions[caret] ?: return@forEach

            if (caretPos.currentX.isFinite() && caretPos.currentY.isFinite()) {
                val caretX = round(caretPos.currentX).toInt()
                val caretY = round(caretPos.currentY).toInt()
                val scaledHeight = (caretHeight * blinkValue.scaleY).toInt()
                val yOffset = if (blinkValue.scaleY < 1.0f) {
                    settings.caretHeightMargins + (caretHeight - scaledHeight) / 2
                } else {
                    settings.caretHeightMargins
                }

                when (caret.visualAttributes.shape) {
                    CaretVisualAttributes.Shape.BAR,
                    CaretVisualAttributes.Shape.DEFAULT -> {
                        g2d.fillRect(caretX, caretY + yOffset, settings.caretWidth, scaledHeight)
                    }

                    CaretVisualAttributes.Shape.BOX,
                    CaretVisualAttributes.Shape.BLOCK -> {
                        val nextCharWidth = updateNextCharWidth(caret)
                        g2d.fillRect(caretX, caretY + yOffset, nextCharWidth, scaledHeight)
                    }

                    CaretVisualAttributes.Shape.UNDERSCORE -> {
                        val underscoreY = if (blinkValue.scaleY < 1.0f) {
                            caretY + caretHeight - 2 + (2 - (2 * blinkValue.scaleY).toInt()) / 2
                        } else {
                            caretY + caretHeight - 2
                        }
                        val nextCharWidth = updateNextCharWidth(caret)
                        g2d.fillRect(
                            caretX,
                            underscoreY,
                            nextCharWidth,
                            (2 * blinkValue.scaleY).toInt().coerceAtLeast(1)
                        )
                    }
                }
            }
        }

        originalComposite?.let { g2d.composite = it }

        // Clean up positions for carets that no longer exist
        caretPositions.keys.retainAll { caret -> allCarets.contains(caret) }
    }

    /** Update the cached value of [cachedNextCharWidth] if [cachedNextChar] has changed since the last computation */
    private fun updateNextCharWidth(caret: Caret): Int {
        val editor = caret.editor
        val nextChar = getNextCharFromCursorOrDefault(editor, caret, 'm')
        if (nextChar != cachedNextChar) {
            val nextCharWidth = editor
                .component
                .getFontMetrics(editor.colorsScheme.getFont(null))
                .charWidth(nextChar)
            cachedNextCharWidth = nextCharWidth
            cachedNextChar = nextChar
        }
        return cachedNextCharWidth
    }

    /** Get the next char after the current cursor or fall back to [default] in case there is no next char */
    private fun getNextCharFromCursorOrDefault(
        editor: Editor,
        caret: Caret,
        default: Char
    ): Char {
        val logicalPositionToOffset = editor.logicalPositionToOffset(caret.logicalPosition)
        val endOffset = min(logicalPositionToOffset + 1, editor.document.textLength)
        val startOffset = max(endOffset - 1, 0)

        val chars = editor.document.charsSequence
        return if (startOffset < endOffset && startOffset >= 0 && endOffset <= chars.length) {
            chars[startOffset]
        } else {
            default
        }
    }

    private fun calculateBlinkValue(timeInCycle: Float): BlinkValue {
        return when (settings.blinkingStyle) {
            SmoothCaretSettings.BlinkingStyle.BLINK -> {
                if (timeInCycle < 0.5f) BlinkValue(1.0f, 1.0f) else BlinkValue(0.0f, 1.0f)
            }

            SmoothCaretSettings.BlinkingStyle.SMOOTH -> {
                when {
                    timeInCycle < 0.3f -> BlinkValue(1.0f, 1.0f)
                    timeInCycle < 0.7f -> {
                        val fadeProgress = (timeInCycle - 0.3f) * 2.5f
                        val opacity = 1.0f - fadeProgress
                        BlinkValue(opacity, 1.0f)
                    }

                    else -> {
                        val fadeProgress = (timeInCycle - 0.7f) * 3.333f
                        val opacity = fadeProgress
                        BlinkValue(opacity, 1.0f)
                    }
                }
            }

            SmoothCaretSettings.BlinkingStyle.PHASE -> {
                when {
                    timeInCycle < 0.15f -> BlinkValue(1.0f, 1.0f)
                    timeInCycle < 0.85f -> {
                        val progress = (timeInCycle - 0.15f) * 1.4286f
                        val sinValue = sin(progress * Math.PI).toFloat()
                        val opacity = 1.0f - sinValue * 0.8f
                        BlinkValue(opacity, 1.0f)
                    }

                    else -> BlinkValue(1.0f, 1.0f)
                }
            }

            SmoothCaretSettings.BlinkingStyle.EXPAND -> {
                when {
                    timeInCycle < 0.2f -> BlinkValue(1.0f, 1.0f)
                    timeInCycle < 0.8f -> {
                        val progress = (timeInCycle - 0.2f) * 1.6667f
                        val sinValue = sin(progress * Math.PI).toFloat()
                        val scale = 1.0f - sinValue * 0.5f
                        BlinkValue(1.0f, scale)
                    }

                    else -> BlinkValue(1.0f, 1.0f)
                }
            }

            SmoothCaretSettings.BlinkingStyle.SOLID -> {
                BlinkValue(1.0f, 1.0f)
            }
        }
    }

    private fun ensureBlinkTimerStarted(editor: Editor) {
        if (blinkTimer == null && settings.blinkingStyle != SmoothCaretSettings.BlinkingStyle.SOLID) {
            val refreshRate = getScreenRefreshRate(editor)
            val delay = 1000 / refreshRate

            blinkTimer = Timer(delay) {
                if (!editor.isDisposed) {
                    val timeSinceLastMove = System.currentTimeMillis() - lastMoveTime
                    if (timeSinceLastMove > resumeBlinkDelay) {
                        val pad = 2
                        val charW = if (cachedCharWidth > 0) cachedCharWidth else 8
                        val caretWidth = max(settings.caretWidth, charW)
                        val caretHeight = editor.lineHeight - (settings.caretHeightMargins * 2)

                        val bounds = accumulateCurrentCaretBounds(caretWidth, caretHeight, pad)
                        clampAndRepaint(editor, bounds, fallbackFull = false)
                    }
                } else {
                    blinkTimer?.stop()
                    blinkTimer = null
                }
            }
            blinkTimer?.start()
        }
    }

    private data class BlinkValue(val opacity: Float, val scaleY: Float)

    private fun resetAllPositions(editor: Editor) {
        caretPositions.clear()
        val allCarets = editor.caretModel.allCarets
        allCarets.forEach { caret ->
            val point = editor.visualPositionToPoint2D(caret.visualPosition)
            val caretPos = CaretPosition(
                currentX = point.x,
                currentY = point.y,
                targetX = point.x,
                targetY = point.y
            )
            caretPositions[caret] = caretPos
        }
        blinkStartTime = System.currentTimeMillis()
    }

    private fun resetCaretPosition(caretPos: CaretPosition, point: Point2D) {
        caretPos.currentX = point.x
        caretPos.currentY = point.y
        caretPos.targetX = caretPos.currentX
        caretPos.targetY = caretPos.currentY
    }

    private fun getScreenRefreshRate(editor: Editor): Int {
        val device = editor.contentComponent.graphicsConfiguration.device
        val mode = device.displayMode
        var refreshRate = mode.refreshRate

        if (refreshRate <= 0) {
            refreshRate = 60
        }

        return refreshRate.coerceIn(30, 240)
    }

    private fun ensureTimerStarted(editor: Editor) {
        if (timer == null) {
            val refreshRate = getScreenRefreshRate(editor)
            val delay = 1000 / refreshRate

            timer = Timer(delay) {
                if (!editor.isDisposed) {
                    var needsRepaint = false

                    ensureCharWidthCached(editor)

                    val pad = 2
                    val charW = if (cachedCharWidth > 0) cachedCharWidth else 8
                    val caretWidth = max(settings.caretWidth, charW)
                    val caretHeight = editor.lineHeight - (settings.caretHeightMargins * 2)

                    val bounds = Bounds()

                    caretPositions.values.forEach { caretPos ->
                        val dx = caretPos.targetX - caretPos.currentX
                        val dy = caretPos.targetY - caretPos.currentY

                        if (abs(dx) > 0 || abs(dy) > 0.01) {
                            val speedFactor = if (settings.adaptiveSpeed) {
                                when {
                                    abs(dx) > charW * 2 -> settings.maxCatchupSpeed
                                    abs(dx) > charW -> settings.catchupSpeed
                                    else -> settings.smoothness
                                }
                            } else {
                                settings.smoothness
                            }

                            val oldX = caretPos.currentX
                            val oldY = caretPos.currentY
                            val newX = oldX + dx * speedFactor
                            val newY = oldY + dy * speedFactor

                            // 更新位置
                            caretPos.currentX = newX
                            caretPos.currentY = newY
                            needsRepaint = true

                            // 计算脏区（包含旧位置与新位置）
                            val minRectX = round(min(oldX, newX)).toInt() - pad
                            val minRectY = round(min(oldY, newY)).toInt() + settings.caretHeightMargins - pad
                            val maxRectX = round(max(oldX, newX)).toInt() + caretWidth + pad
                            val maxRectY =
                                round(max(oldY, newY)).toInt() + settings.caretHeightMargins + caretHeight + pad

                            bounds.add(minRectX, minRectY, maxRectX, maxRectY)
                        }
                    }

                    if (needsRepaint) {
                        clampAndRepaint(editor, bounds, fallbackFull = true)
                    }
                } else {
                    timer?.stop()
                    timer = null
                }
            }
            timer?.start()
        }
    }

    private fun ensureCharWidthCached(editor: Editor) {
        if (cachedEditor != editor || cachedCharWidth == 0) {
            cachedCharWidth = editor.component
                .getFontMetrics(editor.colorsScheme.getFont(null))
                .charWidth('m')
            cachedEditor = editor
        }
    }

    private data class Bounds(
        var minX: Int = Int.MAX_VALUE,
        var minY: Int = Int.MAX_VALUE,
        var maxX: Int = Int.MIN_VALUE,
        var maxY: Int = Int.MIN_VALUE
    ) {
        fun add(xMin: Int, yMin: Int, xMax: Int, yMax: Int) {
            minX = min(minX, xMin)
            minY = min(minY, yMin)
            maxX = max(maxX, xMax)
            maxY = max(maxY, yMax)
        }

        fun isValid(): Boolean = minX < maxX && minY < maxY
    }

    private fun accumulateCurrentCaretBounds(caretWidth: Int, caretHeight: Int, pad: Int): Bounds {
        val b = Bounds()
        caretPositions.values.forEach { pos ->
            if (pos.currentX.isFinite() && pos.currentY.isFinite()) {
                val x = round(pos.currentX).toInt()
                val y = round(pos.currentY).toInt() + settings.caretHeightMargins
                b.add(x - pad, y - pad, x + caretWidth + pad, y + caretHeight + pad)
            }
        }
        return b
    }

    private fun clampAndRepaint(editor: Editor, bounds: Bounds, fallbackFull: Boolean) {
        if (!bounds.isValid()) {
            if (fallbackFull) editor.contentComponent.repaint()
            return
        }
        val comp = editor.contentComponent
        val bx = 0
        val by = 0
        val bw = comp.width
        val bh = comp.height
        val rx = max(bounds.minX, bx)
        val ry = max(bounds.minY, by)
        val rw = min(bounds.maxX, bx + bw) - rx
        val rh = min(bounds.maxY, by + bh) - ry
        if (rw > 0 && rh > 0) {
            comp.repaint(rx, ry, rw, rh)
        } else if (fallbackFull) {
            comp.repaint()
        }
    }
}
