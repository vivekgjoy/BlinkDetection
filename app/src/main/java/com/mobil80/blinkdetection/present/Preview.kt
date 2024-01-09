package com.mobil80.blinkdetection.present

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup

class Preview(
    context: Context?,
    attributeSet: AttributeSet?,
    private val mPreviewHeight: Int,
    private val mPreviewWidth: Int
) : ViewGroup(context, attributeSet) {
    var mSurfaceView: SurfaceView?

    init {
        mSurfaceView = SurfaceView(context)
        addView(mSurfaceView)
    }

    val surfaceView: SurfaceView?
        get() {
            assert(mSurfaceView != null)
            return mSurfaceView
        }
    val surfaceHolder: SurfaceHolder?
        get() = surfaceView!!.holder!!

    public override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawARGB(255, 255, 0, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // We purposely disregard child measurements because act as a
        // wrapper to a SurfaceView that centers the camera preview instead
        // of stretching it.
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (changed && childCount > 0) {
            assert(mSurfaceView != null)
            val width = r - l
            val height = b - t

            // Center the child SurfaceView within the parent, making sure that the preview is
            // *always* fully contained on the device screen.
            if (width * mPreviewHeight > height * mPreviewWidth) {
                val scaledChildWidth = mPreviewWidth * height / mPreviewHeight
                mSurfaceView!!.layout(
                    (width - scaledChildWidth) / 2, 0,
                    (width + scaledChildWidth) / 2, height
                )
            } else {
                val scaledChildHeight = mPreviewHeight * width / mPreviewWidth
                mSurfaceView!!.layout(
                    0, (height - scaledChildHeight) / 2, width,
                    (height + scaledChildHeight) / 2
                )
            }
        }
    }

    companion object {
        private val TAG = Preview::class.java.simpleName
    }
}