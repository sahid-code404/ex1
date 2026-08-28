package com.sahidcode404.camera

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sahidcode404.camera.core.discovery.LensCache
import com.sahidcode404.camera.core.discovery.TopologyReconciler
import com.sahidcode404.camera.core.discovery.UniversalCameraDiscoverer
import com.sahidcode404.camera.core.model.AspectMode
import com.sahidcode404.camera.core.model.CameraRoute
import com.sahidcode404.camera.core.model.CanonicalLens
import com.sahidcode404.camera.core.model.LensFacing
import com.sahidcode404.camera.core.model.PreviewMode
import com.sahidcode404.camera.core.preview.OutputOrientation
import com.sahidcode404.camera.core.raw.LensSettingsStore
import com.sahidcode404.camera.core.session.Camera2Controller
import com.sahidcode404.camera.core.update.DevelopmentOtaClient
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity(), Camera2Controller.Listener {
    private lateinit var root: FrameLayout
    private lateinit var previewHost: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var rawPreviewView: ImageView
    private lateinit var lensRow: LinearLayout
    private lateinit var modeButton: Button
    private lateinit var aspectButton: Button
    private lateinit var frameButton: Button
    private lateinit var statusView: TextView
    private lateinit var shutter: Button

    private lateinit var discoverer: UniversalCameraDiscoverer
    private lateinit var cache: LensCache
    private lateinit var lensSettings: LensSettingsStore
    private lateinit var controller: Camera2Controller
    private lateinit var ota: DevelopmentOtaClient
    private val discoveryExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "LensDiscovery") }

    private var lenses: List<CanonicalLens> = emptyList()
    private var activeLens: CanonicalLens? = null
    private var openedRoute: CameraRoute? = null
    private var previewMode = PreviewMode.API
    private var aspectMode = AspectMode.FOUR_THREE
    private var mirrorFront = true
    private var firstFrameReleased = false
    private var deepDiscoveryStarted = false
    private var pendingCaptureAfterReopen = false
    private var latestFocalLengthMm: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        buildUi()

        discoverer = UniversalCameraDiscoverer(this)
        cache = LensCache(this)
        lensSettings = LensSettingsStore(this)
        controller = Camera2Controller(this, this)
        ota = DevelopmentOtaClient { message -> statusView.text = message }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = bootstrapIfReady()
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updatePreviewBounds()
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                controller.close()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (previewMode == PreviewMode.API) releaseFirstFrameGate()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        } else {
            bootstrapIfReady()
        }
    }

    override fun onResume() {
        super.onResume()
        ota.installReadyIfPermitted(this)
        if (::textureView.isInitialized && textureView.isAvailable && activeLens != null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            openActiveLens()
        }
    }

    override fun onPause() {
        controller.close()
        super.onPause()
    }

    override fun onDestroy() {
        controller.shutdown()
        discoveryExecutor.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            bootstrapIfReady()
        } else if (requestCode == CAMERA_PERMISSION) {
            statusView.text = "Camera permission is required"
        }
    }

    private fun bootstrapIfReady() {
        if (!textureView.isAvailable) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (lenses.isNotEmpty()) {
            openActiveLens()
            return
        }
        val ids = runCatching { discoverer.publicIds() }.getOrElse {
            statusView.text = "Camera enumeration failed"
            return
        }
        val cached = cache.load(ids)
        if (!cached.isNullOrEmpty()) {
            setLenses(cached)
            statusView.text = "Cached lenses ready"
            return
        }

        // First install must not block visible preview on RAW stream scans or logical/physical AUX
        // reconciliation. Seed only the public preview routes, then do the complete topology pass
        // after the first real frame has reached the UI.
        statusView.text = "Preparing camera"
        discoveryExecutor.execute {
            val seed = runCatching { discoverer.discoverStartupSeed() }.getOrElse { emptyList() }
            runOnUiThread {
                if (seed.isEmpty()) statusView.text = "No usable camera route found" else setLenses(seed)
            }
        }
    }

    private fun setLenses(newLenses: List<CanonicalLens>) {
        lenses = newLenses
        val last = cache.lastLensId()
        val previous = activeLens
        activeLens = newLenses.firstOrNull { it.id == previous?.id }
            ?: newLenses.firstOrNull { it.id == last }
            ?: newLenses.firstOrNull { it.facing == LensFacing.BACK }
            ?: newLenses.firstOrNull()
        rebuildLensRow()
        updatePreviewBounds()
        openActiveLens()
    }

    private fun openActiveLens() {
        val lens = activeLens ?: return
        if (!textureView.isAvailable) return
        firstFrameReleased = false
        rawPreviewView.visibility = if (previewMode == PreviewMode.RAW) View.VISIBLE else View.GONE
        textureView.visibility = if (previewMode == PreviewMode.API) View.VISIBLE else View.INVISIBLE
        controller.open(
            lens = lens,
            texture = textureView,
            rawView = rawPreviewView,
            mode = previewMode,
            aspect = aspectMode,
            mirrorFront = mirrorFront,
            displayRotation = currentDisplayRotation(),
        )
    }

    private fun releaseFirstFrameGate() {
        if (firstFrameReleased) return
        firstFrameReleased = true
        statusView.text = ""
        if (!deepDiscoveryStarted) {
            deepDiscoveryStarted = true
            ota.checkAfterFirstFrame(this)
            val ids = runCatching { discoverer.publicIds() }.getOrDefault(emptyList())
            discoveryExecutor.execute {
                val full = runCatching { discoverer.discover() }.getOrDefault(emptyList())
                if (full.isNotEmpty()) {
                    cache.save(ids, full)
                    runOnUiThread {
                        // Rebind canonical identity and lens controls to the rich topology while the
                        // already-open Camera2 session continues streaming. Do not reopen here.
                        activeLens = TopologyReconciler.reconcileVisibleLens(
                            currentLens = activeLens,
                            openedRoute = openedRoute,
                            observedFocalLengthMm = latestFocalLengthMm,
                            fullTopology = full,
                        ) ?: activeLens
                        lenses = full
                        rebuildLensRow()
                        updatePreviewBounds()
                    }
                }
            }
        }
    }

    private fun rebuildLensRow() {
        lensRow.removeAllViews()
        lenses.forEach { lens ->
            val button = Button(this).apply {
                text = if (lens.supportsRaw) lens.label else "${lens.label} · no RAW"
                isAllCaps = false
                alpha = if (lens.id == activeLens?.id) 1f else 0.68f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    activeLens = lens
                    openedRoute = null
                    pendingCaptureAfterReopen = false
                    rebuildLensRow()
                    updatePreviewBounds()
                    openActiveLens()
                }
            }
            lensRow.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
        }
        updateFrameButton()
    }

    private fun updatePreviewBounds() {
        if (!::previewHost.isInitialized) return
        previewHost.post {
            val width = (root.width - dp(16)).coerceAtLeast(dp(240))
            val availableHeight = (root.height - dp(190)).coerceAtLeast(dp(320))
            val sensorRatio = activeLens?.preferredRoute?.rawSizes?.firstOrNull()?.let {
                maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height)
            } ?: activeLens?.preferredRoute?.previewSizes?.firstOrNull()?.let {
                maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height)
            } ?: 4f / 3f
            val desired = when (aspectMode) {
                AspectMode.SENSOR -> (width * sensorRatio).toInt()
                AspectMode.ONE_ONE -> width
                AspectMode.FOUR_THREE -> (width * 4f / 3f).toInt()
                AspectMode.SIXTEEN_NINE -> (width * 16f / 9f).toInt()
                AspectMode.FULL -> availableHeight
            }.coerceAtMost(availableHeight)
            previewHost.layoutParams = FrameLayout.LayoutParams(width, desired, Gravity.CENTER).apply {
                topMargin = dp(4)
                bottomMargin = dp(32)
            }
        }
    }

    private fun cycleAspect() {
        val all = AspectMode.entries
        aspectMode = all[(all.indexOf(aspectMode) + 1) % all.size]
        aspectButton.text = aspectLabel(aspectMode)
        updatePreviewBounds()
        openActiveLens()
    }

    private fun togglePreviewMode() {
        previewMode = if (previewMode == PreviewMode.API) PreviewMode.RAW else PreviewMode.API
        modeButton.text = if (previewMode == PreviewMode.API) "API preview" else "RAW preview"
        openActiveLens()
    }

    private fun onShutter() {
        val lens = activeLens ?: return
        if (!lens.supportsRaw) {
            statusView.text = "This lens does not expose RAW_SENSOR"
            return
        }
        if (openedRoute?.supportsRaw != true) {
            pendingCaptureAfterReopen = true
            openActiveLens()
            return
        }
        val route = openedRoute ?: return
        controller.capture(
            lensSettings.bounds(lens.id),
            OutputOrientation.exifOrientation(route.sensorOrientation, currentDisplayRotation(), route.facing, mirrorFront),
        )
    }

    private fun showFrameSettings() {
        val lens = activeLens ?: return
        val bounds = lensSettings.bounds(lens.id)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val minText = TextView(this).apply { setTextColor(Color.WHITE) }
        val maxText = TextView(this).apply { setTextColor(Color.WHITE) }
        val minSeek = SeekBar(this).apply { max = 14; progress = bounds.minFrames - 2 }
        val maxSeek = SeekBar(this).apply { max = 14; progress = bounds.maxFrames - 2 }
        fun refresh() {
            val min = minSeek.progress + 2
            val max = (maxSeek.progress + 2).coerceAtLeast(min)
            if (maxSeek.progress + 2 < min) maxSeek.progress = min - 2
            minText.text = "Minimum frames: $min"
            maxText.text = "Maximum frames: $max"
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = refresh()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        minSeek.setOnSeekBarChangeListener(listener)
        maxSeek.setOnSeekBarChangeListener(listener)
        refresh()
        content.addView(minText); content.addView(minSeek); content.addView(maxText); content.addView(maxSeek)
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("${lens.label} RAW burst")
            .setView(content)
            .setPositiveButton("Save") { _, _ ->
                lensSettings.setBounds(lens.id, minSeek.progress + 2, maxSeek.progress + 2)
                updateFrameButton()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateFrameButton() {
        val lens = activeLens ?: return
        val b = lensSettings.bounds(lens.id)
        frameButton.text = "Frames ${b.minFrames}–${b.maxFrames}"
    }

    override fun onRouteOpened(lens: CanonicalLens, route: CameraRoute) {
        openedRoute = route
        cache.saveLastWorkingRoute(lens.id, route.routeKey)
        rawPreviewView.visibility = if (previewMode == PreviewMode.RAW) View.VISIBLE else View.GONE
        textureView.visibility = if (previewMode == PreviewMode.API) View.VISIBLE else View.INVISIBLE
        if (pendingCaptureAfterReopen && route.supportsRaw) {
            pendingCaptureAfterReopen = false
            onShutter()
        }
    }

    override fun onPreviewCaptureState(exposureNs: Long?, iso: Int?, focalLengthMm: Float?) {
        latestFocalLengthMm = focalLengthMm
    }

    override fun onRawPreviewFrame() {
        releaseFirstFrameGate()
    }

    override fun onCaptureStarted(frameCount: Int) {
        shutter.isEnabled = false
        statusView.text = "Capturing $frameCount RAW frames"
    }

    override fun onCaptureCompleted(uri: android.net.Uri, acceptedFrames: Int) {
        shutter.isEnabled = true
        statusView.text = "Saved merged DNG · $acceptedFrames frames"
    }

    override fun onError(message: String, throwable: Throwable?) {
        shutter.isEnabled = true
        statusView.text = message
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        previewHost = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(28).toFloat()
            }
            clipToOutline = true
        }
        root.addView(previewHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        textureView = TextureView(this).apply { isOpaque = true }
        rawPreviewView = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        previewHost.addView(textureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewHost.addView(rawPreviewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        modeButton = controlButton("API preview") { togglePreviewMode() }
        aspectButton = controlButton(aspectLabel(aspectMode)) { cycleAspect() }
        frameButton = controlButton("Frames 3–10") { showFrameSettings() }
        top.addView(modeButton); top.addView(aspectButton); top.addView(frameButton)
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        lensRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(lensRow)
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 12f
            maxLines = 1
        }
        shutter = Button(this).apply {
            text = "●"
            textSize = 30f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { onShutter() }
        }
        bottom.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        bottom.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        bottom.addView(shutter, LinearLayout.LayoutParams(dp(88), dp(68)))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(154), Gravity.BOTTOM))
    }

    private fun controlButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun aspectLabel(mode: AspectMode) = when (mode) {
        AspectMode.SENSOR -> "Sensor"
        AspectMode.ONE_ONE -> "1:1"
        AspectMode.FOUR_THREE -> "4:3"
        AspectMode.SIXTEEN_NINE -> "16:9"
        AspectMode.FULL -> "Full"
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int = windowManager.defaultDisplay.rotation

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CAMERA_PERMISSION = 4101
    }
}
