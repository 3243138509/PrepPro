package com.PrepPro.mobile.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView

/**
 * HorizontalScrollView that allows its single child to expand to full content width
 * without wrapping, enabling horizontal scroll for long lines (e.g. code).
 */
class CodeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    override fun measureChild(child: View, parentWidthMeasureSpec: Int, parentHeightMeasureSpec: Int) {
        val lp = child.layoutParams
        val childWidthMeasureSpec = if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        } else {
            ViewGroup.getChildMeasureSpec(
                parentWidthMeasureSpec,
                paddingLeft + paddingRight,
                lp.width,
            )
        }
        val childHeightMeasureSpec = ViewGroup.getChildMeasureSpec(
            parentHeightMeasureSpec,
            paddingTop + paddingBottom,
            lp.height,
        )
        child.measure(childWidthMeasureSpec, childHeightMeasureSpec)
    }

    override fun measureChildWithMargins(
        child: View,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int,
    ) {
        val lp = child.layoutParams as MarginLayoutParams
        val childWidthMeasureSpec = if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        } else {
            ViewGroup.getChildMeasureSpec(
                parentWidthMeasureSpec,
                paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin + widthUsed,
                lp.width,
            )
        }
        val childHeightMeasureSpec = ViewGroup.getChildMeasureSpec(
            parentHeightMeasureSpec,
            paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed,
            lp.height,
        )
        child.measure(childWidthMeasureSpec, childHeightMeasureSpec)
    }
}
