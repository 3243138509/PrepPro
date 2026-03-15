package com.PrepPro.mobile.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager.widget.ViewPager
import kotlin.math.max

class EdgeAwareViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewPager(context, attrs) {

    private var ignoreForSystemBackGesture = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignoreForSystemBackGesture = startsInsideSystemGestureEdge(ev.x)
                if (ignoreForSystemBackGesture) {
                    return false
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> ignoreForSystemBackGesture = false
        }

        if (ignoreForSystemBackGesture) {
            return false
        }

        return runCatching { super.onInterceptTouchEvent(ev) }.getOrDefault(false)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ignoreForSystemBackGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                ignoreForSystemBackGesture = false
            }
            return false
        }

        val handled = runCatching { super.onTouchEvent(ev) }.getOrDefault(false)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            ignoreForSystemBackGesture = false
        }
        return handled
    }

    private fun startsInsideSystemGestureEdge(x: Float): Boolean {
        val leftEdgeWidth = systemGestureEdgeWidthPx(isLeft = true)
        val rightEdgeWidth = systemGestureEdgeWidthPx(isLeft = false)
        return x <= leftEdgeWidth || x >= width - rightEdgeWidth
    }

    private fun systemGestureEdgeWidthPx(isLeft: Boolean): Float {
        val fallbackPx = dpToPx(32f).toFloat()
        val insets = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.systemGestures())
        val systemInset = if (isLeft) insets?.left ?: 0 else insets?.right ?: 0
        return max(fallbackPx, systemInset + dpToPx(8f).toFloat())
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}