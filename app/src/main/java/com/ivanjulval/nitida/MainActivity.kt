package com.ivanjulval.nitida

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.ivanjulval.nitida.core.Adjustments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var topBar: LinearLayout
    private lateinit var txtStatus: TextView
    private lateinit var bottomPanel: LinearLayout
    private lateinit var presetGroup: ChipGroup
    private lateinit var thumbnail: ImageView
    private lateinit var btnShutter: ImageButton
    private lateinit var btnFlip: ImageButton
    private lateinit var btnAdjust: ImageButton
    private lateinit var busyOverlay: LinearLayout
    private lateinit var busyText: TextView
    private lateinit var resultOverlay: FrameLayout
    private lateinit var resultImage: ImageView
    private lateinit var resultLabel: TextView
    private lateinit var resultButtons: LinearLayout

    private lateinit var store: PresetStore
    private var preset = Preset.NATURAL
    private var adj = Adjustments()

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var busy = false
    private var lastUri: Uri? = null
    private var lastResult: ProcessedPhoto? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionNeeded()
        }

    private val closeResultOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hideResult()
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // La pantalla está fija en vertical, pero la foto debe salir derecha si giras el móvil
                imageCapture?.targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        store = PresetStore(this)
        preset = store.current
        adj = store.load(preset)

        // TextureView en lugar de SurfaceView para poder aplicar el filtro de vista previa
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        applyInsets()
        setupPresetChips()
        setupButtons()
        setupTapToFocus()
        onBackPressedDispatcher.addCallback(this, closeResultOnBack)
        refreshStatus()

        if (hasCameraPermission()) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onResume() {
        super.onResume()
        // Por si se ha dado el permiso desde Ajustes del sistema
        if (cameraProvider == null && hasCameraPermission()) startCamera()
    }

    override fun onStop() {
        orientationListener.disable()
        super.onStop()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ interfaz

    private fun bindViews() {
        root = findViewById(R.id.root)
        previewView = findViewById(R.id.previewView)
        topBar = findViewById(R.id.topBar)
        txtStatus = findViewById(R.id.txtStatus)
        bottomPanel = findViewById(R.id.bottomPanel)
        presetGroup = findViewById(R.id.presetGroup)
        thumbnail = findViewById(R.id.thumbnail)
        btnShutter = findViewById(R.id.btnShutter)
        btnFlip = findViewById(R.id.btnFlip)
        btnAdjust = findViewById(R.id.btnAdjust)
        busyOverlay = findViewById(R.id.busyOverlay)
        busyText = findViewById(R.id.busyText)
        resultOverlay = findViewById(R.id.resultOverlay)
        resultImage = findViewById(R.id.resultImage)
        resultLabel = findViewById(R.id.resultLabel)
        resultButtons = findViewById(R.id.resultButtons)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updatePadding(top = bars.top + dp(8))
            bottomPanel.updatePadding(bottom = bars.bottom + dp(16))
            resultLabel.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top + dp(16) }
            resultButtons.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = bars.bottom + dp(24) }
            insets
        }
    }

    private fun setupPresetChips() {
        for (p in Preset.entries) {
            val chip = layoutInflater.inflate(R.layout.item_preset_chip, presetGroup, false) as Chip
            chip.id = View.generateViewId()
            chip.text = p.label
            chip.tag = p
            presetGroup.addView(chip)
            if (p == preset) chip.isChecked = true
        }
        presetGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val p = group.findViewById<Chip>(id)?.tag as? Preset ?: return@setOnCheckedStateChangeListener
            if (p != preset) selectPreset(p)
        }
    }

    private fun selectPreset(p: Preset) {
        preset = p
        store.current = p
        adj = store.load(p)
        applyAdjustmentsToCamera()
        refreshStatus()
        Toast.makeText(this, p.description, Toast.LENGTH_SHORT).show()
    }

    private fun setupButtons() {
        btnShutter.setOnClickListener { takePhoto() }
        btnFlip.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            bindCamera()
        }
        btnAdjust.setOnClickListener {
            AdjustmentsSheet.show(
                context = this,
                preset = preset,
                initial = adj,
                mirrorSelfies = store.mirrorSelfies,
                onChange = { a ->
                    adj = a
                    store.save(preset, a)
                    applyAdjustmentsToCamera()
                    refreshStatus()
                },
                onReset = {
                    store.reset(preset)
                    store.load(preset)
                },
                onMirrorChange = { store.mirrorSelfies = it },
            )
        }
        thumbnail.setOnClickListener { lastResult?.let { showResult(it) } }
        findViewById<View>(R.id.btnResultClose).setOnClickListener { hideResult() }
        findViewById<View>(R.id.btnResultOpen).setOnClickListener { openInGallery() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        previewView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                camera?.let { cam ->
                    val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(
                        point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
                    cam.cameraControl.startFocusAndMetering(action)
                }
                v.performClick()
            }
            true
        }
    }

    private fun refreshStatus() {
        val frames = if (adj.burstFrames == 1) "1 foto" else "ráfaga de ${adj.burstFrames}"
        val ev = if (adj.exposureEv != 0f) String.format(Locale.getDefault(), " · %+.1f EV", adj.exposureEv) else ""
        txtStatus.text = "${preset.label} · $frames$ev"
    }

    private fun setBusy(on: Boolean, text: String? = null) {
        busyOverlay.isVisible = on
        busyText.text = text ?: ""
        btnShutter.isEnabled = !on
    }

    // ------------------------------------------------------------------ cámara

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionNeeded() {
        txtStatus.setText(R.string.camera_permission_needed)
        Snackbar.make(root, R.string.camera_permission_needed, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.grant) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    // Permiso denegado para siempre: hay que ir a los ajustes de la app
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    )
                }
            }
            .show()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo iniciar la cámara", e)
                toast("No se pudo iniciar la cámara")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!provider.hasCamera(selector)) {
            toast("Esta cámara no está disponible")
            return
        }
        val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        // Hasta 12 MP: con 64 MP la fusión de 8 fotos no cabría en memoria
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    )
                    .build()
            )
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .build()

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview, capture)
            imageCapture = capture
            applyAdjustmentsToCamera()
        } catch (e: Exception) {
            Log.e(TAG, "Error al conectar la cámara", e)
            toast("Error al conectar la cámara")
        }
    }

    /** Exposición en la cámara (afecta a la foto real) + filtro aproximado en el visor. */
    private fun applyAdjustmentsToCamera() {
        PreviewLook.apply(previewView, adj)
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val step = state.exposureCompensationStep.toFloat()
        if (step <= 0f) return
        val range = state.exposureCompensationRange
        val index = (adj.exposureEv / step).roundToInt().coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(index)
    }

    // ------------------------------------------------------------------ foto

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (busy) return
        busy = true
        val settings = adj.copy()
        val count = settings.burstFrames
        val mirror = lensFacing == CameraSelector.LENS_FACING_FRONT && store.mirrorSelfies
        setBusy(true, if (count > 1) "No te muevas… 0/$count" else "Haciendo foto…")

        lifecycleScope.launch {
            try {
                val shots = captureBurst(capture, count, cameraExecutor) { n ->
                    if (count > 1) busyText.text = "No te muevas… $n/$count"
                }
                busyText.text = "Procesando…"
                val result = withContext(Dispatchers.Default) {
                    BurstProcessor.process(shots, settings, mirror) { msg ->
                        runOnUiThread { busyText.text = msg }
                    }
                }
                busyText.text = "Guardando…"
                val uri = withContext(Dispatchers.IO) { PhotoSaver.save(this@MainActivity, result.full) }
                lastUri = uri
                lastResult = result
                thumbnail.setImageBitmap(result.after)
                showResult(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Sin memoria", e)
                toast("No hay memoria suficiente. Prueba con menos fotos en la ráfaga.")
            } catch (e: Exception) {
                Log.e(TAG, "Error al hacer la foto", e)
                toast("Error: ${e.message}")
            } finally {
                busy = false
                setBusy(false)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showResult(result: ProcessedPhoto) {
        val info = if (result.framesTotal > 1) {
            "  ·  ${result.framesUsed}/${result.framesTotal} fotos fusionadas"
        } else ""
        val afterLabel = getString(R.string.hold_to_compare) + info
        resultImage.setImageBitmap(result.after)
        resultLabel.text = afterLabel
        resultImage.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resultImage.setImageBitmap(result.before)
                    resultLabel.setText(R.string.showing_original)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resultImage.setImageBitmap(result.after)
                    resultLabel.text = afterLabel
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
        resultOverlay.isVisible = true
        closeResultOnBack.isEnabled = true
    }

    private fun hideResult() {
        resultOverlay.isVisible = false
        closeResultOnBack.isEnabled = false
    }

    private fun openInGallery() {
        val uri = lastUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("No hay ninguna galería para abrir la foto")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "Nitida"
    }
}
