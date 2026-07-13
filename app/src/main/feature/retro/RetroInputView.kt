package com.winlator.cmod.feature.retro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class RetroInputView(
    context: Context,
    private val listener: Listener,
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

        fun onMenu()
    }

    private data class Button(
        val keyCode: Int,
        val label: String,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var radius: Float = 0f,
    )

    private val faceButtons =
        listOf(
            Button(KeyEvent.KEYCODE_BUTTON_X, "X"),
            Button(KeyEvent.KEYCODE_BUTTON_B, "B"),
            Button(KeyEvent.KEYCODE_BUTTON_Y, "Y"),
            Button(KeyEvent.KEYCODE_BUTTON_A, "A"),
        )
    private val shoulderL = Button(KeyEvent.KEYCODE_BUTTON_L1, "L")
    private val shoulderR = Button(KeyEvent.KEYCODE_BUTTON_R1, "R")
    private val selectButton = Button(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT")
    private val startButton = Button(KeyEvent.KEYCODE_BUTTON_START, "START")
    private val menuButton = Button(0, "MENU")

    private val allButtons
        get() = faceButtons + shoulderL + shoulderR + selectButton + startButton + menuButton

    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadRadius = 0f

    private val pressedButtons = HashSet<Int>()
    private var dpadX = 0f
    private var dpadY = 0f
    private var menuLatched = false

    private val basePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(90, 255, 255, 255)
        }
    private val pressedPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(170, 90, 170, 255)
        }
    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(150, 230, 230, 230)
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 20, 24, 30)
            textAlign = Paint.Align.CENTER
        }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    fun releaseAll() {
        for (keyCode in pressedButtons) listener.onButton(keyCode, false)
        pressedButtons.clear()
        if (dpadX != 0f || dpadY != 0f) {
            dpadX = 0f
            dpadY = 0f
            listener.onDpad(0f, 0f)
        }
        menuLatched = false
        invalidate()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        val width = w.toFloat()
        val height = h.toFloat()
        val unit = minOf(width, height)
        val faceRadius = unit * 0.085f
        val margin = unit * 0.06f

        dpadRadius = unit * 0.16f
        dpadCx = margin + dpadRadius
        dpadCy = height - margin - dpadRadius

        val clusterCx = width - margin - dpadRadius
        val clusterCy = height - margin - dpadRadius
        val spread = faceRadius * 1.7f
        faceButtons.forEach { it.radius = faceRadius }
        faceButtons[0].cx = clusterCx
        faceButtons[0].cy = clusterCy - spread
        faceButtons[1].cx = clusterCx
        faceButtons[1].cy = clusterCy + spread
        faceButtons[2].cx = clusterCx - spread
        faceButtons[2].cy = clusterCy
        faceButtons[3].cx = clusterCx + spread
        faceButtons[3].cy = clusterCy

        shoulderL.radius = faceRadius
        shoulderL.cx = margin + faceRadius
        shoulderL.cy = margin + faceRadius
        shoulderR.radius = faceRadius
        shoulderR.cx = width - margin - faceRadius
        shoulderR.cy = margin + faceRadius

        val smallRadius = faceRadius * 0.85f
        selectButton.radius = smallRadius
        selectButton.cx = width * 0.5f - smallRadius * 1.5f
        selectButton.cy = height - margin - smallRadius
        startButton.radius = smallRadius
        startButton.cx = width * 0.5f + smallRadius * 1.5f
        startButton.cy = height - margin - smallRadius

        menuButton.radius = smallRadius
        menuButton.cx = width * 0.5f
        menuButton.cy = margin + smallRadius
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawDpad(canvas)
        allButtons.forEach { drawButton(canvas, it) }
    }

    private fun drawDpad(canvas: Canvas) {
        val arm = dpadRadius
        val thickness = dpadRadius * 0.66f
        val horizontal =
            RectF(dpadCx - arm, dpadCy - thickness / 2, dpadCx + arm, dpadCy + thickness / 2)
        val vertical =
            RectF(dpadCx - thickness / 2, dpadCy - arm, dpadCx + thickness / 2, dpadCy + arm)
        val corner = thickness * 0.35f
        val activeX = abs(dpadX) > 0.1f
        val activeY = abs(dpadY) > 0.1f
        canvas.drawRoundRect(horizontal, corner, corner, if (activeX) pressedPaint else basePaint)
        canvas.drawRoundRect(vertical, corner, corner, if (activeY) pressedPaint else basePaint)
        canvas.drawRoundRect(horizontal, corner, corner, strokePaint)
        canvas.drawRoundRect(vertical, corner, corner, strokePaint)
    }

    private fun drawButton(
        canvas: Canvas,
        button: Button,
    ) {
        val pressed = if (button.keyCode == 0) menuLatched else pressedButtons.contains(button.keyCode)
        canvas.drawCircle(button.cx, button.cy, button.radius, if (pressed) pressedPaint else basePaint)
        canvas.drawCircle(button.cx, button.cy, button.radius, strokePaint)
        textPaint.textSize = button.radius * if (button.label.length > 2) 0.55f else 0.9f
        val textY = button.cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(button.label, button.cx, textY, textPaint)
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

    private fun recompute(event: MotionEvent) {
        val released = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        val liftedPointer =
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1

        val newPressed = HashSet<Int>()
        var newDpadX = 0f
        var newDpadY = 0f
        var menuTouched = false

        if (!released) {
            for (i in 0 until event.pointerCount) {
                if (i == liftedPointer) continue
                val x = event.getX(i)
                val y = event.getY(i)

                val dxToPad = x - dpadCx
                val dyToPad = y - dpadCy
                val padReach = dpadRadius * 1.5f
                if (dxToPad * dxToPad + dyToPad * dyToPad <= padReach * padReach) {
                    val dz = dpadRadius * 0.28f
                    if (dxToPad > dz) newDpadX = 1f else if (dxToPad < -dz) newDpadX = -1f
                    if (dyToPad > dz) newDpadY = 1f else if (dyToPad < -dz) newDpadY = -1f
                    continue
                }

                for (button in allButtons) {
                    val bdx = x - button.cx
                    val bdy = y - button.cy
                    val reach = button.radius * 1.25f
                    if (bdx * bdx + bdy * bdy <= reach * reach) {
                        if (button.keyCode == 0) {
                            menuTouched = true
                        } else {
                            newPressed.add(button.keyCode)
                        }
                        break
                    }
                }
            }
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
