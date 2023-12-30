package com.mobil80.blinkdetection.present

import android.content.Context
import android.graphics.*
import android.hardware.Camera
import android.hardware.Camera.*
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Encapsulates the core image scanning.
 *
 *
 * As of 7/20/12, the flow should be:
 *
 *
 * 1. CardIOActivity sets up the CardScanner, Preview and Overlay. 2. As each frame is received &
 * processed by the scanner, the scanner notifies the activity of any relevant changes. (e.g. edges
 * detected, scan complete etc.) 3. CardIOActivity passes on the information to the preview and
 * overlay, which can then update themselves as needed. 4. Once a result is reported, CardIOActivty
 * closes the scanner and launches the next activity.
 *
 *
 * HOWEVER, at the moment, the CardScanner is directly communicating with the Preview.
 */
//TODO: implement autofocus of the camera
internal class FaceScanner(scanActivity: CameraActivity, currentFrameOrientation: Int) :
    PreviewCallback, AutoFocusCallback, SurfaceHolder.Callback {
    private var detectedBitmap: Bitmap? = null

    // member data
    protected var mScanActivityRef: WeakReference<CameraActivity>
    private val mSuppressScan = false
    private val mScanExpiry = false
    private val mUnblurDigits = DEFAULT_UNBLUR_DIGITS

    // read by CardIOActivity to set up Preview
    val mPreviewWidth = 640
    val mPreviewHeight = 480
    private var mFirstPreviewFrame = true
    private var mAutoFocusStartedAt: Long = 0
    private var mAutoFocusCompletedAt: Long = 0
    private var mCamera: Camera? = null
    private var mPreviewBuffer: ByteArray? = null

    // accessed by test harness subclass.
    protected var useCamera = true
    private var isSurfaceValid = false

    /**
     * Connect or reconnect to camera. If fails, sleeps and tries again. Returns `true` if successful,
     * `false` if maxTimeout passes.
     */
    private fun openFrontFacingCameraGingerbread(): Camera? {
        var cameraCount = 0
        var cam: Camera? = null
        val cameraInfo = CameraInfo()
        cameraCount = Camera.getNumberOfCameras()
        for (camIdx in 0 until cameraCount) {
            Camera.getCameraInfo(camIdx, cameraInfo)
            if (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
                try {
                    cam = Camera.open(camIdx)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Camera failed to open: " + e.localizedMessage)
                }
            }
        }
        return cam
    }

    private fun connectToCamera(checkInterval: Int, maxTimeout: Int): Camera? {
        var maxTimeout = maxTimeout
        val start = System.currentTimeMillis()
        if (useCamera) {
            do {
                try {
                    // Camera.open() will open the back-facing camera. Front cameras are not
                    // attempted.
                    return openFrontFacingCameraGingerbread()
                } catch (e: RuntimeException) {
                    try {
                        Log.w(
                            TAG,
                            "Wasn't able to connect to camera service. Waiting and trying again..."
                        )
                        Thread.sleep(checkInterval.toLong())
                    } catch (e1: InterruptedException) {
                        Log.e(TAG, "Interrupted while waiting for camera", e1)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected exception. Please report it as a GitHub issue", e)
                    maxTimeout = 0
                }
            } while (System.currentTimeMillis() - start < maxTimeout)
        }
        Log.w(TAG, "camera connect timeout")
        return null
    }

    fun prepareScanner() {
        mFirstPreviewFrame = true
        mAutoFocusStartedAt = 0
        mAutoFocusCompletedAt = 0
        if (useCamera && mCamera == null) {
            mCamera = connectToCamera(CAMERA_CONNECT_RETRY_INTERVAL, CAMERA_CONNECT_TIMEOUT)
            if (mCamera == null) {
                Log.e(TAG, "prepare scanner couldn't connect to camera!")
                return
            }
            setCameraDisplayOrientation(mCamera!!)
            val parameters = mCamera!!.parameters
            val supportedPreviewSizes = parameters.supportedPreviewSizes
            if (supportedPreviewSizes != null) {
                var previewSize: Camera.Size? = null
                for (s in supportedPreviewSizes) {
                    if (s.width == 640 && s.height == 480) {
                        previewSize = s
                        break
                    }
                }
                if (previewSize == null) {
                    Log.w(
                        TAG,
                        "Didn't find a supported 640x480 resolution, so forcing"
                    )
                    previewSize = supportedPreviewSizes[0]
                    previewSize.width = mPreviewWidth
                    previewSize.height = mPreviewHeight
                }
            }
            parameters.setPreviewSize(mPreviewWidth, mPreviewHeight)
            mCamera!!.parameters = parameters
        } else if (!useCamera) {
            Log.w(TAG, "useCamera is false!")
        } else if (mCamera != null) {
            Log.v(TAG, "we already have a camera instance: $mCamera")
        }
        if (detectedBitmap == null) {
            detectedBitmap = Bitmap.createBitmap(
                CREDIT_CARD_TARGET_WIDTH,
                CREDIT_CARD_TARGET_HEIGHT, Bitmap.Config.ARGB_8888
            )
        }
    }

    fun pauseScanning() {
        setFlashOn(false)
        // Because the Camera object is a shared resource, it's very
        // important to release it when the activity is paused.
        if (mCamera != null) {
            try {
                mCamera!!.stopPreview()
                mCamera!!.setPreviewDisplay(null)
            } catch (e: IOException) {
                Log.w(TAG, "can't stop preview display", e)
            }
            mCamera!!.setPreviewCallback(null)
            mCamera!!.release()
            mPreviewBuffer = null
            mCamera = null
        }
    }

    fun endScanning() {
        if (mCamera != null) {
            pauseScanning()
        }
        mPreviewBuffer = null
    }

    /*
     * --------------------------- SurfaceHolder callbacks
     */
    private fun makePreviewGo(holder: SurfaceHolder?): Boolean {
        // method name from http://www.youtube.com/watch?v=-WmGvYDLsj4
        assert(holder != null)
        assert(holder!!.surface != null)
        mFirstPreviewFrame = true
        if (useCamera) {
            try {
                mCamera!!.setPreviewDisplay(holder)
            } catch (e: IOException) {
                Log.e(TAG, "can't set preview display", e)
                return false
            }
            try {
                mCamera!!.startPreview()
                mCamera!!.autoFocus(this)
            } catch (e: RuntimeException) {
                Log.e(TAG, "startPreview failed on camera. Error: ", e)
                return false
            }
        }
        return true
    }

    /*
     * @see android.view.SurfaceHolder.Callback#surfaceCreated(android.view.SurfaceHolder )
     */
    override fun surfaceCreated(holder: SurfaceHolder) {
        // The Surface has been created, acquire the camera and tell it where to draw.
        if (mCamera != null || !useCamera) {
            isSurfaceValid = true
            makePreviewGo(holder)
        } else {
            Log.wtf(TAG, "CardScanner.surfaceCreated() - camera is null!")
            return
        }
    }

    /*
     * @see android.view.SurfaceHolder.Callback#surfaceChanged(android.view.SurfaceHolder , int,
     * int, int)
     */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(
            TAG, String.format(
                "Preview.surfaceChanged(holder?:%b, f:%d, w:%d, h:%d )",
                holder != null, format, width, height
            )
        )
    }

    /*
     * @see android.view.SurfaceHolder.Callback#surfaceDestroyed(android.view. SurfaceHolder)
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (mCamera != null) {
            try {
                mCamera!!.stopPreview()
            } catch (e: Exception) {
                Log.e(TAG, "error stopping camera", e)
            }
        }
        isSurfaceValid = false
    }
    // ------------------------------------------------------------------------
    // STATIC INITIALIZATION
    // ------------------------------------------------------------------------
    /**
     * Custom loadLibrary method that first tries to load the libraries from the built-in libs
     * directory and if it fails, tries to use the alternative libs path if one is set.
     *
     * No checks are performed to ensure that the native libraries match the cardIO library version.
     * This needs to be handled by the consuming application.
     */
    init {
        mScanActivityRef = WeakReference(scanActivity)
    }

    override fun onPreviewFrame(data: ByteArray, camera: Camera) {
        if (data == null) {
            Log.w(TAG, "frame is null! skipping")
            return
        }
        if (processingInProgress) {
            Log.e(TAG, "processing in progress.... dropping frame")
            // return frame buffer to pool
            if (camera != null) {
                //camera.addCallbackBuffer(data);
            }
            return
        }
        processingInProgress = true

        // TODO: eliminate this foolishness and measure/layout properly.
        if (mFirstPreviewFrame) {
            mFirstPreviewFrame = false
        }
        val parameters = camera.parameters
        val width = parameters.previewSize.width
        val height = parameters.previewSize.height
        val yuv = YuvImage(data, parameters.previewFormat, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 50, out)
        val bytes = out.toByteArray()
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        bitmap = Bitmap.createScaledBitmap(bitmap!!, 400, 300, false)
        Log.d("time", SystemClock.uptimeMillis().toString() + "")
        val time1 = SystemClock.currentThreadTimeMillis()
        CameraActivity.setBitMapImage(bitmap)
        Log.d("time", (SystemClock.currentThreadTimeMillis() - time1).toString() + "")
        if (camera != null) {
            camera.addCallbackBuffer(data)
        }
        processingInProgress = false
    }
    // ------------------------------------------------------------------------
    // CAMERA CONTROL & CALLBACKS
    // ------------------------------------------------------------------------
    /**
     * Invoked when autoFocus is complete
     *
     *
     * This method is called by Android, never directly by application code.
     */
    override fun onAutoFocus(success: Boolean, camera: Camera) {
        mAutoFocusCompletedAt = System.currentTimeMillis()
    }

    /**
     * True if autoFocus is in progress
     */
    val isAutoFocusing: Boolean
        get() = mAutoFocusCompletedAt < mAutoFocusStartedAt

    fun toggleFlash() {
        setFlashOn(!isFlashOn)
    }
    // ------------------------------------------------------------------------
    // MISC CAMERA CONTROL
    // ------------------------------------------------------------------------
    /**
     * Tell Preview's camera to trigger autofocus.
     *
     * @param isManual callback for when autofocus is complete
     */
    fun triggerAutoFocus(isManual: Boolean) {
        if (useCamera && !isAutoFocusing) {
            try {
                mAutoFocusStartedAt = System.currentTimeMillis()
                mCamera!!.autoFocus(this)
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not trigger auto focus: $e")
            }
        }
    }

    /**
     * Check if the flash is on.
     *
     * @return state of the flash.
     */
    val isFlashOn: Boolean
        get() {
            if (!useCamera) {
                return false
            }
            val params = mCamera!!.parameters
            return params.flashMode == Camera.Parameters.FLASH_MODE_TORCH
        }

    /**
     * Set the flash on or off
     *
     * @param b desired flash state
     * @return `true` if successful
     */
    fun setFlashOn(b: Boolean): Boolean {
        if (mCamera != null) {
            try {
                val params = mCamera!!.parameters
                params.flashMode =
                    if (b) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
                mCamera!!.parameters = params
                return true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not set flash mode: $e")
            }
        }
        return false
    }

    fun resumeScanning(holder: SurfaceHolder?): Boolean {
        if (mCamera == null) {
            prepareScanner()
        }
        if (useCamera && mCamera == null) {
            // prepare failed!
            Log.i(TAG, "null camera. failure")
            return false
        }
        assert(holder != null)
        if (useCamera && mPreviewBuffer == null) {
            val parameters = mCamera!!.parameters
            val previewFormat = parameters.previewFormat
            val bytesPerPixel = ImageFormat.getBitsPerPixel(previewFormat) / 8
            val bufferSize = mPreviewWidth * mPreviewHeight * bytesPerPixel * 3
            mPreviewBuffer = ByteArray(bufferSize)
            mCamera!!.addCallbackBuffer(mPreviewBuffer)
        }
        holder!!.addCallback(this)
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)
        if (useCamera) {
            mCamera!!.setPreviewCallbackWithBuffer(this)
        }
        if (isSurfaceValid) {
            makePreviewGo(holder)
        }

        // Turn flash off
        setFlashOn(false)
        return true
    }

    private fun setCameraDisplayOrientation(mCamera: Camera) {
        val result: Int

        /* check API level. If upper API level 21, re-calculate orientation. */result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val info = CameraInfo()
                Camera.getCameraInfo(0, info)
                val degrees = rotationalOffset
                val cameraOrientation = info.orientation
                (cameraOrientation - degrees + 360) % 360
            } else {
                /* if API level is lower than 21, use the default value */
                90
            }

        /*set display orientation*/mCamera.setDisplayOrientation(result)
    }// just hope for the best (shouldn't happen)// Check "normal" screen orientation and adjust accordingly

    /**
     * @see [SO
     * post](http://stackoverflow.com/questions/12216148/android-screen-orientation-differs-between-devices)
     */
    val rotationalOffset: Int
        get() {
            val rotationOffset: Int
            // Check "normal" screen orientation and adjust accordingly
            val naturalOrientation =
                (mScanActivityRef.get()!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.rotation
            rotationOffset = if (naturalOrientation == Surface.ROTATION_0) {
                0
            } else if (naturalOrientation == Surface.ROTATION_90) {
                90
            } else if (naturalOrientation == Surface.ROTATION_180) {
                180
            } else if (naturalOrientation == Surface.ROTATION_270) {
                270
            } else {
                // just hope for the best (shouldn't happen)
                0
            }
            return rotationOffset
        }

    companion object {
        private val TAG = FaceScanner::class.java.simpleName
        private const val MIN_FOCUS_SCORE = 6f // TODO - parameterize this

        // value based on phone? or
        // change focus behavior?
        private const val DEFAULT_UNBLUR_DIGITS = -1 // no blur per default
        private const val CAMERA_CONNECT_TIMEOUT = 5000
        private const val CAMERA_CONNECT_RETRY_INTERVAL = 50
        const val ORIENTATION_PORTRAIT = 1

        // these values MUST match those in dmz_constants.h
        const val CREDIT_CARD_TARGET_WIDTH = 428 // kCreditCardTargetWidth
        const val CREDIT_CARD_TARGET_HEIGHT = 270 // kCreditCardTargetHeight
        private const val manualFallbackForError = false

        /**
         * Handles processing of each frame.
         *
         *
         * This method is called by Android, never directly by application code.
         */
        private var processingInProgress = false
    }
}