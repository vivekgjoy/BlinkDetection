package com.mobil80.blinkdetection.present

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.lang.ref.WeakReference

internal class OverlayView(
    captureActivity: CameraActivity,
    attributeSet: AttributeSet?,
    private val mShowTorch: Boolean
) : View(captureActivity, attributeSet) {
    private val mScanActivityRef: WeakReference<CameraActivity>
    private var mBitmap: Bitmap? = null
    var mScanLineDrawable: GradientDrawable? = null
    private var mGuide: Rect? = null
    private var mRotation = 0
    private val mState = 0
    var guideColor = 0
    var hideCardIOLogo = false
    var scanInstructions: String? = null

    // Keep paint objects around for high frequency methods to avoid re-allocating them.
    private var mGradientDrawable: GradientDrawable? = null
    private val mGuidePaint: Paint
    private val mLockedBackgroundPaint: Paint
    private var mLockedBackgroundPath: Path? = null
    private var mCameraPreviewRect: Rect? = null

    // for test
    var torchRect: Rect? = null
        private set
    private var mLogoRect: Rect? = null
    private var mRotationFlip: Int
    private var mScale = 1f

    init {
        mScanActivityRef = WeakReference(captureActivity)
        mRotationFlip = 1

        // card.io is designed for an hdpi screen (density = 1.5);
        mScale = resources.displayMetrics.density / 1.5f
        mGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mLockedBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mLockedBackgroundPaint.clearShadowLayer()
        mLockedBackgroundPaint.style = Paint.Style.FILL
        mLockedBackgroundPaint.color = -0x45000000 // 75% black
    }

    // Public methods used by CardIOActivity
    fun setGuideAndRotation(rect: Rect?, rotation: Int) {
        mRotation = rotation
        mGuide = rect
        invalidate()
        val topEdgeUIOffset: Point
        if (mRotation % 180 != 0) {
            topEdgeUIOffset = Point((40 * mScale).toInt(), (60 * mScale).toInt())
            mRotationFlip = -1
        } else {
            topEdgeUIOffset = Point((60 * mScale).toInt(), (40 * mScale).toInt())
            mRotationFlip = 1
        }
        if (mCameraPreviewRect != null) {
            val torchPoint = Point(
                mCameraPreviewRect!!.left + topEdgeUIOffset.x,
                mCameraPreviewRect!!.top + topEdgeUIOffset.y
            )

            // mTorchRect used only for touch lookup, not layout
            torchRect = BlinkUtilFuns.rectGivenCenter(
                torchPoint,
                (TORCH_WIDTH * mScale).toInt(),
                (TORCH_HEIGHT * mScale).toInt()
            )

            // mLogoRect used only for touch lookup, not layout
            val logoPoint = Point(
                mCameraPreviewRect!!.right - topEdgeUIOffset.x,
                mCameraPreviewRect!!.top + topEdgeUIOffset.y
            )
            mLogoRect = BlinkUtilFuns.rectGivenCenter(
                logoPoint,
                (LOGO_MAX_WIDTH * mScale).toInt(),
                (LOGO_MAX_HEIGHT * mScale).toInt()
            )
            val gradientColors = intArrayOf(Color.WHITE, Color.BLACK)
            val gradientOrientation = GRADIENT_ORIENTATIONS[mRotation / 90 % 4]
            mGradientDrawable = GradientDrawable(gradientOrientation, gradientColors)
            mGradientDrawable!!.gradientType = GradientDrawable.LINEAR_GRADIENT
            mGradientDrawable!!.bounds = mGuide!!
            mGradientDrawable!!.alpha = 50
            mLockedBackgroundPath = Path()
            mLockedBackgroundPath!!.addRect(RectF(mCameraPreviewRect), Path.Direction.CW)
            mLockedBackgroundPath!!.addRect(RectF(mGuide), Path.Direction.CCW)
        }
    }

    var bitmap: Bitmap?
        get() = mBitmap
        set(bitmap) {
            if (mBitmap != null) {
                mBitmap!!.recycle()
            }
            mBitmap = bitmap
            if (mBitmap != null) {
                decorateBitmap()
            }
        }
    val cardX: Int
        get() = mGuide!!.centerX() - mBitmap!!.width / 2
    val cardY: Int
        get() = mGuide!!.centerY() - mBitmap!!.height / 2
    val cardImage: Bitmap?
        get() = if (mBitmap != null && !mBitmap!!.isRecycled) {
            Bitmap.createBitmap(mBitmap!!, 0, 0, mBitmap!!.width, mBitmap!!.height)
        } else {
            null
        }

    // Drawing methods
    private fun guideStrokeRect(x1: Int, y1: Int, x2: Int, y2: Int): Rect {
        val r: Rect
        val t2 = (GUIDE_STROKE_WIDTH / 2 * mScale).toInt()
        r = Rect()
        r.left = Math.min(x1, x2) - t2
        r.right = Math.max(x1, x2) + t2
        r.top = Math.min(y1, y2) - t2
        r.bottom = Math.max(y1, y2) + t2
        return r
    }

    public override fun onDraw(canvas: Canvas) {
        if (mGuide == null || mCameraPreviewRect == null) {
            return
        }
        canvas.save()
        val tickLength: Int

        // Draw background rect
        mGradientDrawable!!.draw(canvas)
        tickLength = if (mRotation == 0 || mRotation == 180) {
            (mGuide!!.bottom - mGuide!!.top) / 4
        } else {
            (mGuide!!.right - mGuide!!.left) / 4
        }

        // Draw guide lines
        mGuidePaint.clearShadowLayer()
        mGuidePaint.style = Paint.Style.FILL
        mGuidePaint.color = guideColor

        // top left
        canvas.drawRect(
            guideStrokeRect(mGuide!!.left, mGuide!!.top, mGuide!!.left + tickLength, mGuide!!.top),
            mGuidePaint
        )
        canvas.drawRect(
            guideStrokeRect(mGuide!!.left, mGuide!!.top, mGuide!!.left, mGuide!!.top + tickLength),
            mGuidePaint
        )

        // top right
        canvas.drawRect(
            guideStrokeRect(
                mGuide!!.right,
                mGuide!!.top,
                mGuide!!.right - tickLength,
                mGuide!!.top
            ),
            mGuidePaint
        )
        canvas.drawRect(
            guideStrokeRect(
                mGuide!!.right,
                mGuide!!.top,
                mGuide!!.right,
                mGuide!!.top + tickLength
            ),
            mGuidePaint
        )

        // bottom left
        canvas.drawRect(
            guideStrokeRect(
                mGuide!!.left,
                mGuide!!.bottom,
                mGuide!!.left + tickLength,
                mGuide!!.bottom
            ),
            mGuidePaint
        )
        canvas.drawRect(
            guideStrokeRect(
                mGuide!!.left,
                mGuide!!.bottom,
                mGuide!!.left,
                mGuide!!.bottom - tickLength
            ),
            mGuidePaint
        )

        // bottom right
        canvas.drawRect(
            guideStrokeRect(
                mGuide!!.right, mGuide!!.bottom, mGuide!!.right - tickLength,
                mGuide!!.bottom
            ), mGuidePaint
        )
        canvas.drawRect(
            guideStrokeRect(
                mGuide!!.right, mGuide!!.bottom, mGuide!!.right, mGuide!!.bottom
                        - tickLength
            ), mGuidePaint
        )
        canvas.restore()

        // draw logo
        if (!hideCardIOLogo) {
            canvas.save()
            canvas.translate(mLogoRect!!.exactCenterX(), mLogoRect!!.exactCenterY())
            canvas.rotate((mRotationFlip * mRotation).toFloat())
            canvas.restore()
        }
        if (mShowTorch) {
            // draw torch
            canvas.save()
            canvas.translate(torchRect!!.exactCenterX(), torchRect!!.exactCenterY())
            canvas.rotate((mRotationFlip * mRotation).toFloat())
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            val action: Int
            action = event.action and MotionEvent.ACTION_MASK
            if (action == MotionEvent.ACTION_DOWN) {
                val p = Point(event.x.toInt(), event.y.toInt())
                val r = BlinkUtilFuns.rectGivenCenter(p, BUTTON_TOUCH_TOLERANCE, BUTTON_TOUCH_TOLERANCE)
                if (mShowTorch && torchRect != null && Rect.intersects(torchRect!!, r)) {
                    mScanActivityRef.get()!!.toggleFlash()
                } else {
                    mScanActivityRef.get()!!.triggerAutoFocus()
                }
            }
        } catch (e: NullPointerException) {
            // Un-reproducible NPE reported on device without flash where flash detected and flash
            // button pressed (see https://github.com/paypal/PayPal-Android-SDK/issues/27)
            Log.d(TAG, "NullPointerException caught in onTouchEvent method")
        }
        return false
    }

    private fun decorateBitmap() {
        val roundedRect =
            RectF(2f, 2f, (mBitmap!!.width - 2).toFloat(), (mBitmap!!.height - 2).toFloat())
        val cornerRadius = mBitmap!!.height * CORNER_RADIUS_SIZE

        // Alpha canvas with white rounded rect
        val maskBitmap = Bitmap.createBitmap(
            mBitmap!!.width, mBitmap!!.height,
            Bitmap.Config.ARGB_8888
        )
        val maskCanvas = Canvas(maskBitmap)
        maskCanvas.drawColor(Color.TRANSPARENT)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        maskPaint.color = Color.BLACK
        maskPaint.style = Paint.Style.FILL
        maskCanvas.drawRoundRect(roundedRect, cornerRadius, cornerRadius, maskPaint)
        val paint = Paint()
        paint.isFilterBitmap = false

        // Draw mask onto mBitmap
        val canvas = Canvas(mBitmap!!)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(maskBitmap, 0f, 0f, paint)

        // Now re-use the above bitmap to do a shadow.
        paint.xfermode = null
        maskBitmap.recycle()
    }

    val isAnimating: Boolean
        get() = mState != 0

    fun setCameraPreviewRect(rect: Rect?) {
        mCameraPreviewRect = rect
    }

    companion object {
        private val TAG = OverlayView::class.java.simpleName
        private const val GUIDE_FONT_SIZE = 26.0f
        private const val GUIDE_LINE_PADDING = 8.0f
        private const val GUIDE_LINE_HEIGHT = GUIDE_FONT_SIZE + GUIDE_LINE_PADDING
        private const val CARD_NUMBER_MARKUP_FONT_SIZE = GUIDE_FONT_SIZE + 2
        private val GRADIENT_ORIENTATIONS = arrayOf(
            GradientDrawable.Orientation.TOP_BOTTOM,
            GradientDrawable.Orientation.LEFT_RIGHT,
            GradientDrawable.Orientation.BOTTOM_TOP,
            GradientDrawable.Orientation.RIGHT_LEFT
        )
        private const val GUIDE_STROKE_WIDTH = 17
        private const val CORNER_RADIUS_SIZE = 1 / 15.0f
        private const val TORCH_WIDTH = 70
        private const val TORCH_HEIGHT = 50
        private const val LOGO_MAX_WIDTH = 100
        private const val LOGO_MAX_HEIGHT = TORCH_HEIGHT
        private const val BUTTON_TOUCH_TOLERANCE = 20
    }
}