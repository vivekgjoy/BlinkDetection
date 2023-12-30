package com.mobil80.blinkdetection.present

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup

/**
 * Created by muralikrishna on 02/11/17.
 */
/**
 * This class contains a SurfaceView, which is used to display the camera preview frames. It
 * performs basic layout and life cycle tasks for the camera and camera previews.
 *
 *
 * Technical note: display of camera preview frames will only work when there is a valid surface
 * view to display those frames on. To that end, I have added a valid surface flag that is updated
 * by surface view lifecycle callbacks. We only attempt (re-)start camera preview if there is a
 * valid surface view to draw on.
 */
class Preview(
    context: Context?,
    attributeSet: AttributeSet?,
    private val mPreviewHeight: Int,
    private val mPreviewWidth: Int
) : ViewGroup(context, attributeSet) {
    var mSurfaceView: SurfaceView?

    init {

        // the preview size comes from the cardScanner (camera)
        // need to swap width & height to account for implicit 90deg rotation
        // which is part of cardScanner. see "mCamera.setDisplayOrientation(90);"
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

    // ------------------------------------------------------------------------
    // LAYOUT METHODS
    // ------------------------------------------------------------------------
    // TODO - document
    // Need a better explanation of why onMeasure is needed and how width/height are determined.
    // Must the camera be set first via setCamera? What if mSupportedPreviewSizes == null?
    // Why do we startPreview within this method if the surface is valid?
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // We purposely disregard child measurements because act as a
        // wrapper to a SurfaceView that centers the camera preview instead
        // of stretching it.
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    // TODO - document
    // What is the child surface? The camera preview image?
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