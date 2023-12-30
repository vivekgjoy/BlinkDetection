package com.mobil80.blinkdetection.present

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.*
import android.hardware.Camera
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Created by muralikrishna on 02/11/17.
 */
/**
 * This class has various static utility methods.
 */
internal object Util {
    private val TAG = Util::class.java.simpleName
    private val TORCH_BLACK_LISTED = Build.MODEL == "DROID2"
    const val PUBLIC_LOG_TAG = "card.io"
    private var sHardwareSupported: Boolean? = null
    fun deviceSupportsTorch(context: Context): Boolean {
        return (!TORCH_BLACK_LISTED
                && context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
    }

    fun manifestHasConfigChange(resolveInfo: ResolveInfo?, activityClass: Class<*>): String? {
        var error: String? = null
        if (resolveInfo == null) {
            error = String.format(
                "Didn't find %s in the AndroidManifest.xml",
                activityClass.name
            )
        } else if (!hasConfigFlag(
                resolveInfo.activityInfo.configChanges,
                ActivityInfo.CONFIG_ORIENTATION
            )
        ) {
            error = (activityClass.name
                    + " requires attribute android:configChanges=\"orientation\"")
        }
        if (error != null) {
            Log.e(PUBLIC_LOG_TAG, error)
        }
        return error
    }

    fun hasConfigFlag(config: Int, configFlag: Int): Boolean {
        return config and configFlag == configFlag
    }

    /* --- HARDWARE SUPPORT --- */
    fun hardwareSupported(): Boolean {
        if (sHardwareSupported == null) {
            sHardwareSupported = hardwareSupportCheck()
        }
        return sHardwareSupported!!
    }

    private fun hardwareSupportCheck(): Boolean {
        // Camera needs to open
        var c: Camera? = null
        try {
            c = Camera.open()
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return true
            } else {
                Log.w(PUBLIC_LOG_TAG, "- Error opening camera: $e")
            }
        }
        if (c == null) {
            Log.w(PUBLIC_LOG_TAG, "- No camera found")
            return false
        } else {
            val list = c.parameters.supportedPreviewSizes
            c.release()
            var supportsVGA = false
            for (s in list) {
                if (s.width == 640 && s.height == 480) {
                    supportsVGA = true
                    break
                }
            }
            if (!supportsVGA) {
                Log.w(PUBLIC_LOG_TAG, "- Camera resolution is insufficient")
                return false
            }
        }
        return true
    }

    val nativeMemoryStats: String
        get() = ("(free/alloc'd/total)" + Debug.getNativeHeapFreeSize() + "/"
                + Debug.getNativeHeapAllocatedSize() + "/" + Debug.getNativeHeapSize())

    fun logNativeMemoryStats() {
        Log.d("MEMORY", "Native memory stats: " + nativeMemoryStats)
    }

    fun rectGivenCenter(center: Point, width: Int, height: Int): Rect {
        return Rect(
            center.x - width / 2, center.y - height / 2, center.x + width / 2, center.y
                    + height / 2
        )
    }

    fun setupTextPaintStyle(paint: Paint) {
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.isAntiAlias = true
        val black = floatArrayOf(0f, 0f, 0f)
        paint.setShadowLayer(1.5f, 0.5f, 0f, Color.HSVToColor(200, black))
    }

    fun writeCapturedCardImageIfNecessary(
        origIntent: Intent, dataIntent: Intent, mOverlay: OverlayView?
    ) {
        if (origIntent.getBooleanExtra(
                CameraActivity.EXTRA_RETURN_CARD_IMAGE,
                false
            ) && mOverlay != null && mOverlay.bitmap != null
        ) {
            val scaledCardBytes = ByteArrayOutputStream()
            mOverlay.bitmap!!.compress(Bitmap.CompressFormat.JPEG, 80, scaledCardBytes)
            dataIntent.putExtra(
                CameraActivity.EXTRA_CAPTURED_CARD_IMAGE,
                scaledCardBytes.toByteArray()
            )
        }
    }
}