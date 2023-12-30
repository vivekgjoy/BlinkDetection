package com.mobil80.blinkdetection.present

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.util.SparseArray
import android.view.View
import com.google.android.gms.vision.Frame
import com.google.android.gms.vision.face.Face
import com.google.android.gms.vision.face.FaceDetector

/**
 * Created by muralikrishna on 02/11/17.
 */
/**
 * Created by Paul on 11/4/15.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var mBitmap: Bitmap? = null
    private var mFaces: SparseArray<Face>? = null
    private var leftEyeOpenProbability = -1.0
    private var rightEyeOpenProbability = -1.0
    private var leftopenRatio = 1.0
    private val detector = FaceDetector.Builder(getContext())
        .setTrackingEnabled(false)
        .setLandmarkType(FaceDetector.ALL_LANDMARKS)
        .setClassificationType(FaceDetector.ALL_CLASSIFICATIONS)
        .build()

    fun setBitmap(bitmap: Bitmap?) {
        mBitmap = bitmap
        if (!detector.isOperational) {
            //Handle contingency
        } else {
            //Log.d("time1", SystemClock.currentThreadTimeMillis()+"");
            val frame = Frame.Builder().setBitmap(bitmap).build()
            mFaces = detector.detect(frame)
        }
        if (isEyeBlinked) {
            Log.d("isEyeBlinked", "eye blink is observed")
            blinkCount++
            CameraActivity.showScore(blinkCount)
        }
        invalidate()
    }

    private val isEyeBlinked: Boolean
        private get() {
            if (mFaces!!.size() == 0) return false
            val face = mFaces!!.valueAt(0)
            val currentLeftEyeOpenProbability = face.isLeftEyeOpenProbability
            val currentRightEyeOpenProbability = face.isRightEyeOpenProbability
            if (currentLeftEyeOpenProbability.toDouble() == -1.0 || currentRightEyeOpenProbability.toDouble() == -1.0) {
                return false
            }
            return if (leftEyeOpenProbability > 0.9 || rightEyeOpenProbability > 0.9) {
                var blinked = false
                if (currentLeftEyeOpenProbability < 0.6 || rightEyeOpenProbability < 0.6) {
                    blinked = true
                }
                leftEyeOpenProbability = currentLeftEyeOpenProbability.toDouble()
                rightEyeOpenProbability = currentRightEyeOpenProbability.toDouble()
                blinked
            } else {
                leftEyeOpenProbability = currentLeftEyeOpenProbability.toDouble()
                rightEyeOpenProbability = currentRightEyeOpenProbability.toDouble()
                false
            }
        }
    private val isEyeToggled: Boolean
        private get() {
            if (mFaces!!.size() == 0) return false
            val face = mFaces!!.valueAt(0)
            val currentLeftEyeOpenProbability = face.isLeftEyeOpenProbability
            val currentRightEyeOpenProbability = face.isRightEyeOpenProbability
            if (currentLeftEyeOpenProbability.toDouble() == -1.0 || currentRightEyeOpenProbability.toDouble() == -1.0) {
                return false
            }
            var currentLeftOpenRatio =
                (currentLeftEyeOpenProbability / currentRightEyeOpenProbability).toDouble()
            if (currentLeftOpenRatio > 3) currentLeftOpenRatio = 3.0
            if (currentLeftOpenRatio < 0.33) currentLeftOpenRatio = 0.33
            Log.d("probs", "$currentLeftOpenRatio $leftopenRatio")
            if (currentLeftOpenRatio == 0.33 || currentLeftOpenRatio == 3.0) {
                if (leftopenRatio == 1.0) {
                    leftopenRatio = currentLeftOpenRatio
                }
                if (leftopenRatio * currentLeftOpenRatio == 0.99) {
                    leftopenRatio = currentLeftOpenRatio
                    return true
                }
            }
            return false
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mBitmap != null && mFaces != null) {
            val scale = drawBitmap(canvas)
            drawFaceLandmarks(canvas, scale)
        }
    }

    private fun drawBitmap(canvas: Canvas): Double {
        val viewWidth = canvas.width.toDouble()
        val viewHeight = canvas.height.toDouble()
        val imageWidth = mBitmap!!.width.toDouble()
        val imageHeight = mBitmap!!.height.toDouble()
        val scale = Math.min(viewWidth / imageWidth, viewHeight / imageHeight)
        val destBounds = Rect(0, 0, (imageWidth * scale).toInt(), (imageHeight * scale).toInt())
        canvas.drawBitmap(mBitmap!!, null, destBounds, null)
        return scale
    }

    private fun drawFaceBox(canvas: Canvas, scale: Double) {
        //This should be defined as a member variable rather than
        //being created on each onDraw request, but left here for
        //emphasis.
        val paint = Paint()
        paint.color = Color.GREEN
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        for (i in 0 until mFaces!!.size()) {
            val face = mFaces!!.valueAt(i)
            left = (face.position.x * scale).toFloat()
            top = (face.position.y * scale).toFloat()
            right = scale.toFloat() * (face.position.x + face.width)
            bottom = scale.toFloat() * (face.position.y + face.height)
            canvas.drawRect(left, top, right, bottom, paint)
        }
    }

    private fun drawFaceLandmarks(canvas: Canvas, scale: Double) {
        val paint = Paint()
        paint.color = Color.GREEN
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        for (i in 0 until mFaces!!.size()) {
            val face = mFaces!!.valueAt(i)
            for (landmark in face.landmarks) {
                val cx = (landmark.position.x * scale).toInt()
                val cy = (landmark.position.y * scale).toInt()
                canvas.drawCircle(cx.toFloat(), cy.toFloat(), 10f, paint)
            }
        }
    }

    private fun logFaceData() {
        var smilingProbability: Float
        var leftEyeOpenProbability: Float
        var rightEyeOpenProbability: Float
        var eulerY: Float
        var eulerZ: Float
        for (i in 0 until mFaces!!.size()) {
            val face = mFaces!!.valueAt(i)
            smilingProbability = face.isSmilingProbability
            leftEyeOpenProbability = face.isLeftEyeOpenProbability
            rightEyeOpenProbability = face.isRightEyeOpenProbability
            eulerY = face.eulerY
            eulerZ = face.eulerZ
            Log.e("Tuts+ Face Detection", "Smiling: $smilingProbability")
            Log.d("Tuts+ Face Detection", "Left eye open: $leftEyeOpenProbability")
            Log.d("Tuts+ Face Detection", "Right eye open: $rightEyeOpenProbability")
            Log.e("Tuts+ Face Detection", "Euler Y: $eulerY")
            Log.e("Tuts+ Face Detection", "Euler Z: $eulerZ")
        }
    }

    companion object {
        private var blinkCount = 0
    }
}