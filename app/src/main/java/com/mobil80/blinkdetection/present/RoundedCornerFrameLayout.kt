package com.mobil80.blinkdetection.present

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

class RoundedCornerFrameLayout : FrameLayout {
    private var cornerRadius = 0f // Initialize with a default value

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

    // Override the onDraw method to set rounded corners
    override fun onDraw(canvas: Canvas) {
        val path = Path()
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(path)
        super.onDraw(canvas)
    }

    // Set the corner radius
    fun setCornerRadius(radius: Float) {
        cornerRadius = radius
        invalidate()
    }
}