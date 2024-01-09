package com.mobil80.blinkdetection.present

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.*
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.*

class CameraActivity : Activity() {
    private var mOverlay: OverlayView? = null
    private var orientationListener: OrientationEventListener? = null

    var mPreview: Preview? = null
    private var mGuideFrame: Rect? = null
    private val mLastDegrees = 0
    private var mFrameOrientation = 0
    private var suppressManualEntry = false
    private var mDetectOnly = false
    private var customOverlayLayout: LinearLayout? = null
    private var waitingForPermission = false
    private var mUIBar: RelativeLayout? = null
    private var mMainLayout: RelativeLayout? = null
    private var useApplicationTheme = false
    private var mCardScanner: FaceScanner? = null
    private var manualEntryFallbackOrForced = false

    internal inner class MyGLSurfaceView(context: Context?) : GLSurfaceView(context) {
        private val mRenderer: GameGLRenderer

        init {

            // Create an OpenGL ES 2.0 context
            setEGLContextClientVersion(2)
            mRenderer = GameGLRenderer()
            super.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            // Set the Renderer for drawing on the GLSurfaceView
            setRenderer(mRenderer)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thisActivity = this
        numActivityAllocations++
        // NOTE: java native asserts are disabled by default on Android.
        if (numActivityAllocations != 1) {
            Log.i(
                TAG, String.format(
                    "INTERNAL WARNING: There are %d (not 1) CardIOActivity allocations!",
                    numActivityAllocations
                )
            )
        }
        val clientData = this.intent
        useApplicationTheme = intent.getBooleanExtra(EXTRA_KEEP_APPLICATION_THEME, false)
        // Validate app's manifest is correct.
        mDetectOnly = clientData.getBooleanExtra(EXTRA_SUPPRESS_SCAN, false)
        val errorMsg: String?

        // Check for CardIOActivity's orientation config in manifest
        val resolveInfo: ResolveInfo? = packageManager.resolveActivity(
            clientData,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        errorMsg = BlinkUtilFuns.manifestHasConfigChange(resolveInfo, CameraActivity::class.java)
        if (errorMsg != null) {
            throw RuntimeException(errorMsg ?: "Default error message")
        }

        suppressManualEntry = clientData.getBooleanExtra(EXTRA_SUPPRESS_MANUAL_ENTRY, false)
        if (savedInstanceState != null) {
            waitingForPermission = savedInstanceState.getBoolean(BUNDLE_WAITING_FOR_PERMISSION)
        }
        if (clientData.getBooleanExtra(EXTRA_NO_CAMERA, false)) {
            Log.i(BlinkUtilFuns.PUBLIC_LOG_TAG, "EXTRA_NO_CAMERA set to true. Skipping camera.")
            manualEntryFallbackOrForced = true
        } else {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!waitingForPermission) {
                        if (checkSelfPermission(Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_DENIED
                        ) {
                            Log.d(TAG, "permission denied to camera - requesting it")
                            val permissions = arrayOf(Manifest.permission.CAMERA)
                            waitingForPermission = true
                            requestPermissions(permissions, PERMISSION_REQUEST_ID)
                        } else {
                            checkCamera()
                            android23AndAboveHandleCamera()
                        }
                    }
                } else {
                    checkCamera()
                    android22AndBelowHandleCamera()
                }
            } catch (e: Exception) {
                handleGeneralExceptionError(e)
            }
        }
    }

    private fun android23AndAboveHandleCamera() {
        if (manualEntryFallbackOrForced) {
            finishIfSuppressManualEntry()
        } else {
            // Guaranteed to be called in API 23+
            showCameraScannerOverlay()
        }
    }

    private fun android22AndBelowHandleCamera() {
        if (manualEntryFallbackOrForced) {
            finishIfSuppressManualEntry()
        } else {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            showCameraScannerOverlay()
        }
    }

    private fun finishIfSuppressManualEntry() {
        if (suppressManualEntry) {
            Log.i(BlinkUtilFuns.PUBLIC_LOG_TAG, "Camera not available and manual entry suppressed.")
            setResultAndFinish(RESULT_SCAN_NOT_AVAILABLE, null)
        }
    }

    private fun checkCamera() {
        try {
            if (!BlinkUtilFuns.hardwareSupported()) {
                manualEntryFallbackOrForced = true
            }
        } catch (e: CameraUnavailableException) {
            val toast = Toast.makeText(this, "camera is unavailable", Toast.LENGTH_LONG)
            toast.setGravity(Gravity.CENTER, 0, TOAST_OFFSET_Y)
            toast.show()
            manualEntryFallbackOrForced = true
        }
    }

    private fun showCameraScannerOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            val decorView = window.decorView
            val uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN

            decorView.systemUiVisibility = uiOptions
            val actionBar = actionBar
            actionBar?.hide()
        }
        try {
            mGuideFrame = Rect()
            mFrameOrientation = ORIENTATION_PORTRAIT
            mCardScanner =
                if (intent.getBooleanExtra(PRIVATE_EXTRA_CAMERA_BYPASS_TEST_MODE, false)) {
                    check(this.packageName.contentEquals("io.card.development")) { "Illegal access of private extra" }
                    // use reflection here so that the tester can be safely stripped for release
                    // builds.
                    val testScannerClass = Class.forName("io.card.payment.CardScannerTester")
                    val cons = testScannerClass.getConstructor(
                        this.javaClass,
                        Integer.TYPE
                    )
                    cons.newInstance(
                        *arrayOf(
                            this,
                            mFrameOrientation
                        )
                    ) as FaceScanner
                } else {
                    FaceScanner(this, mFrameOrientation)
                }
            mCardScanner!!.prepareScanner()
            setPreviewLayout()
            orientationListener = object : OrientationEventListener(
                this,
                SensorManager.SENSOR_DELAY_UI
            ) {
                override fun onOrientationChanged(orientation: Int) {
                    doOrientationChange(orientation)
                }
            }
        } catch (e: Exception) {
            handleGeneralExceptionError(e)
        }
    }

    private fun handleGeneralExceptionError(e: Exception) {
        Log.e(
            BlinkUtilFuns.PUBLIC_LOG_TAG,
            "Unknown exception, please post the stack trace as a GitHub issue",
            e
        )
        val toast = Toast.makeText(this, "exception", Toast.LENGTH_LONG)
        toast.setGravity(Gravity.CENTER, 0, TOAST_OFFSET_Y)
        toast.show()
        manualEntryFallbackOrForced = true
    }

    private fun doOrientationChange(orientation: Int) {
        var orientation = orientation
        if (orientation < 0 || mCardScanner == null) {
            return
        }
        orientation += mCardScanner!!.rotationalOffset

        // Check if we have gone too far forward with
        // rotation adjustment, keep the result between 0-360
        if (orientation > 360) {
            orientation -= 360
        }
        var degrees: Int
        degrees = -1
        if (orientation < DEGREE_DELTA || orientation > 360 - DEGREE_DELTA) {
            degrees = 0
            mFrameOrientation = ORIENTATION_PORTRAIT
        } else if (orientation > 90 - DEGREE_DELTA && orientation < 90 + DEGREE_DELTA) {
            degrees = 90
            mFrameOrientation = ORIENTATION_LANDSCAPE_LEFT
        } else if (orientation > 180 - DEGREE_DELTA && orientation < 180 + DEGREE_DELTA) {
            degrees = 180
            mFrameOrientation = ORIENTATION_PORTRAIT_UPSIDE_DOWN
        } else if (orientation > 270 - DEGREE_DELTA && orientation < 270 + DEGREE_DELTA) {
            degrees = 270
            mFrameOrientation = ORIENTATION_LANDSCAPE_RIGHT
        }
        if (degrees >= 0 && degrees != mLastDegrees) {
            if (degrees == 90) {
                rotateCustomOverlay(270f)
            } else if (degrees == 270) {
                rotateCustomOverlay(90f)
            } else {
                rotateCustomOverlay(degrees.toFloat())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!waitingForPermission) {
            if (manualEntryFallbackOrForced) {
                if (suppressManualEntry) {
                    finishIfSuppressManualEntry()
                    return
                } else {
                    return
                }
            }
            BlinkUtilFuns.logNativeMemoryStats()
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            orientationListener!!.enable()
            if (!restartPreview()) {
                Log.e(TAG, "Could not connect to camera.")
            } else {
                // Turn flash off
                setFlashOn(false)
            }
            doOrientationChange(mLastDegrees)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(BUNDLE_WAITING_FOR_PERMISSION, waitingForPermission)
    }

    override fun onPause() {
        super.onPause()
        if (orientationListener != null) {
            orientationListener!!.disable()
        }
        setFlashOn(false)
        if (mCardScanner != null) {
            mCardScanner!!.pauseScanning()
        }
    }

    override fun onDestroy() {
        mOverlay = null
        numActivityAllocations--
        if (orientationListener != null) {
            orientationListener!!.disable()
        }
        setFlashOn(false)
        if (mCardScanner != null) {
            mCardScanner!!.endScanning()
            mCardScanner = null
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_ID) {
            waitingForPermission = false
            if (grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showCameraScannerOverlay()
            } else {
                // show manual entry - handled in onResume()
                manualEntryFallbackOrForced = true
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            DATA_ENTRY_REQUEST_ID -> if (resultCode == RESULT_CANCELED) {
                Log.d(
                    TAG,
                    "ignoring onActivityResult(RESULT_CANCELED) caused only when Camera Permissions are Denied in Android 23"
                )
            } else if (resultCode == RESULT_CARD_INFO || resultCode == RESULT_ENTRY_CANCELED || manualEntryFallbackOrForced) {
                if (data != null && data.hasExtra(EXTRA_SCAN_RESULT)) {
                    Log.v(TAG, "EXTRA_SCAN_RESULT: " + data.getParcelableExtra(EXTRA_SCAN_RESULT))
                } else {
                    Log.d(TAG, "no data in EXTRA_SCAN_RESULT")
                }
                setResultAndFinish(resultCode, data)
            } else {
                if (mUIBar != null) {
                    mUIBar!!.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onBackPressed() {
        if (!manualEntryFallbackOrForced && mOverlay!!.isAnimating) {
            try {
                restartPreview()
            } catch (re: RuntimeException) {
                Log.w(TAG, "*** could not return to preview: $re")
            }
        } else if (mCardScanner != null) {
            super.onBackPressed()
        }
    }

    fun onFirstFrame(orientation: Int) {
        val sv = mPreview!!.surfaceView
        if (mOverlay != null) {
            //mOverlay.setCameraPreviewRect(new Rect(sv.getLeft(), sv.getTop(), sv.getRight(), sv.getBottom()));
        }
        mFrameOrientation = ORIENTATION_PORTRAIT
        if (orientation != mFrameOrientation) {
            Log.wtf(
                BlinkUtilFuns.PUBLIC_LOG_TAG,
                "the orientation of the scanner doesn't match the orientation of the activity"
            )
        }
    }

    private fun restartPreview(): Boolean {
        if (mPreview == null || mCardScanner == null) {
            // Handle null cases gracefully or log an error
            return false
        }

        val success = mCardScanner!!.resumeScanning(mPreview!!.surfaceHolder)
        if (success) {
            mUIBar?.visibility = View.VISIBLE // Using safe call ?. to handle mUIBar being null
        }
        return success
    }


    // Called by OverlayView
    fun toggleFlash() {
        setFlashOn(!mCardScanner!!.isFlashOn)
    }

    fun setFlashOn(b: Boolean) {
        val success = mPreview != null && mOverlay != null && mCardScanner!!.setFlashOn(b)
        if (success) {
        }
    }

    fun triggerAutoFocus() {
        mCardScanner!!.triggerAutoFocus(true)
    }

    private fun setPreviewLayout() {
        var horizontalMargin = 25
        // top level container
        mMainLayout = RelativeLayout(this)
        mMainLayout!!.setBackgroundColor(Color.WHITE)
        mMainLayout!!.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val previewFrame = FrameLayout(this)
        previewFrame.id = FRAME_ID
        mPreview = Preview(this, null, mCardScanner!!.mPreviewWidth, mCardScanner!!.mPreviewHeight)
        mPreview!!.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP
        )

        previewFrame.addView(mPreview)
        mOverlay = OverlayView(this, null, BlinkUtilFuns.deviceSupportsTorch(this))
        mOverlay!!.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        if (intent != null) {
            val color = intent.getIntExtra(EXTRA_GUIDE_COLOR, 0)
            if (color != 0) {
                val alphaRemovedColor = color or - 0x1000000
                if (color != alphaRemovedColor) {
                    Log.w(BlinkUtilFuns.PUBLIC_LOG_TAG, "Removing transparency from provided guide color.")
                }
                mOverlay!!.guideColor = alphaRemovedColor
            } else {
                mOverlay!!.guideColor = Color.GREEN
            }
            val hideCardIOLogo = intent.getBooleanExtra(EXTRA_HIDE_CARDIO_LOGO, false)
            mOverlay!!.hideCardIOLogo = hideCardIOLogo
            val scanInstructions = intent.getStringExtra(EXTRA_SCAN_INSTRUCTIONS)
            if (scanInstructions != null) {
                mOverlay!!.scanInstructions = scanInstructions
            }
        }
        previewFrame.addView(mOverlay)
        val previewParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        previewParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
        previewParams.addRule(RelativeLayout.ABOVE, UIBAR_ID)
        mMainLayout!!.addView(previewFrame, previewParams)
        val rel = RelativeLayout(this)
        modifiedImage = FaceOverlayView(this)
        textView = TextView(this)
        textView!!.text = "0"
        textView!!.textSize = 60f
        textView!!.setTypeface(Typeface.DEFAULT_BOLD)
        textView!!.setTextColor(Color.BLACK)
        //rel.addView(modifiedImage);
        rel.addView(textView)
        mMainLayout!!.addView(rel)
        mUIBar = RelativeLayout(this)
        mUIBar!!.gravity = Gravity.BOTTOM
        val mUIBarParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        previewParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        mUIBar!!.layoutParams = mUIBarParams
        mUIBar!!.id = UIBAR_ID
        mUIBar!!.gravity = Gravity.BOTTOM or Gravity.RIGHT

        // Show the keyboard button
        // Device has a flash, show the flash button
        val uiParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

        uiParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        val scale = resources.displayMetrics.density
        val uiBarMarginPx = (UIBAR_VERTICAL_MARGIN_DP * scale + 0.5f).toInt()
        uiParams.setMargins(0, uiBarMarginPx, 0, uiBarMarginPx)
        mMainLayout!!.addView(mUIBar, uiParams)

        if (intent != null) {
            if (customOverlayLayout != null) {
                mMainLayout!!.removeView(customOverlayLayout)
                customOverlayLayout = null
            }
            val resourceId = intent.getIntExtra(EXTRA_SCAN_OVERLAY_LAYOUT_ID, -1)
            if (resourceId != -1) {
                customOverlayLayout = LinearLayout(this)
                customOverlayLayout!!.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                val inflater = this.layoutInflater
                inflater.inflate(resourceId, customOverlayLayout)
                mMainLayout!!.addView(customOverlayLayout)
            }
        }
        this.setContentView(mMainLayout)
    }

    private fun rotateCustomOverlay(degrees: Float) {
        if (customOverlayLayout != null) {
            val pivotX = (customOverlayLayout!!.width / 2).toFloat()
            val pivotY = (customOverlayLayout!!.height / 2).toFloat()
            val an: Animation = RotateAnimation(0f, degrees, pivotX, pivotY)
            an.duration = 0
            an.repeatCount = 0
            an.fillAfter = true
            customOverlayLayout!!.animation = an
        }
    }

    private fun setResultAndFinish(resultCode: Int, data: Intent?) {
        setResult(resultCode, data)
        markedCardImage = null
        finish()
    }

    companion object {
        const val EXTRA_NO_CAMERA = "io.card.payment.noCamera"

        const val EXTRA_REQUIRE_EXPIRY = "io.card.payment.requireExpiry"

        const val EXTRA_SCAN_EXPIRY = "io.card.payment.scanExpiry"

        const val EXTRA_UNBLUR_DIGITS = "io.card.payment.unblurDigits"

        const val EXTRA_REQUIRE_CVV = "io.card.payment.requireCVV"

        const val EXTRA_REQUIRE_POSTAL_CODE = "io.card.payment.requirePostalCode"

        const val EXTRA_RESTRICT_POSTAL_CODE_TO_NUMERIC_ONLY =
            "io.card.payment.restrictPostalCodeToNumericOnly"

        const val EXTRA_REQUIRE_CARDHOLDER_NAME = "io.card.payment.requireCardholderName"

        const val EXTRA_USE_CARDIO_LOGO = "io.card.payment.useCardIOLogo"

        const val EXTRA_SCAN_RESULT = "io.card.payment.scanResult"

        private const val EXTRA_MANUAL_ENTRY_RESULT = "io.card.payment.manualEntryScanResult"

        const val EXTRA_SUPPRESS_MANUAL_ENTRY = "io.card.payment.suppressManual"

        const val EXTRA_LANGUAGE_OR_LOCALE = "io.card.payment.languageOrLocale"

        const val EXTRA_GUIDE_COLOR = "io.card.payment.guideColor"

        const val EXTRA_SUPPRESS_CONFIRMATION = "io.card.payment.suppressConfirmation"

        const val EXTRA_HIDE_CARDIO_LOGO = "io.card.payment.hideLogo"

        const val EXTRA_SCAN_INSTRUCTIONS = "io.card.payment.scanInstructions"

        const val EXTRA_SUPPRESS_SCAN = "io.card.payment.suppressScan"

        const val EXTRA_CAPTURED_CARD_IMAGE = "io.card.payment.capturedCardImage"

        const val EXTRA_RETURN_CARD_IMAGE = "io.card.payment.returnCardImage"

        const val EXTRA_SCAN_OVERLAY_LAYOUT_ID = "io.card.payment.scanOverlayLayoutId"

        const val EXTRA_USE_PAYPAL_ACTIONBAR_ICON = "io.card.payment.intentSenderIsPayPal"

        const val EXTRA_KEEP_APPLICATION_THEME = "io.card.payment.keepApplicationTheme"

        const val PRIVATE_EXTRA_CAMERA_BYPASS_TEST_MODE = "io.card.payment.cameraBypassTestMode"

        private var lastResult = 0xca8d10 // arbitrary. chosen to be well above

        val RESULT_CARD_INFO = lastResult++

        val RESULT_ENTRY_CANCELED = lastResult++

        val RESULT_SCAN_NOT_AVAILABLE = lastResult++

        val RESULT_SCAN_SUPPRESSED = lastResult++

        val RESULT_CONFIRMATION_SUPPRESSED = lastResult++
        private val TAG = CameraActivity::class.java.simpleName
        private const val DEGREE_DELTA = 15
        private const val ORIENTATION_PORTRAIT = 1
        private const val ORIENTATION_PORTRAIT_UPSIDE_DOWN = 2
        private const val ORIENTATION_LANDSCAPE_RIGHT = 3
        private const val ORIENTATION_LANDSCAPE_LEFT = 4
        private const val FRAME_ID = 1
        private const val UIBAR_ID = 2
        private const val BUNDLE_WAITING_FOR_PERMISSION = "io.card.payment.waitingForPermission"
        private const val UIBAR_VERTICAL_MARGIN_DP = 15.0f
        private val VIBRATE_PATTERN = longArrayOf(0, 70, 10, 40)
        private const val TOAST_OFFSET_Y = -75
        private const val DATA_ENTRY_REQUEST_ID = 10
        private const val PERMISSION_REQUEST_ID = 11
        private var textView: TextView? = null
        private var numActivityAllocations = 0

        var markedCardImage: Bitmap? = null

        var modifiedImage: FaceOverlayView? = null

        var thisActivity: Activity? = null
        fun showScore(score: Int) {
            textView!!.text = score.toString() + ""
        }

        fun setBitMapImage(bitmap: Bitmap) {
            //Log.d("time",SystemClock.uptimeMillis()+"");
            var bitmap = bitmap
            bitmap = RotateBitmap(bitmap, -90f)
            modifiedImage!!.setBitmap(bitmap)
        }

        fun RotateBitmap(source: Bitmap, angle: Float): Bitmap {
            val matrix = Matrix()
            matrix.postRotate(angle)
            return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }
    }
}