package com.armsx2.runtime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Window
import android.view.WindowManager
import kr.co.iefriends.pcsx2.NativeApp

class EmulationSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var viewWidth = 0
    private var viewHeight = 0
    private var fixedWidth = 0
    private var fixedHeight = 0

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        keepScreenOn = true
    }

    private fun hostWindow(): Window? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current.window
            current = current.baseContext
        }
        return null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hostWindow()?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDetachedFromWindow() {
        hostWindow()?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestFocus()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeApp.setDisplayRefreshRate(currentDisplayRefreshHz())
        NativeApp.onNativeSurfaceChanged(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val released = java.util.concurrent.CountDownLatch(1)
        Thread({
            runCatching { NativeApp.onNativeSurfaceChanged(null, 0, 0) }
            released.countDown()
        }, "ps2-surface-release").start()
        if (!released.await(SURFACE_RELEASE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            android.util.Log.w(
                "EmulationSurface",
                "GS thread did not release the surface within ${SURFACE_RELEASE_TIMEOUT_MS}ms",
            )
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        viewWidth = width
        viewHeight = height
        applyOutputScale()
    }

    fun applyOutputScale() {
        val multiplier = MainActivityRuntime.prefs.getInt("ui.hwScaler", 0)
        if (viewWidth <= 0 || viewHeight <= 0) return

        val shortSide = minOf(viewWidth, viewHeight)
        val targetShortSide = 448 * multiplier
        if (multiplier <= 0 || targetShortSide >= shortSide) {
            if (fixedWidth != 0 || fixedHeight != 0) {
                fixedWidth = 0
                fixedHeight = 0
                holder.setSizeFromLayout()
            }
            return
        }

        val scale = targetShortSide.toFloat() / shortSide
        val width = (viewWidth * scale).toInt().coerceAtLeast(1)
        val height = (viewHeight * scale).toInt().coerceAtLeast(1)
        if (width == fixedWidth && height == fixedHeight) return
        fixedWidth = width
        fixedHeight = height
        holder.setFixedSize(width, height)
    }

    private companion object {
        const val SURFACE_RELEASE_TIMEOUT_MS = 2000L
    }

    private fun currentDisplayRefreshHz(): Float = runCatching {
        val currentDisplay = if (android.os.Build.VERSION.SDK_INT >= 30) context.display else display
        currentDisplay?.refreshRate ?: 0f
    }.getOrDefault(0f)
}
