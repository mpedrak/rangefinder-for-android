package com.example.rangefinder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity()
{

    companion object
    {
        private const val TAG = "Rangefinder"
        private const val REQUEST_CAMERA_PERMISSION = 1001
    }

    private lateinit var flashButton: Button
    private var isFlashOn = false
    private lateinit var cameraView: AutoFitTextureView
    private lateinit var distanceTextView: TextView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private lateinit var cameraManager: CameraManager


    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportActionBar?.hide()
        hideStatusBar()

        cameraView = findViewById(R.id.cameraView)
        distanceTextView = findViewById(R.id.distanceTextView)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        cameraView.surfaceTextureListener = surfaceTextureListener
        flashButton = findViewById(R.id.flashButton)
        flashButton.setOnClickListener { toggleFlash() }
    }

    private fun hideStatusBar()
    {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onResume()
    {
        super.onResume()
        hideStatusBar()
        startBackgroundThread()
        if (cameraView.isAvailable)
        {
            openCameraIfPermitted(cameraView.width, cameraView.height)
            cameraView.setOnClickListener { triggerCenterAutoFocus() }
        }
    }


    override fun onWindowFocusChanged(hasFocus: Boolean)
    {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun toggleFlash()
    {
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull{
                cameraManager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return

            isFlashOn = !isFlashOn

            previewRequestBuilder?.set(
                CaptureRequest.FLASH_MODE,
                if (isFlashOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )

            captureSession?.setRepeatingRequest(
                previewRequestBuilder!!.build(),
                captureCallback,
                backgroundHandler
            )

            flashButton.text = if (isFlashOn) "FLASHLIGHT ON" else "FLASHLIGHT OFF"
        }
        catch (e: CameraAccessException)
        {
            Log.e(TAG, "toggleFlash error: ${e.message}")
        }
    }


    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener
    {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int)
        {
            configureTransform(width, height)
            openCameraIfPermitted(width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int)
        {
            configureTransform(width, height)
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    override fun onPause()
    {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }
    private fun startBackgroundThread()
    {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread()
    {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun openCameraIfPermitted(width: Int, height: Int)
    {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
            return
        }
        openCamera(width, height)
    }
    private fun configureTransform(viewWidth: Int, viewHeight: Int)
    {
        val rotation = windowManager.defaultDisplay.rotation
        val matrix = android.graphics.Matrix()

        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f


        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
        {
            val scale = maxOf(
                viewHeight.toFloat() / viewWidth.toFloat(),
                viewWidth.toFloat() / viewHeight.toFloat()
            )

            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        }
        else if (rotation == Surface.ROTATION_180)
        {
            matrix.postRotate(180f, centerX, centerY)
        }

        cameraView.setTransform(matrix)
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera(cameraView.width, cameraView.height)
            } else {
                distanceTextView.text = "No camera persmission granted"
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun openCamera(width: Int, height: Int) {
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.first()

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfig = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = streamConfig?.getOutputSizes(SurfaceTexture::class.java)?.find {
                it.width.toFloat() / it.height == 16f / 9f
            } ?: Size(1920, 1080)

//            textureView.setAspectRatio(previewSize.width, previewSize.height)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                return

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception: ${e.message}")
            distanceTextView.text = "Camera error"
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            createCameraPreviewSession()
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            cameraDevice = null
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            cameraDevice = null
            distanceTextView.text = "Camera error"
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = cameraView.surfaceTexture ?: return
            texture.setDefaultBufferSize(cameraView.width, cameraView.height)
            val surface = Surface(texture)

            previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder?.addTarget(surface)

            previewRequestBuilder?.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorArraySize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!

            val regionWidth = sensorArraySize.width() / 5
            val regionHeight = sensorArraySize.height() / 5
            val centerX = sensorArraySize.width() / 2
            val centerY = sensorArraySize.height() / 2

            val afRegion = MeteringRectangle(
                centerX - regionWidth / 2,
                centerY - regionHeight / 2,
                regionWidth,
                regionHeight,
                MeteringRectangle.METERING_WEIGHT_MAX
            )

            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(afRegion))
            previewRequestBuilder?.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            cameraDevice?.createCaptureSession(listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val previewRequest = previewRequestBuilder!!.build()
                        session.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        distanceTextView.text = "Couldn't configure camera view"
                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createPreviewSession err: ${e.message}")
        }
    }

    private fun triggerCenterAutoFocus() {
        try {
            val session = captureSession ?: return
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
            builder.addTarget(Surface(cameraView.surfaceTexture))

            val cameraId = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!

            val regionWidth = sensorArray.width() / 5
            val regionHeight = sensorArray.height() / 5
            val centerX = sensorArray.width() / 2
            val centerY = sensorArray.height() / 2

            val afRegion = MeteringRectangle(
                centerX - regionWidth / 2,
                centerY - regionHeight / 2,
                regionWidth,
                regionHeight,
                MeteringRectangle.METERING_WEIGHT_MAX
            )

            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(afRegion))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)

                    previewRequestBuilder?.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    session.setRepeatingRequest(previewRequestBuilder!!.build(), captureCallback, backgroundHandler)
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "triggerCenterAutoFocus error: ${e.message}")
        }
    }


    private val captureCallback = object : CameraCaptureSession.CaptureCallback()
    {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult)
        {
            super.onCaptureCompleted(session, request, result)
            val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            runOnUiThread{
                if (focusDistance != null && focusDistance > 0)
                {
                    val distance = 1f / focusDistance
                    distanceTextView.text = String.format("DISTANCE: %.2f m", distance)
                }
                else
                {
                    distanceTextView.text = "DISTANCE: ∞"
                }
            }
        }
    }

    private fun closeCamera()
    {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }
}