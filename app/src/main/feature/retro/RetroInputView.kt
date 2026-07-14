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

        fun onRightStick(
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
        val textScale: Float = 1f,
        val bounds: RectF = RectF(),
    )

    private class CButton(
        val dx: Float,
        val dy: Float,
        val glyph: String,
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
    private val cButtons = mutableListOf<CButton>()
    private val menuButton = GlassButton(0, "MENU", GlassShape.PILL, textScale = 0.75f)
    private var snap = 0f
    private var cStickX = 0f
    private var cStickY = 0f

    private val cAccentStroke = Color.argb(150, 255, 210, 90)
    private val cAccentPressedStroke = Color.argb(220, 255, 214, 96)
    private val cAccentPressedFill = Color.argb(60, 255, 210, 90)
    private val cAccentText = Color.argb(255, 255, 214, 96)

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
        cButtons.clear()
        val width = w.toFloat()
        val height = h.toFloat()
        if (height > width) {
            layoutPortrait(width, height)
        } else {
            layoutLandscape(width, height)
        }
    }

    private fun layoutLandscape(
        width: Float,
        height: Float,
    ) {
        if (config.hasStick) {
            layoutN64(width, height)
            return
        }
        snap = width / 100f
        val margin = snap * 2.5f
        val bottomGap = snap * 9f
        val faceRadius = snap * 3f
        strokeWidth = max(2f, snap * 0.18f)

        val trigW = snap * 10.4f
        val trigH = snap * 5.2f
        val trigGap = snap * 1.5f

        var leftCursor = margin
        var rightCursor = margin
        if (config.hasTriggers) {
            val lt =
                GlassButton(KeyEvent.KEYCODE_BUTTON_L2, config.leftTriggerLabel, GlassShape.TRIGGER_LT, textScale = 1.3f)
            lt.bounds.set(margin, leftCursor, margin + trigW, leftCursor + trigH)
            buttons += lt
            leftCursor += trigH + trigGap
            if (config.showRightTrigger) {
                val rt =
                    GlassButton(
                        KeyEvent.KEYCODE_BUTTON_R2,
                        config.rightTriggerLabel,
                        GlassShape.TRIGGER_RT,
                        textScale = 1.3f,
                    )
                rt.bounds.set(width - margin - trigW, rightCursor, width - margin, rightCursor + trigH)
                buttons += rt
                rightCursor += trigH + trigGap
            }
        }
        if (config.hasShoulders) {
            val lb = GlassButton(KeyEvent.KEYCODE_BUTTON_L1, "L", GlassShape.TRIGGER_LB, textScale = 1.3f)
            lb.bounds.set(margin, leftCursor, margin + trigW, leftCursor + trigH)
            buttons += lb
            val rb = GlassButton(KeyEvent.KEYCODE_BUTTON_R1, "R", GlassShape.TRIGGER_RB, textScale = 1.3f)
            rb.bounds.set(width - margin - trigW, rightCursor, width - margin, rightCursor + trigH)
            buttons += rb
            leftCursor += trigH + trigGap
        }

        val spread = snap * 5.5f
        val clusterCx = width - margin - faceRadius - spread
        val clusterCy = height - bottomGap - faceRadius - spread
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
            addFace(KeyEvent.KEYCODE_BUTTON_B, "B", clusterCx - faceRadius * 1.1f, clusterCy + spread * 0.5f + faceRadius * 0.5f)
            addFace(KeyEvent.KEYCODE_BUTTON_A, "A", clusterCx + faceRadius * 1.1f, clusterCy + spread * 0.5f - faceRadius * 1.1f)
        }

        val pillW = snap * 6f
        val pillH = snap * 3f
        val pillGap = snap * 1.2f
        val pillY = clusterTop - pillH - snap * 3.5f
        val start = GlassButton(KeyEvent.KEYCODE_BUTTON_START, "START", GlassShape.PILL, textScale = 0.75f)
        start.bounds.set(width - margin - pillW, pillY, width - margin, pillY + pillH)
        buttons += start
        val select = GlassButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", GlassShape.PILL, textScale = 0.75f)
        select.bounds.set(
            width - margin - pillW * 2 - pillGap,
            pillY,
            width - margin - pillW - pillGap,
            pillY + pillH,
        )
        buttons += select

        val menuW = snap * 6f
        stickRadius = 0f
        dpadRadius = snap * 7.5f
        dpadCx = margin + dpadRadius
        dpadCy = height - bottomGap - dpadRadius
        val menuY = max(pillY, leftCursor)
        menuButton.bounds.set(dpadCx - menuW * 0.5f, menuY, dpadCx + menuW * 0.5f, menuY + pillH)
    }

    private fun layoutN64(
        width: Float,
        height: Float,
    ) {
        snap = width / 100f
        strokeWidth = max(2f, snap * 0.18f)
        val margin = snap * 2.5f
        val trigW = snap * 10.4f
        val trigH = snap * 5.2f
        val trigGap = snap * 1.5f

        val lb = GlassButton(KeyEvent.KEYCODE_BUTTON_L1, "L", GlassShape.TRIGGER_LB, textScale = 1.3f)
        lb.bounds.set(margin, margin, margin + trigW, margin + trigH)
        buttons += lb
        val z =
            GlassButton(KeyEvent.KEYCODE_BUTTON_L2, config.leftTriggerLabel, GlassShape.TRIGGER_RT, textScale = 1.3f)
        z.bounds.set(width - margin - trigW, margin, width - margin, margin + trigH)
        buttons += z
        val rb = GlassButton(KeyEvent.KEYCODE_BUTTON_R1, "R", GlassShape.TRIGGER_RB, textScale = 1.3f)
        rb.bounds.set(
            width - margin - trigW,
            margin + trigH + trigGap,
            width - margin,
            margin + trigH * 2 + trigGap,
        )
        buttons += rb

        val faceRadius = snap * 3f
        val spread = snap * 5.5f
        val clusterCx = width - margin - faceRadius - spread
        val clusterCy = height - snap * 4.5f - faceRadius - spread
        val bCx = clusterCx - faceRadius * 0.9f
        val bCy = clusterCy + spread * 0.5f - faceRadius * 0.9f
        val bButton = GlassButton(KeyEvent.KEYCODE_BUTTON_Y, "B", GlassShape.CIRCLE)
        bButton.bounds.set(bCx - faceRadius, bCy - faceRadius, bCx + faceRadius, bCy + faceRadius)
        buttons += bButton
        val aCx = clusterCx + faceRadius * 0.9f
        val aCy = clusterCy + spread * 0.5f + faceRadius * 0.9f
        val aButton = GlassButton(KeyEvent.KEYCODE_BUTTON_B, "A", GlassShape.CIRCLE)
        aButton.bounds.set(aCx - faceRadius, aCy - faceRadius, aCx + faceRadius, aCy + faceRadius)
        buttons += aButton

        val pillW = snap * 6f
        val pillH = snap * 3f
        val pillGap = snap * 1.2f
        val pillY = height - snap * 2.5f - pillH
        var pillX = (width - pillW * 3f - pillGap * 2f) * 0.5f
        menuButton.bounds.set(pillX, pillY, pillX + pillW, pillY + pillH)
        pillX += pillW + pillGap
        val select = GlassButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", GlassShape.PILL, textScale = 0.75f)
        select.bounds.set(pillX, pillY, pillX + pillW, pillY + pillH)
        buttons += select
        pillX += pillW + pillGap
        val start = GlassButton(KeyEvent.KEYCODE_BUTTON_START, "START", GlassShape.PILL, textScale = 0.75f)
        start.bounds.set(pillX, pillY, pillX + pillW, pillY + pillH)
        buttons += start

        stickRadius = snap * 7f
        stickCx = margin + stickRadius + snap * 1f
        stickCy = height - snap * 5.5f - stickRadius
        dpadRadius = snap * 6.5f
        dpadCx = stickCx
        dpadCy = stickCy - stickRadius - snap * 2f - dpadRadius

        val cRadius = snap * 2.4f
        val cSpread = snap * 3.6f
        val cCx = clusterCx + snap * 2f
        val topOfFaces = bCy - faceRadius
        val bottomOfTriggers = margin + trigH * 2 + trigGap
        val cCy =
            min(
                (bottomOfTriggers + topOfFaces) * 0.5f + snap * 2f,
                topOfFaces - snap * 1.5f - cSpread - cRadius,
            )
        fun addC(
            dx: Float,
            dy: Float,
            glyph: String,
            x: Float,
            y: Float,
        ) {
            val c = CButton(dx, dy, glyph)
            c.bounds.set(x - cRadius, y - cRadius, x + cRadius, y + cRadius)
            cButtons += c
        }
        addC(0f, -1f, "▲", cCx, cCy - cSpread)
        addC(0f, 1f, "▼", cCx, cCy + cSpread)
        addC(-1f, 0f, "◀", cCx - cSpread, cCy)
        addC(1f, 0f, "▶", cCx + cSpread, cCy)
    }

    private fun layoutPortrait(
        width: Float,
        height: Float,
    ) {
        snap = width / 100f
        strokeWidth = max(2f, snap * 0.4f)
        stickRadius = 0f
        val zoneTop = height * 0.5f
        val zoneH = height - zoneTop
        val margin = snap * 5f

        if (config.hasShoulders) {
            val trigW = snap * 24f
            val trigH = snap * 8f
            val lb = GlassButton(KeyEvent.KEYCODE_BUTTON_L1, "L", GlassShape.TRIGGER_LB, textScale = 1.3f)
            lb.bounds.set(margin, zoneTop + snap * 2f, margin + trigW, zoneTop + snap * 2f + trigH)
            buttons += lb
            val rb = GlassButton(KeyEvent.KEYCODE_BUTTON_R1, "R", GlassShape.TRIGGER_RB, textScale = 1.3f)
            rb.bounds.set(width - margin - trigW, zoneTop + snap * 2f, width - margin, zoneTop + snap * 2f + trigH)
            buttons += rb
        }

        val rowCy = zoneTop + zoneH * 0.45f
        dpadRadius = snap * 15f
        dpadCx = margin + dpadRadius
        dpadCy = rowCy

        val faceRadius = snap * 8.5f
        fun addFace(
            keyCode: Int,
            label: String,
            cx: Float,
            cy: Float,
        ) {
            val button = GlassButton(keyCode, label, GlassShape.CIRCLE)
            button.bounds.set(cx - faceRadius, cy - faceRadius, cx + faceRadius, cy + faceRadius)
            buttons += button
        }
        addFace(KeyEvent.KEYCODE_BUTTON_A, "A", width - margin - faceRadius, rowCy - faceRadius * 0.9f)
        addFace(
            KeyEvent.KEYCODE_BUTTON_B,
            "B",
            width - margin - faceRadius * 3f - snap * 2f,
            rowCy + faceRadius * 0.9f,
        )

        val pillW = snap * 13f
        val pillH = snap * 5.5f
        val pillGap = snap * 2f
        val pillY = height - margin - pillH
        val select = GlassButton(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", GlassShape.PILL, textScale = 0.75f)
        select.bounds.set(width * 0.5f - pillGap * 0.5f - pillW, pillY, width * 0.5f - pillGap * 0.5f, pillY + pillH)
        buttons += select
        val start = GlassButton(KeyEvent.KEYCODE_BUTTON_START, "START", GlassShape.PILL, textScale = 0.75f)
        start.bounds.set(width * 0.5f + pillGap * 0.5f, pillY, width * 0.5f + pillGap * 0.5f + pillW, pillY + pillH)
        buttons += start

        val menuW = snap * 13f
        menuButton.bounds.set(
            width * 0.5f - menuW * 0.5f,
            zoneTop + snap * 1.5f,
            width * 0.5f + menuW * 0.5f,
            zoneTop + snap * 1.5f + pillH,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.strokeWidth = strokeWidth
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        drawDpad(canvas)
        if (config.hasStick) drawStick(canvas)
        buttons.forEach { drawGlassButton(canvas, it, pressedButtons.contains(it.keyCode)) }
        cButtons.forEach { drawCButton(canvas, it) }
        drawGlassButton(canvas, menuButton, menuLatched)
    }

    private fun drawCButton(
        canvas: Canvas,
        button: CButton,
    ) {
        val b = button.bounds
        val pressed =
            (button.dx != 0f && cStickX == button.dx) || (button.dy != 0f && cStickY == button.dy)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawCircle(b.centerX(), b.centerY(), b.width() * 0.5f, paint)
        if (pressed) {
            paint.color = cAccentPressedFill
            canvas.drawCircle(b.centerX(), b.centerY(), b.width() * 0.5f, paint)
        }
        paint.shader =
            RadialGradient(
                b.centerX(),
                b.centerY(),
                b.width() * 0.5f,
                Color.argb(0, 0, 0, 0),
                Color.argb(glassEdgeAlpha, 0, 0, 0),
                Shader.TileMode.CLAMP,
            )
        canvas.drawCircle(b.centerX(), b.centerY(), b.width() * 0.5f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = if (pressed) cAccentPressedStroke else cAccentStroke
        canvas.drawCircle(b.centerX(), b.centerY(), b.width() * 0.5f, paint)
        paint.style = Paint.Style.FILL
        paint.color = cAccentText
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.textSize = b.width() * 0.5f
        val textY = b.centerY() - (paint.descent() + paint.ascent()) * 0.5f
        canvas.drawText(button.glyph, b.centerX(), textY, paint)
        paint.isFakeBoldText = false
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
        val maxTextWidth = b.width() - strokeWidth * 2
        paint.textSize = snap * 2f * button.textScale
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
        if (cStickX != 0f || cStickY != 0f) {
            cStickX = 0f
            cStickY = 0f
            listener.onRightStick(0f, 0f)
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
        val inflate = snap * 1.2f
        return if (button.shape == GlassShape.CIRCLE) {
            val r = b.width() * 0.5f + inflate
            hypot(x - b.centerX(), y - b.centerY()) <= r
        } else {
            x >= b.left - inflate && x <= b.right + inflate &&
                y >= b.top - inflate && y <= b.bottom + inflate
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
        var newCX = 0f
        var newCY = 0f
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

                var cHit = false
                for (c in cButtons) {
                    val reach = c.bounds.width() * 0.5f + snap * 1.2f
                    if (hypot(x - c.bounds.centerX(), y - c.bounds.centerY()) <= reach) {
                        if (c.dx != 0f) newCX = c.dx
                        if (c.dy != 0f) newCY = c.dy
                        cHit = true
                        break
                    }
                }
                if (cHit) continue

                for (button in buttons) {
                    if (hitButton(button, x, y)) {
                        newPressed.add(button.keyCode)
                        break
                    }
                }
            }
        }

        if (newCX != cStickX || newCY != cStickY) {
            cStickX = newCX
            cStickY = newCY
            listener.onRightStick(cStickX, cStickY)
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
