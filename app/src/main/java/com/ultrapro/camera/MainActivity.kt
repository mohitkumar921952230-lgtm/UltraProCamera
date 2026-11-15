package com.ultrapro.camera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ultrapro.camera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var currentMode = "PHOTO"
    private var selectedLut = AiLutEngine.LutPreset.AI_AUTO
    private var aiUltraEnabled = true
    private var timerSeconds = 0
    private var isGridVisible = false

    private val modesList = listOf("NIGHT", "BOKEH", "PHOTO", "VIDEO", "HDR", "PRO")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            initCameraSystem()
        } else {
            Toast.makeText(this, "Camera permission zaroori hai!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        setupModesUI()
        setupLutsUI()
        setupControls()

        if (hasPermissions()) {
            initCameraSystem()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun initCameraSystem() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val extFuture = ExtensionsManager.getInstanceAsync(this, cameraProvider!!)
            extFuture.addListener({
                try {
                    extensionsManager = extFuture.get()
                } catch (_: Exception) {
                }
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val baseSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val extensionMode = when (currentMode) {
            "NIGHT" -> ExtensionMode.NIGHT
            "BOKEH" -> ExtensionMode.BOKEH
            "HDR" -> ExtensionMode.HDR
            else -> ExtensionMode.NONE
        }

        val finalSelector = if (extensionMode != ExtensionMode.NONE &&
            extensionsManager?.isExtensionAvailable(baseSelector, extensionMode) == true
        ) {
            extensionsManager!!.getExtensionEnabledCameraSelector(baseSelector, extensionMode)
        } else {
            baseSelector
        }

        val preview = Preview.Builder()
            .setPreviewStabilizationEnabled(true)
            .build()
            .also {
                it.surfaceProvider = binding.viewFinder.surfaceProvider
            }

        try {
            if (currentMode == "VIDEO") {
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.FHD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
                        )
                    )
                    .build()
                videoCapture = VideoCapture.Builder(recorder)
                    .setVideoStabilizationEnabled(true)
                    .build()

                camera = provider.bindToLifecycle(
                    this, finalSelector, preview, videoCapture
                )
            } else {
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flashMode)
                    .build()

                camera = provider.bindToLifecycle(
                    this, finalSelector, preview, imageCapture
                )
            }
            setupZoomAndTouch()
            setupExposureControl()
        } catch (e: Exception) {
            if (finalSelector != baseSelector) {
                currentMode = "PHOTO"
                bindCameraUseCases()
            }
        }
    }

    private fun setupZoomAndTouch() {
        val cam = camera ?: return
        binding.zoomSeekBar.progress = 0
        binding.tvZoomLevel.text = "1.0x"

        binding.zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = progress / 100f
                cam.cameraControl.setLinearZoom(ratio)
                val zoomState = cam.cameraInfo.zoomState.value
                val currentZoom = zoomState?.zoomRatio ?: (1.0f + ratio * 7f)
                binding.tvZoomLevel.text = String.format(Locale.US, "%.1fx", currentZoom)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val scaleGesture = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 8f
                val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                val newZoom = (currentZoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                cam.cameraControl.setZoomRatio(newZoom)
                binding.tvZoomLevel.text = String.format(Locale.US, "%.1fx", newZoom)
                return true
            }
        })

        binding.viewFinder.setOnTouchListener { v, event ->
            scaleGesture.onTouchEvent(event)
            if (event.action == android.view.MotionEvent.ACTION_UP && !scaleGesture.isInProgress) {
                val factory = binding.viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point).build()
                cam.cameraControl.startFocusAndMetering(action)
                v.performClick()
            }
            true
        }
    }

    private fun setupExposureControl() {
        val cam = camera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        binding.evSeekBar.progress = 50
        binding.evSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (range.upper > range.lower) {
                    val index = range.lower + ((progress / 100f) * (range.upper - range.lower)).toInt()
                    cam.cameraControl.setExposureCompensationIndex(index)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupControls() {
        binding.btnFlash.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> {
                    binding.btnFlash.text = "⚡ ON"
                    ImageCapture.FLASH_MODE_ON
                }
                ImageCapture.FLASH_MODE_ON -> {
                    binding.btnFlash.text = "⚡ AUTO"
                    ImageCapture.FLASH_MODE_AUTO
                }
                else -> {
                    binding.btnFlash.text = "⚡ OFF"
                    ImageCapture.FLASH_MODE_OFF
                }
            }
            imageCapture?.flashMode = flashMode
        }

        binding.btnAiBoost.setOnClickListener {
            aiUltraEnabled = !aiUltraEnabled
            binding.btnAiBoost.text = if (aiUltraEnabled) "✨ AI ULTRA: ON" else "✨ AI ULTRA: OFF"
binding.btnAiBoost.setTextColor(if (aiUltraEnabled) Color.parseColor("#FFD700") else Color.WHITE)
}
binding.btnGrid.setOnClickListener {
isGridVisible = !isGridVisible
binding.gridOverlay.visibility = if (isGridVisible) View.VISIBLE else View.GONE
}
binding.btnTimer.setOnClickListener {
timerSeconds = when (timerSeconds) {
0 -> 3
3 -> 10
else -> 0
}
binding.btnTimer.text = "⏱ ${timerSeconds}s"
}
binding.btnSwitchCamera.setOnClickListener {
lensFacing = if(lensFacing == CameraSelector.LENS_FACING_BACK) {
CameraSelector.LENS_FACING_FRONT
} else {
CameraSelector.LENS_FACING_BACK
}
bindCameraUseCases()
}
binding.btnGallery.setOnClickListener {
val intent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
startActivity(intent)
}
binding.btnCapture.setOnClickListener {
if (currentMode == "VIDEO") {
toggleVideoRecording()
} else {
triggerPhotoCaptureWithTimer()
}
}
}
private fun triggerPhotoCaptureWithTimer() {
if (timerSeconds == 0) {
captureAndAiEnhancePhoto()
return
}
lifecycleScope.launch {
binding.tvCountdown.visibility = View.VISIBLE
for (sec in timerSeconds downTo 1) {
binding.tvCountdown.text = sec.toString()
delay(1000L)
}
binding.tvCountdown.visibility = View.GONE
captureAndAiEnhancePhoto()
}
}
private fun captureAndAiEnhancePhoto() {
val capture = imageCapture ?: return
binding.tvProcessing.visibility = View.VISIBLE
capture.takePicture(
ContextCompat.getMainExecutor(this),
object : ImageCapture.OnImageCapturedCallback() {
override fun onCaptureSuccess(image: ImageProxy) {
lifecycleScope.launch {
val saved = processAndSaveImageProxy(image)
binding.tvProcessing.visibility = View.GONE
if (saved) {
Toast.makeText(
this@MainActivity,
"Saved Ultra HD (${selectedLut.title})!",
Toast.LENGTH_SHORT
).show()
}
}
}
override fun onError(exception: ImageCaptureException) {
binding.tvProcessing.visibility = View.GONE
Toast.makeText(this@MainActivity, "Capture failed", Toast.LENGTH_SHORT).show()
}
}
)
}
private suspend fun processAndSaveImageProxy(image: ImageProxy): Boolean = withContext(Dispatchers.IO) {
try {
val buffer = image.planes[0].buffer
val bytes = ByteArray(buffer.remaining())
buffer.get(bytes)
val rotation = image.imageInfo.rotationDegrees
image.close()
var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext false
if (rotation != 0) {
val matrix = Matrix().apply {
postRotate(rotation.toFloat())
if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
postScale(-1f, 1f)
}
}
bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
val enhancedBitmap =            val enhancedBitmap = AiLutEngine.enhanceImageOffline(
                source = bitmap,
                lut = selectedLut,
                aiUltraEnabled = aiUltraEnabled,
                isNightMode = (currentMode == "NIGHT")
            )

            saveBitmapToGallery(enhancedBitmap)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val name = "ULTRAPRO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis()) + ".jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/UltraProCamera")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
            }
        }
    }

    private fun toggleVideoRecording() {
        val vidCapture = videoCapture ?: return
        val curRecording = activeRecording
        if (curRecording != null) {
            curRecording.stop()
            activeRecording = null
            binding.btnCapture.text = "●"
            binding.btnCapture.setTextColor(Color.BLACK)
            return
        }

        val name = "ULTRAPRO_VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/UltraProCamera")
            }
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        activeRecording = vidCapture.output
            .prepareRecording(this, mediaStoreOutput)
            .apply {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        binding.btnCapture.text = "■"
                        binding.btnCapture.setTextColor(Color.RED)
                        Toast.makeText(this, "Stabilized Video Recording Started", Toast.LENGTH_SHORT).show()
                    }
                    is VideoRecordEvent.Finalize -> {
                        Toast.makeText(this, "Ultra HD Video Saved to Gallery!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
    }

    private fun setupModesUI() {
        binding.modeContainer.removeAllViews()
        for (mode in modesList) {
            val tv = TextView(this).apply {
                text = mode
                textSize = 14f
                setPadding(28, 12, 28, 12)
                setTextColor(if (mode == currentMode) Color.parseColor("#FFD700") else Color.WHITE)
                setOnClickListener {
                    currentMode = mode
                    binding.btnCapture.text = if (mode == "VIDEO") "🎥" else "●"
                    setupModesUI()
                    bindCameraUseCases()
                }
            }
            binding.modeContainer.addView(tv)
        }
    }

    private fun setupLutsUI() {
        binding.lutContainer.removeAllViews()
        for (lut in AiLutEngine.LutPreset.values()) {
            val tv = TextView(this).apply {
                text = lut.title
                textSize = 12f
                setPadding(24, 10, 24, 10)
                setBackgroundColor(
                    if (lut == selectedLut) Color.parseColor("#55FFD700") else Color.parseColor("#33FFFFFF")
                )
                setTextColor(if (lut == selectedLut) Color.parseColor("#FFD700") else Color.WHITE)
                val params = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(8, 0, 8, 0) }
                layoutParams = params

                setOnClickListener {
                    selectedLut = lut
                    setupLutsUI()
                    Toast.makeText(this@MainActivity, "LUT Active: ${lut.title}", Toast.LENGTH_SHORT).show()
                }
            }
            binding.lutContainer.addView(tv)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

          
