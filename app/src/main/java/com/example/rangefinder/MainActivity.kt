package com.example.rangefinder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt
import android.widget.LinearLayout
import android.graphics.Rect
import android.content.Intent
import android.media.ImageReader
import android.util.Log
import android.util.Size
import android.widget.Toast
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity()
{

    companion object
    {
        private const val TAG = "Rangefinder"
        private const val REQUEST_CAMERA_PERMISSION = 1001
    }

    private lateinit var flashButton: Button
    private lateinit var saveButton: Button
    private lateinit var viewSavedButton: Button
    private var isFlashOn = false
    private lateinit var cameraView: AutoFitTextureView
    private lateinit var distanceTextView: TextView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private lateinit var cameraManager: CameraManager
    private lateinit var focusRectangle: View
    private lateinit var cameraButtonsContainer: LinearLayout
    private lateinit var zoomButtonsContainer: LinearLayout
    private var mainBackCameraId: String? = null // Can be logical with other lens attached
    private var ultraWideId: String? = null
    private var currentCameraId: String? = null
    private var activeCameraId: String? = null
    private var sensorActiveArray: Rect? = null

    private enum class CameraMode { WIDE, UW, ZOOM }

    private var currentMode: CameraMode = CameraMode.WIDE
    private var logicalBackPhysicalCount: Int = 1
    private var currentDistance: Float? = null
    private var currentDistanceLabel: String = "DISTANCE: -"
    private lateinit var storage: MeasurementStorage
    private var imageReader: ImageReader? = null


    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportActionBar?.hide()
        hideStatusBar()

        cameraView = findViewById(R.id.cameraView)
        distanceTextView = findViewById(R.id.distanceTextView)
        flashButton = findViewById(R.id.flashButton)
        saveButton = findViewById(R.id.saveButton)
        viewSavedButton = findViewById(R.id.viewSavedButton)
        focusRectangle = findViewById(R.id.focusRectangle)
        cameraButtonsContainer = findViewById(R.id.cameraButtonsContainer)
        zoomButtonsContainer = findViewById(R.id.zoomButtonsContainer)

        storage = MeasurementStorage(this)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        detectBackCameras()

        currentMode = CameraMode.WIDE
        zoomButtonsContainer.visibility = View.GONE
        currentCameraId = mainBackCameraId ?: ultraWideId


        setupCameraModeButtons()
        setupZoomPresetButtons()

        cameraView.surfaceTextureListener = surfaceTextureListener
        cameraView.setOnClickListener { triggerCenterAutoFocus() }
        flashButton.setOnClickListener { toggleFlash() }
        saveButton.setOnClickListener { captureAndSave() }
        viewSavedButton.setOnClickListener {
            startActivity(Intent(this, SavedImagesActivity::class.java))
        }
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
            configureTransform(cameraView.width, cameraView.height)
            openCameraIfPermitted(cameraView.width, cameraView.height)
        }
    }

    override fun onPause()
    {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean)
    {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun detectBackCameras()
    {
        val allIds = cameraManager.cameraIdList

        for (id in allIds)
        {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val isLogicalMulti = caps?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            ) == true

            if (isLogicalMulti)
            {
                mainBackCameraId = id
                break
            }
        }


        var bestUltraWideId: String? = null
        var minFocal: Float? = null

        for (id in allIds)
        {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            if (id == mainBackCameraId) continue

            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val f = focalLengths?.minOrNull() ?: continue

            if (minFocal == null || f < minFocal!!)
            {
                minFocal = f
                bestUltraWideId = id
            }
        }

        if (mainBackCameraId == null && ultraWideId == null)
        {
            for (id in allIds)
            {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK)
                {
                    mainBackCameraId = id
                    break
                }
            }
        }

        if (bestUltraWideId != mainBackCameraId)
        {
            ultraWideId = bestUltraWideId
        }

        logicalBackPhysicalCount = 1
        mainBackCameraId?.let { lid ->
            try {
                val chars = cameraManager.getCameraCharacteristics(lid)
                val physicalIds = chars.physicalCameraIds
                if (physicalIds.isNotEmpty())
                {
                    logicalBackPhysicalCount = physicalIds.size
                }
            }
            catch (e: CameraAccessException)
            {
                Log.e(TAG, "Error reading physicalCameraIds: ${e.message}")
            }
        }

        Log.d(TAG, "logicalBackId = $mainBackCameraId, ultraWideId = $ultraWideId, logicalBackPhysicalCount = $logicalBackPhysicalCount")
    }

    private fun cameraHasAutoFocus(cameraId: String): Boolean {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val afModes = characteristics.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
            ) ?: return false

            afModes.any {
                it != CaptureRequest.CONTROL_AF_MODE_OFF
            }
        } catch (e: CameraAccessException) {
            return false
        }
    }


    private fun setupCameraModeButtons()
    {
        cameraButtonsContainer.removeAllViews()

        val marginPx = (6 * resources.displayMetrics.density).roundToInt()

        ultraWideId?.let { uwId ->
            val uwButton = Button(this).apply {
                text = "UW"
                textSize = 17f
                setTextColor(color(R.color.text_primary))
                setBackgroundColor(color(R.color.overlay_button_background))
                setPadding(20, 20, 20, 20)
                minWidth = 150
                minHeight = 0
                minimumWidth = 150
                minimumHeight = 0

                setOnClickListener {
                    currentMode = CameraMode.UW
                    currentCameraId = uwId

                    if (!cameraHasAutoFocus(uwId)) {
                        Toast.makeText(
                            this@MainActivity,
                            "Ultrawide lens has fixed focus.\nMeasurement may be inaccurate.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    reopenCurrentCamera()
                }
            }
            val lp2 = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            uwButton.layoutParams = lp2
            cameraButtonsContainer.addView(uwButton)
        }

        val wideButton = Button(this).apply {
            text = "W"
            textSize = 17f
            setTextColor(color(R.color.text_primary))
            setBackgroundColor(color(R.color.overlay_button_background))
            setPadding(20, 20, 20, 20)
            minWidth = 150
            minHeight = 0
            minimumWidth = 150
            minimumHeight = 0

            setOnClickListener {
                currentMode = CameraMode.WIDE
                currentCameraId = mainBackCameraId
                reopenCurrentCamera()
                resetZoomOnActiveCamera()
            }
        }
        val lp3 = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(marginPx, marginPx, marginPx, marginPx)
        }
        wideButton.layoutParams = lp3
        cameraButtonsContainer.addView(wideButton)


        if (mainBackCameraId != null && logicalBackPhysicalCount >= 2)
        {
            val ZOOMButton = Button(this).apply {
                text = "ZOOM"
                textSize = 17f
                setTextColor(color(R.color.text_primary))
                setBackgroundColor(color(R.color.overlay_button_background))
                setPadding(20, 20, 20, 20)
                minWidth = 150
                minHeight = 0
                minimumWidth = 150
                minimumHeight = 0

                setOnClickListener {
                    currentMode = CameraMode.ZOOM
                    currentCameraId = mainBackCameraId

                    reopenCurrentCamera()
                }
            }


            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            ZOOMButton.layoutParams = lp
            cameraButtonsContainer.addView(ZOOMButton)
        }
    }

    private fun reopenCurrentCamera()
    {
        if (currentMode == CameraMode.ZOOM && logicalBackPhysicalCount >= 2)
        {
            zoomButtonsContainer.visibility = View.VISIBLE
        }
        else
        {
            zoomButtonsContainer.visibility = View.GONE
        }
        closeCamera()
        if (cameraView.isAvailable)
        {
            openCameraIfPermitted(cameraView.width, cameraView.height)
        }
    }


    private fun setupZoomPresetButtons()
    {
        zoomButtonsContainer.removeAllViews()

        val presets = listOf(
            2.0f to "2x",
            3.0f to "3x",
            4.0f to "4x",
            5.0f to "5x",
            10.0f to "10x"
        )

        for ((factor, label) in presets) {
            val button = Button(this).apply {
                text = label
                textSize = 17f
                setTextColor(color(R.color.text_primary))
                setBackgroundColor(color(R.color.overlay_zoom_button_background))
                setPadding(20, 20, 20, 20)
                minWidth = 150
                minHeight = 0
                minimumWidth = 150
                minimumHeight = 0

                setOnClickListener {
                    when (currentMode) {
                        CameraMode.ZOOM -> setZoomOnActiveCamera(factor)
                        CameraMode.UW -> resetZoomOnActiveCamera()
                        CameraMode.WIDE -> setZoomOnActiveCamera(1.0f)
                    }
                }
            }

            val marginPx = (6 * resources.displayMetrics.density).roundToInt()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            button.layoutParams = lp

            zoomButtonsContainer.addView(button)
        }
    }


    private fun setZoomOnActiveCamera(zoomFactor: Float)
    {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val sensorRect = sensorActiveArray ?: return

        val z = zoomFactor.coerceAtLeast(1.0f)

        val centerX = sensorRect.centerX()
        val centerY = sensorRect.centerY()
        val halfWidth = (sensorRect.width() / (2 * z)).toInt()
        val halfHeight = (sensorRect.height() / (2 * z)).toInt()
        val zoomRect = Rect(
            centerX - halfWidth,
            centerY - halfHeight,
            centerX + halfWidth,
            centerY + halfHeight
        )

        builder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect)

        try {
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "setZoomOnActiveCamera error: ${e.message}")
        }
    }


    private fun resetZoomOnActiveCamera()
    {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val sensorRect = sensorActiveArray ?: return

        builder.set(CaptureRequest.SCALER_CROP_REGION, sensorRect)

        try {
            session.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "resetZoomOnActiveCamera error: ${e.message}")
        }
    }


    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    private fun openCameraIfPermitted(width: Int, height: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
            return
        }
        openCamera(width, height)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
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
            val idToOpen = currentCameraId ?: mainBackCameraId ?: ultraWideId ?: return
            currentCameraId = idToOpen
            openSpecificCamera(idToOpen, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera wrapper error: ${e.message}")
            distanceTextView.text = "Camera error"
        }
    }

    private fun openSpecificCamera(cameraId: String, width: Int, height: Int) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfig =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = streamConfig?.getOutputSizes(SurfaceTexture::class.java)?.find {
                it.width.toFloat() / it.height == 16f / 9f
            } ?: Size(1920, 1080)

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) return

            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception (openSpecificCamera): ${e.message}")
            distanceTextView.text = "Camera error"
        }
    }


    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            activeCameraId = device.id
            createCameraPreviewSession()
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            cameraDevice = null
            activeCameraId = null
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            cameraDevice = null
            activeCameraId = null
            distanceTextView.text = "Camera error"
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = cameraView.surfaceTexture ?: return
            texture.setDefaultBufferSize(cameraView.width, cameraView.height)
            val surface = Surface(texture)

            // Setup ImageReader for still image capture
            val cameraId = activeCameraId ?: return
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            val streamConfigMap =
                cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val largestSize = streamConfigMap?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                ?.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1080)

            // Use maxImages=2 to allow for buffering and prevent "Unable to acquire buffer" errors
            imageReader = ImageReader.newInstance(
                largestSize.width,
                largestSize.height,
                android.graphics.ImageFormat.JPEG,
                2
            )
            imageReader?.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)

            previewRequestBuilder =
                cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder?.addTarget(surface)

            previewRequestBuilder?.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            val sensorArraySize =
                cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
            sensorActiveArray = sensorArraySize

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

            val outputConfigs = mutableListOf<OutputConfiguration>()
            outputConfigs.add(OutputConfiguration(surface))
            imageReader?.surface?.let { outputConfigs.add(OutputConfiguration(it)) }

            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                ContextCompat.getMainExecutor(this),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val previewRequest = previewRequestBuilder!!.build()
                        session.setRepeatingRequest(
                            previewRequest,
                            captureCallback,
                            backgroundHandler
                        )
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        distanceTextView.text = "Couldn't configure camera view"
                    }
                }
            )

            cameraDevice?.createCaptureSession(sessionConfig)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createPreviewSession err: ${e.message}")
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            val params = focusRectangle.layoutParams
            params.width = width / 5
            params.height = height / 5
            focusRectangle.layoutParams = params

            configureTransform(width, height)
            openCameraIfPermitted(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = display?.rotation ?: Surface.ROTATION_0
        val matrix = android.graphics.Matrix()

        cameraView.setTransform(matrix)

        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val scale = maxOf(
                viewHeight.toFloat() / viewWidth.toFloat(),
                viewWidth.toFloat() / viewHeight.toFloat()
            )

            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate(90f * (rotation - 2), centerX, centerY)
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }

        cameraView.setTransform(matrix)
    }


    private fun triggerCenterAutoFocus() {
        try {
            val session = captureSession ?: return
            val builder = previewRequestBuilder ?: return

            val cameraId = activeCameraId ?: return
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorArray =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!

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

            runOnUiThread {
                focusRectangle.animate().cancel()
                focusRectangle.alpha = 1f
            }

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)

                    runOnUiThread {
                        focusRectangle.animate()
                            .alpha(0f)
                            .setStartDelay(100)
                            .setDuration(500)
                            .start()
                    }

                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    builder.set(
                        CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_IDLE
                    )
                    session.setRepeatingRequest(
                        builder.build(),
                        captureCallback,
                        backgroundHandler
                    )
                }
            }, backgroundHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "triggerCenterAutoFocus error: ${e.message}")
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)

            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val shouldUpdateDistance = when (afState) {
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> true
                else -> false
            }

            if (!shouldUpdateDistance) {
                return
            }

            val focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            runOnUiThread {
                if (focusDistance != null && focusDistance > 0) {
                    val distance = 1f / focusDistance
                    currentDistance = distance
                    currentDistanceLabel = String.format("DISTANCE: %.2f m", distance)
                    distanceTextView.text = currentDistanceLabel
                } else {
                    currentDistance = null
                    currentDistanceLabel = "DISTANCE: ∞"
                    distanceTextView.text = currentDistanceLabel
                }
            }
        }
    }

    private fun toggleFlash()
    {
        try {
            val cameraId = activeCameraId ?: return

            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!flashAvailable) {
                return
            }

            isFlashOn = !isFlashOn

            previewRequestBuilder?.set(
                CaptureRequest.FLASH_MODE,
                if (isFlashOn) CaptureRequest.FLASH_MODE_TORCH
                else CaptureRequest.FLASH_MODE_OFF
            )

            captureSession?.setRepeatingRequest(
                previewRequestBuilder!!.build(),
                captureCallback,
                backgroundHandler
            )

            flashButton.text = if (isFlashOn) "FLASHLIGHT ON" else "FLASHLIGHT OFF"
        } catch (e: CameraAccessException) {
            Log.e(TAG, "toggleFlash error: ${e.message}")
        }
    }

    private var isCapturing = false

    private fun captureAndSave() {
        if (isCapturing) return

        val session = captureSession ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val builder = previewRequestBuilder ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val reader = imageReader ?: run {
            Toast.makeText(this, "Image capture not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            isCapturing = true
            saveButton.isEnabled = false
            saveButton.text = "SAVING..."

            val captureBuilder =
                cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    ?: run {
                        resetSaveButton()
                        Toast.makeText(this, "Failed to create capture request", Toast.LENGTH_SHORT)
                            .show()
                        return
                    }

            captureBuilder.addTarget(reader.surface)
            captureBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                builder.get(CaptureRequest.CONTROL_AF_MODE)
            )
            captureBuilder.set(
                CaptureRequest.CONTROL_AF_REGIONS,
                builder.get(CaptureRequest.CONTROL_AF_REGIONS)
            )
            captureBuilder.set(
                CaptureRequest.SCALER_CROP_REGION,
                builder.get(CaptureRequest.SCALER_CROP_REGION)
            )
            if (isFlashOn) captureBuilder.set(
                CaptureRequest.FLASH_MODE,
                CaptureRequest.FLASH_MODE_SINGLE
            )

            val rotation = display?.rotation ?: Surface.ROTATION_0

            val characteristics = cameraManager.getCameraCharacteristics(activeCameraId!!)
//            val sensorOrientation =
//                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val jpegOrientation = getJpegOrientation(
                characteristics,
                rotation
            )
            captureBuilder.set(
                CaptureRequest.JPEG_ORIENTATION,
                jpegOrientation
            )

            session.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        super.onCaptureFailed(session, request, failure)
                        runOnUiThread {
                            resetSaveButton()
                            Toast.makeText(
                                this@MainActivity,
                                "Failed to capture image",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                backgroundHandler
            )

        } catch (e: CameraAccessException) {
            Log.e(TAG, "captureAndSave error: ${e.message}")
            resetSaveButton()
            Toast.makeText(this, "Failed to capture image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetSaveButton() {
        isCapturing = false
        saveButton.isEnabled = true
        saveButton.text = "SAVE"
    }

    private fun getJpegOrientation(
        characteristics: CameraCharacteristics,
        deviceRotation: Int
    ): Int {
        val sensorOrientation =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val deviceDegrees = when (deviceRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        val sign = if (facing == CameraCharacteristics.LENS_FACING_FRONT) 1 else -1

        return (sensorOrientation + sign * deviceDegrees + 360) % 360
    }

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        if (!isCapturing) {
            reader.acquireLatestImage()?.close()
            return@OnImageAvailableListener
        }

        val image = reader.acquireLatestImage() ?: run {
            Log.e(TAG, "ImageReader returned null image")
            runOnUiThread {
                resetSaveButton()
            }
            return@OnImageAvailableListener
        }

        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            Log.d(TAG, "Image captured, size: ${bytes.size} bytes")

            val measurement = storage.saveMeasurement(
                jpegBytes = bytes,
                distance = currentDistance,
                distanceLabel = currentDistanceLabel,
                cameraMode = ""
            )

            Log.d(TAG, "Measurement saved: ${measurement.id}, path: ${measurement.imagePath}")

            runOnUiThread {
                resetSaveButton()
                Toast.makeText(this@MainActivity, "Measurement saved!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}", e)
            runOnUiThread {
                resetSaveButton()
                Toast.makeText(
                    this@MainActivity,
                    "Error saving image: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        } finally {
            image.close()
        }
    }

    private fun closeCamera() {
//        captureSession?.close()
        captureSession?.apply {
            stopRepeating()
            abortCaptures()
            close()
        }
        captureSession = null
        imageReader?.close()
        imageReader = null
        cameraDevice?.close()
        cameraDevice = null
        activeCameraId = null
    }
}