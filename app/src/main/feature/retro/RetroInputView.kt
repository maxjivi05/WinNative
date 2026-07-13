package com.winlator.cmod.feature.retro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.winlator.cmod.runtime.input.controls.GameHubLayout
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class RetroInputView(
    context: Context,
    private val listener: Listener,
    private val system: RetroSystem? = null,
) : View(context) {
    interface Listener {
        fun onButton(
            keyCode: Int,
            down: Boolean,
        )

        fun onDpad(
            x: Float,
            y: Float,
        )

        fun onStick(
            x: Float,
            y: Float,
        )

        fun onMenu()
    }

    private enum class GlassShape { CIRCLE, PILL, TRIGGER_LT, TRIGGER_LB, TRIGGER_RT, TRIGGER_RB }

    private class GlassButton(
        val keyCode: Int,
        val label: String,
        val shape: GlassShape,
        val bounds: RectF = RectF(),
    )

    private data class OverlayConfig(
        val hasXY: Boolean,
        val hasShoulders: Boolean,
        val hasTriggers: Boolean,
        val hasStick: Boolean,
        val leftTriggerLabel: String = "L2",
        val rightTriggerLabel: String = "R2",
        val showRightTrigger: Boolean = true,
    )

    private val config =
        when (system?.id) {
            RetroSystems.SNES.id -> OverlayConfig(hasXY = true, hasShoulders = true, hasTriggers = false, hasStick = false)
            RetroSystems.GBA.id -> OverlayConfig(hasXY = false, hasShoulders = true, hasTriggers = false, hasStick = false)
            RetroSystems.GENESIS.id -> OverlayConfig(hasXY = true, hasShoulders = true, hasTriggers = false, hasStick = false)
            RetroSystems.N64.id ->
                OverlayConfig(
                    hasXY = false,
                    hasShoulders = true,
                    hasTriggers = true,
                    hasStick = true,
                    leftTriggerLabel = "Z",
                    showRightTrigger = false,
                )
            RetroSystems.PSX.id -> OverlayConfig(hasXY = true, hasShoulders = true, hasTriggers = true, hasStick = false)
            else -> OverlayConfig(hasXY = false, hasShoulders = false, hasTriggers = false, hasStick = false)
        }

    private val buttons = mutableListOf<GlassButton>()
    private val menuButton = GlassButton(0, "MENU", GlassShape.PILL)

    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadRadius = 0f

    private var stickCx = 0f
    private var stickCy = 0f
    private var stickRadius = 0f
    private var stickPointerId = -1
    private var stickX = 0f
    private var stickY = 0f

    private val pressedButtons = HashSet<Int>()
    private var dpadX = 0f
    private var dpadY = 0f
    private var menuLatched = false

    private var strokeWidth = 4f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val arrowCenter = FloatArray(2)

    private val fillColor = Color.argb(90, 0, 0, 0)
    private val strokeColor = Color.argb(150, 255, 255, 255)
    private val pressedFillColor = Color.argb(60, 255, 255, 255)
    private val pressedStrokeColor = Color.argb(220, 255, 255, 255)
    private val textColor = Color.argb(255, 255, 255, 255)
    private val glassEdgeAlpha = 75

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        buttons.clear()
        val width = w.toFloat()
        val height = h.toFloat()
        val unit = min(width, height)
        val margin = unit * 0.05f
        val faceRadius = unit * 0.085f
        strokeWidth = max(2f, faceRadius * 0.16f)

        val trigW = unit * 0.30f
        val trigH = unit * 0.105f
        val trigGap = unit * 0.02f

        var leftCursor = margin
        var rightCursor = margin
        if (config.hasTriggers) {
            val lt = GlassButton(KeyEvent.KEYCODE_BUTTON_L2, config.leftTriggerLabel, GlassShape.TRIGGER_LT)
            lt.bounds.set(margin, leftCursor, margin + trigW, leftCursor + trigH)
            buttons += lt
            leftCursor += trigH + trigGap
            if (config.showRightTrigger) {
                val rt = GlassButton(KeyEvent.KEYCODE_BUTTON_R2, config.rightTriggerLabel, GlassShape.TRIGGER_RT)
                rt.bounds.set(width - margin - trigW, rightCursor, width - margin, rightCursor + trigH)
                buttons += rt
                rightCursor += trigH + trigGap
            }
        }
        if (config.hasShoulders) {
            val lb = GlassButton(KeyEvent.KEYCODE_BUTTON_L1, "L", GlassShape.TRIGGER_LB)
            lb.bounds.set(margin, leftCursor, margin + trigW, leftCursor + trigH)
            buttons += lb
            val rb = GlassButton(KeyEvent.KEYCODE_BUTTON_R1, "R", GlassShape.TRIGGER_RB)
            rb.bounds.set(width - margin - trigW, rightCursor, width - margin, rightCursor + trigH)
            buttons += rb
            leftCursor += trigH + trigGap
        }

        val clusterCx = width - margin - faceRadius * 2.6f
        val clusterCy = height - margin - faceRadius * 2.6f
        val spread = faceRadius * 1.75f
        var clusterTop = height
        fun addFace(
            keyCode: Int,
            label: String,
            cx: Float,
            cy: Float,
        ) {
            val button = GlassButton(keyCode, label, GlassShape.CIRCLE)
            button.bounds.set(cx - faceRadius, cy - faceRadius, cx + faceRadius, cy + faceRadius)
            buttons += button
            clusterTop = min(clusterTop, button.bounds.top)
        }
        if (config.hasXY) {
            addFace(KeyEvent.KEYCODE_BUTTON_X, "X", clusterCx, clusterCy - spread)
            addFace(KeyEvent.KEYCODE_BUTTON_B, "B", clusterCx, clusterCy + spread)
            addFace(KeyEvent.KEYCODE_BUTTON_Y, "Y", clusterCx - spread, clusterCy)
            addFace(KeyEvent.KEYCODE_BUTTON_A, "A", clusterCx + spread, clusterCy)
        } else {
            addFace(KeyEvent.KEYCODE_BUTTON_B, "B", clusterCx - faceRadius * 1.1f, clusterCy + faceRadius * 0.7f)
            addFace(KeyEvent.KEYCODE_BUTTON_A, "A", clusterCx + faceRadius * 1.1f, clusterCy - faceRadius * 0.7f)
        }

        val pillW = unit * 0.135f
        val pillH = unit * 0.062f
        val pillGap = unit * 0.02f
        val pillY = clusterTop - pillH - unit * 0.065f
        val start = GlassButton(KeyEvent.KEYCODE_BUTTON_START, "START", GlassShape.PILL)
        start.bounds.set(width - margin - pillW, pillY, width - margin, pillY + pillH)
        buttons += start
        val select = GlassButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", GlassShape.PILL)
        select.bounds.set(
            width - margin - pillW * 2 - pillGap,
            pillY,
            width - margin - pillW - pillGap,
            pillY + pillH,
        )
        buttons += select

        val menuW = unit * 0.15f
        if (config.hasStick) {
            dpadRadius = unit * 0.135f
            dpadCx = margin + dpadRadius + unit * 0.02f
            menuButton.bounds.set(dpadCx - menuW * 0.5f, leftCursor, dpadCx + menuW * 0.5f, leftCursor + pillH)
            leftCursor += pillH + trigGap
            dpadCy = leftCursor + dpadRadius
            leftCursor += dpadRadius * 2 + trigGap
            stickRadius = unit * 0.115f
            stickCx = dpadCx
            stickCy = max(leftCursor + stickRadius, height - margin - stickRadius)
        } else {
            stickRadius = 0f
            dpadRadius = unit * 0.155f
            dpadCx = margin + dpadRadius * 1.15f
            dpadCy = height - margin - dpadRadius * 1.15f
            val menuY = max(pillY, leftCursor)
            menuButton.bounds.set(dpadCx - menuW * 0.5f, menuY, dpadCx + menuW * 0.5f, menuY + pillH)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.strokeWidth = strokeWidth
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        drawDpad(canvas)
        if (config.hasStick) drawStick(canvas)
        buttons.forEach { drawGlassButton(canvas, it, pressedButtons.contains(it.keyCode)) }
        drawGlassButton(canvas, menuButton, menuLatched)
    }

    private fun buildShapePath(button: GlassButton) {
        val b = button.bounds
        when (button.shape) {
            GlassShape.CIRCLE -> {
                path.reset()
                path.addCircle(b.centerX(), b.centerY(), b.width() * 0.5f, Path.Direction.CW)
            }
            GlassShape.PILL -> {
                path.reset()
                val r = b.height() * 0.5f
                path.addRoundRect(b.left, b.top, b.right, b.bottom, r, r, Path.Direction.CW)
            }
            GlassShape.TRIGGER_LT ->
                GameHubLayout.buildTriggerPath(path, GameHubLayout.RenderShape.TRIGGER_LT, b.left, b.top, b.right, b.bottom)
            GlassShape.TRIGGER_LB ->
                GameHubLayout.buildTriggerPath(path, GameHubLayout.RenderShape.TRIGGER_LB, b.left, b.top, b.right, b.bottom)
            GlassShape.TRIGGER_RT ->
                GameHubLayout.buildTriggerPath(path, GameHubLayout.RenderShape.TRIGGER_RT, b.left, b.top, b.right, b.bottom)
            GlassShape.TRIGGER_RB ->
                GameHubLayout.buildTriggerPath(path, GameHubLayout.RenderShape.TRIGGER_RB, b.left, b.top, b.right, b.bottom)
        }
    }

    private fun drawGlassButton(
        canvas: Canvas,
        button: GlassButton,
        pressed: Boolean,
    ) {
        val b = button.bounds
        buildShapePath(button)

        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawPath(path, paint)
        if (pressed) {
            paint.color = pressedFillColor
            canvas.drawPath(path, paint)
        }

        paint.shader =
            RadialGradient(
                b.centerX(),
                b.centerY(),
                max(b.width(), b.height()) * 0.5f,
                Color.argb(0, 0, 0, 0),
                Color.argb(glassEdgeAlpha, 0, 0, 0),
                Shader.TileMode.CLAMP,
            )
        canvas.drawPath(path, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.color = if (pressed) pressedStrokeColor else strokeColor
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
        paint.color = textColor
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        val maxTextWidth = b.width() - strokeWidth * 3
        paint.textSize = b.height() * if (button.label.length > 2) 0.42f else 0.62f
        if (button.label.isNotEmpty() && paint.measureText(button.label) > maxTextWidth) {
            paint.textSize = paint.textSize * maxTextWidth / paint.measureText(button.label)
        }
        val textY = b.centerY() - (paint.descent() + paint.ascent()) * 0.5f
        canvas.drawText(button.label, b.centerX(), textY, paint)
        paint.isFakeBoldText = false
    }

    private fun drawDpad(canvas: Canvas) {
        val sidePressed =
            booleanArrayOf(dpadY < -0.1f, dpadY > 0.1f, dpadX < -0.1f, dpadX > 0.1f)
        for (side in 0 until 4) {
            path.reset()
            GameHubLayout.buildDpadArrow(path, side, dpadCx, dpadCy, dpadRadius)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = fillColor
            canvas.drawPath(path, paint)
            if (sidePressed[side]) {
                paint.color = pressedFillColor
                canvas.drawPath(path, paint)
            }
            GameHubLayout.dpadArrowCenter(side, dpadCx, dpadCy, dpadRadius, arrowCenter)
            paint.shader =
                RadialGradient(
                    arrowCenter[0],
                    arrowCenter[1],
                    dpadRadius * 0.5f,
                    Color.argb(0, 0, 0, 0),
                    Color.argb(glassEdgeAlpha, 0, 0, 0),
                    Shader.TileMode.CLAMP,
                )
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
            paint.shader = null
        }
        val engaged = dpadX != 0f || dpadY != 0f
        GameHubLayout.buildDpadArrows(path, dpadCx, dpadCy, dpadRadius)
        paint.style = Paint.Style.STROKE
        paint.color = if (engaged) pressedStrokeColor else strokeColor
        canvas.drawPath(path, paint)
    }

    private fun drawStick(canvas: Canvas) {
        val engaged = stickPointerId != -1
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawCircle(stickCx, stickCy, stickRadius, paint)

        paint.shader =
            RadialGradient(
                stickCx,
                stickCy,
                stickRadius,
                Color.argb(0, 0, 0, 0),
                Color.argb(glassEdgeAlpha, 0, 0, 0),
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(stickCx, stickCy, stickRadius, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.color = if (engaged) pressedStrokeColor else strokeColor
        canvas.drawCircle(stickCx, stickCy, stickRadius - strokeWidth * 0.5f, paint)

        val thumbX = stickCx + stickX * stickRadius * 0.52f
        val thumbY = stickCy + stickY * stickRadius * 0.52f
        val thumbRadius = stickRadius * 0.48f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(if (engaged) 100 else 77, 255, 255, 255)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.color = if (engaged) pressedStrokeColor else strokeColor
        canvas.drawCircle(thumbX, thumbY, thumbRadius - strokeWidth * 0.5f, paint)
    }

    fun releaseAll() {
        for (keyCode in pressedButtons) listener.onButton(keyCode, false)
        pressedButtons.clear()
        if (dpadX != 0f || dpadY != 0f) {
            dpadX = 0f
            dpadY = 0f
            listener.onDpad(0f, 0f)
        }
        if (stickPointerId != -1 || stickX != 0f || stickY != 0f) {
            stickPointerId = -1
            stickX = 0f
            stickY = 0f
            listener.onStick(0f, 0f)
        }
        menuLatched = false
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> recompute(event)
            else -> return false
        }
        invalidate()
        return true
    }

    private fun hitButton(
        button: GlassButton,
        x: Float,
        y: Float,
    ): Boolean {
        val b = button.bounds
        return if (button.shape == GlassShape.CIRCLE) {
            val r = b.width() * 0.5f * 1.25f
            hypot(x - b.centerX(), y - b.centerY()) <= r
        } else {
            x >= b.left - b.height() * 0.2f && x <= b.right + b.height() * 0.2f &&
                y >= b.top - b.height() * 0.25f && y <= b.bottom + b.height() * 0.25f
        }
    }

    private fun recompute(event: MotionEvent) {
        val released =
            event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        val liftedPointer =
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1

        val newPressed = HashSet<Int>()
        var newDpadX = 0f
        var newDpadY = 0f
        var menuTouched = false
        var stickSeen = false
        var newStickX = stickX
        var newStickY = stickY

        if (!released) {
            for (i in 0 until event.pointerCount) {
                if (i == liftedPointer) continue
                val x = event.getX(i)
                val y = event.getY(i)
                val pointerId = event.getPointerId(i)

                if (config.hasStick) {
                    if (pointerId == stickPointerId) {
                        stickSeen = true
                        newStickX = ((x - stickCx) / stickRadius).coerceIn(-1f, 1f)
                        newStickY = ((y - stickCy) / stickRadius).coerceIn(-1f, 1f)
                        continue
                    }
                    if (stickPointerId == -1 &&
                        hypot(x - stickCx, y - stickCy) <= stickRadius * 1.3f
                    ) {
                        stickPointerId = pointerId
                        stickSeen = true
                        newStickX = ((x - stickCx) / stickRadius).coerceIn(-1f, 1f)
                        newStickY = ((y - stickCy) / stickRadius).coerceIn(-1f, 1f)
                        continue
                    }
                }

                val dxToPad = x - dpadCx
                val dyToPad = y - dpadCy
                if (hypot(dxToPad, dyToPad) <= dpadRadius * 1.4f) {
                    val dz = dpadRadius * 0.24f
                    if (dxToPad > dz) newDpadX = 1f else if (dxToPad < -dz) newDpadX = -1f
                    if (dyToPad > dz) newDpadY = 1f else if (dyToPad < -dz) newDpadY = -1f
                    continue
                }

                if (hitButton(menuButton, x, y)) {
                    menuTouched = true
                    continue
                }

                for (button in buttons) {
                    if (hitButton(button, x, y)) {
                        newPressed.add(button.keyCode)
                        break
                    }
                }
            }
        }

        if (!stickSeen && stickPointerId != -1) {
            stickPointerId = -1
            newStickX = 0f
            newStickY = 0f
        }
        if (newStickX != stickX || newStickY != stickY) {
            stickX = newStickX
            stickY = newStickY
            listener.onStick(stickX, stickY)
        }

        for (keyCode in pressedButtons) {
            if (!newPressed.contains(keyCode)) listener.onButton(keyCode, false)
        }
        for (keyCode in newPressed) {
            if (!pressedButtons.contains(keyCode)) listener.onButton(keyCode, true)
        }
        pressedButtons.clear()
        pressedButtons.addAll(newPressed)

        if (newDpadX != dpadX || newDpadY != dpadY) {
            dpadX = newDpadX
            dpadY = newDpadY
            listener.onDpad(dpadX, dpadY)
        }

        if (menuTouched && !menuLatched) {
            menuLatched = true
            listener.onMenu()
        } else if (!menuTouched) {
            menuLatched = false
        }
    }
}
