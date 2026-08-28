package com.sahidcode404.camera.core.session

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import com.sahidcode404.camera.core.model.AspectMode
import com.sahidcode404.camera.core.model.CameraRoute
import com.sahidcode404.camera.core.model.CanonicalLens
import com.sahidcode404.camera.core.model.PreviewMode
import com.sahidcode404.camera.core.preview.PreviewGeometry
import com.sahidcode404.camera.core.raw.BurstMemoryBudget
import com.sahidcode404.camera.core.raw.DngOutputWriter
import com.sahidcode404.camera.core.raw.FramePlanner
import com.sahidcode404.camera.core.raw.LensBurstBounds
import com.sahidcode404.camera.core.raw.NativeRawMerger
import com.sahidcode404.camera.core.raw.RawBurstTimeoutPolicy
import com.sahidcode404.camera.core.raw.RawTimestampPairingPolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.pow

class Camera2Controller(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onRouteOpened(lens: CanonicalLens, route: CameraRoute)
        fun onPreviewCaptureState(exposureNs: Long?, iso: Int?, focalLengthMm: Float?)
        fun onRawPreviewFrame()
        fun onCaptureStarted(frameCount: Int)
        fun onCaptureCompleted(uri: android.net.Uri, acceptedFrames: Int)
        fun onError(message: String, throwable: Throwable? = null)
    }

    private data class CapturedRaw(
        val timestamp: Long,
        val bytes: ByteBuffer,
        val result: TotalCaptureResult,
        val exposureNs: Long,
        val iso: Int,
    )

    private data class BurstRequestTag(
        val generation: Long,
        val captureId: Long,
    )

    private val manager = context.getSystemService(CameraManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraThread = HandlerThread("CameraOwner").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val processing = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RawProcessing").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val sessionExecutor = Executor { command -> cameraHandler.post(command) }
    private val generationGuard = Any()

    @Volatile private var generation = 0L
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var activeRoute: CameraRoute? = null
    private var textureView: TextureView? = null
    private var rawPreviewView: ImageView? = null
    private var previewMode: PreviewMode = PreviewMode.API
    private var aspectMode: AspectMode = AspectMode.FOUR_THREE
    private var mirrorFront: Boolean = true
    private var displayRotation: Int = Surface.ROTATION_0
    private var currentPreviewSize: Size? = null
    private var currentRawSize: Size? = null
    private var currentCharacteristics: CameraCharacteristics? = null
    private var latestExposureNs: Long? = null
    private var latestIso: Int? = null

    private var nextCaptureId = 0L
    private var activeCaptureId = 0L
    private var captureGeneration = 0L
    private var captureExpected = 0
    private var captureMinimum = 0
    private var captureCompletedCallbacks = 0
    private var captureFailedCallbacks = 0
    private var capturePausedRawPreview = false
    private val pendingImages = TreeMap<Long, ByteBuffer>()
    private val pendingResults = TreeMap<Long, TotalCaptureResult>()
    private val captured = mutableListOf<CapturedRaw>()
    private var captureOrientation: Int = 1

    fun open(
        lens: CanonicalLens,
        texture: TextureView,
        rawView: ImageView,
        mode: PreviewMode,
        aspect: AspectMode,
        mirrorFront: Boolean,
        displayRotation: Int,
    ) {
        cameraHandler.post {
            val token = advanceGeneration()
            closeOwned()
            textureView = texture
            rawPreviewView = rawView
            previewMode = mode
            aspectMode = aspect
            this.mirrorFront = mirrorFront
            this.displayRotation = displayRotation
            openRouteCandidates(lens, lens.routes.iterator(), token)
        }
    }

    fun close() {
        cameraHandler.post {
            advanceGeneration()
            closeOwned()
        }
    }

    fun shutdown() {
        cameraHandler.post {
            advanceGeneration()
            closeOwned()
            processing.shutdown()
            cameraThread.quitSafely()
        }
    }

    fun capture(bounds: LensBurstBounds, orientation: Int) {
        cameraHandler.post {
            val route = activeRoute ?: return@post reportError("No active camera route")
            val currentSession = session ?: return@post reportError("Camera session is not ready")
            val currentDevice = device ?: return@post reportError("Camera device is not ready")
            val reader = rawReader ?: return@post reportError("Selected lens has no configured RAW stream")
            val rawSize = currentRawSize ?: return@post reportError("Selected lens does not expose RAW_SENSOR")
            if (captureExpected > 0) return@post

            val memoryMax = BurstMemoryBudget.maxFrames(context, rawSize, bounds.maxFrames)
            val minimum = bounds.minFrames.coerceAtMost(memoryMax).coerceAtLeast(2)
            val plan = FramePlanner.plan(
                latestExposureNs,
                latestIso,
                minimum,
                memoryMax,
                route.supportsManualSensor,
            )

            nextCaptureId += 1L
            val captureId = nextCaptureId
            val token = generation
            activeCaptureId = captureId
            captureGeneration = token
            captureExpected = plan.frameCount
            captureMinimum = minimum.coerceAtMost(plan.frameCount)
            captureCompletedCallbacks = 0
            captureFailedCallbacks = 0
            capturePausedRawPreview = false
            captureOrientation = orientation
            pendingImages.clear()
            pendingResults.clear()
            captured.clear()
            mainHandler.post {
                if (token == generation) listener.onCaptureStarted(plan.frameCount)
            }

            val characteristics = currentCharacteristics ?: return@post abortCapture("Missing camera characteristics")
            val exposureRange: Range<Long>? = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoRange: Range<Int>? = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val baseExposure = latestExposureNs
            val baseIso = latestIso
            val manualPlan = route.supportsManualSensor && baseExposure != null && baseIso != null && exposureRange != null && isoRange != null
            val plannedExposureNs = ArrayList<Long>(plan.frameCount)

            val requests = plan.evOffsets.map { ev ->
                currentDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    if (previewMode == PreviewMode.API) previewSurface?.let(::addTarget)
                    if (manualPlan) {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        val scaled = (baseExposure!! * 2.0.pow(ev.toDouble())).toLong()
                            .coerceIn(exposureRange!!.lower, exposureRange.upper)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, scaled)
                        set(CaptureRequest.SENSOR_SENSITIVITY, baseIso!!.coerceIn(isoRange!!.lower, isoRange.upper))
                        plannedExposureNs += scaled
                    } else {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        plannedExposureNs += baseExposure ?: 10_000_000L
                    }
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    setTag(BurstRequestTag(token, captureId))
                }.build()
            }

            if (previewMode == PreviewMode.RAW) {
                capturePausedRawPreview = runCatching {
                    currentSession.stopRepeating()
                    true
                }.getOrDefault(false)
            }

            val submitted = runCatching {
                currentSession.captureBurst(requests, burstCallback, cameraHandler)
            }.onFailure {
                abortCapture("RAW burst failed", it)
            }.isSuccess
            if (!submitted) return@post

            val timeoutMs = RawBurstTimeoutPolicy.timeoutMs(plannedExposureNs, plan.frameCount)
            cameraHandler.postDelayed({
                if (isCaptureActive(captureId, token)) handleCaptureTimeout()
            }, timeoutMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRouteCandidates(lens: CanonicalLens, routes: Iterator<CameraRoute>, token: Long) {
        if (token != generation) return
        if (!routes.hasNext()) {
            reportError("No usable route could be opened for ${lens.label}")
            return
        }
        val route = routes.next()
        if (previewMode == PreviewMode.RAW && !route.supportsRaw) {
            openRouteCandidates(lens, routes, token)
            return
        }
        runCatching {
            manager.openCamera(route.openId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraHandler.post {
                        if (token != generation) {
                            camera.close()
                            return@post
                        }
                        device = camera
                        configureSession(lens, route, routes, token)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraHandler.post {
                        camera.close()
                        if (device === camera) device = null
                        if (token == generation) openRouteCandidates(lens, routes, token)
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraHandler.post {
                        camera.close()
                        if (device === camera) device = null
                        if (token == generation) openRouteCandidates(lens, routes, token)
                    }
                }
            }, cameraHandler)
        }.onFailure { openRouteCandidates(lens, routes, token) }
    }

    private fun configureSession(
        lens: CanonicalLens,
        route: CameraRoute,
        remainingRoutes: Iterator<CameraRoute>,
        token: Long,
    ) {
        val camera = device ?: return
        val texture = textureView ?: return
        val host = texture.parent as? View
        val viewWidth = host?.width?.takeIf { it > 0 } ?: texture.width
        val viewHeight = host?.height?.takeIf { it > 0 } ?: texture.height
        val sensorRatio = route.rawSizes.firstOrNull()?.let { it.width.toFloat() / it.height }
            ?: route.previewSizes.firstOrNull()?.let { it.width.toFloat() / it.height }
        val previewSize = PreviewGeometry.chooseSize(route.previewSizes, viewWidth, viewHeight, sensorRatio, aspectMode)
        currentPreviewSize = previewSize
        val surfaceTexture: SurfaceTexture = texture.surfaceTexture ?: run {
            reportError("Preview surface is unavailable")
            return
        }
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(surfaceTexture)

        val rawSize = route.rawSizes.firstOrNull()
        currentRawSize = rawSize
        rawReader = rawSize?.let { size ->
            ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 4).apply {
                setOnImageAvailableListener(rawImageListener, cameraHandler)
            }
        }
        currentCharacteristics = runCatching { manager.getCameraCharacteristics(route.openId) }.getOrNull()

        val surfaces = mutableListOf<Surface>()
        if (previewMode == PreviewMode.API) previewSurface?.let(surfaces::add)
        rawReader?.surface?.let(surfaces::add)
        if (surfaces.isEmpty()) {
            reportError("No preview or RAW surface is available")
            return
        }

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                cameraHandler.post {
                    if (token != generation || device !== camera) {
                        configured.close()
                        return@post
                    }
                    session = configured
                    activeRoute = route
                    startRepeating(configured, camera)
                    mainHandler.post {
                        if (token != generation) return@post
                        if (previewMode == PreviewMode.API) {
                            PreviewGeometry.applyTextureTransform(
                                texture,
                                previewSize,
                                route.sensorOrientation,
                                displayRotation,
                                route.facing,
                                mirrorFront,
                            )
                        }
                        listener.onRouteOpened(lens, route)
                    }
                }
            }

            override fun onConfigureFailed(failed: CameraCaptureSession) {
                cameraHandler.post {
                    failed.close()
                    closeSessionPiecesForRetry()
                    if (token == generation) openRouteCandidates(lens, remainingRoutes, token)
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= 28 && route.physicalId != null) {
                val outputs = surfaces.map { surface ->
                    OutputConfiguration(surface).apply { setPhysicalCameraId(route.physicalId) }
                }
                camera.createCaptureSession(
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, sessionExecutor, callback),
                )
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(surfaces, callback, cameraHandler)
            }
        } catch (_: Throwable) {
            closeSessionPiecesForRetry()
            openRouteCandidates(lens, remainingRoutes, token)
        }
    }

    private fun startRepeating(configured: CameraCaptureSession, camera: CameraDevice) {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        when (previewMode) {
            PreviewMode.API -> previewSurface?.let(builder::addTarget)
            PreviewMode.RAW -> rawReader?.surface?.let(builder::addTarget)
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        configured.setRepeatingRequest(builder.build(), previewCaptureCallback, cameraHandler)
    }

    private val previewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (session !== this@Camera2Controller.session) return
            val token = generation
            latestExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            latestIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val focal = result.get(CaptureResult.LENS_FOCAL_LENGTH)
            val exposure = latestExposureNs
            val iso = latestIso
            mainHandler.post {
                if (token == generation) listener.onPreviewCaptureState(exposure, iso, focal)
            }
        }
    }

    private val burstCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (session !== this@Camera2Controller.session) return
            if (activeBurstTag(request) == null) return
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (timestamp == null) {
                captureFailedCallbacks += 1
                maybeFinishCapture()
                return
            }
            captureCompletedCallbacks += 1
            pendingResults[timestamp] = result
            while (pendingResults.size > captureExpected + 4) pendingResults.pollFirstEntry()
            tryPairFromResult(timestamp)
            maybeFinishCapture()
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: android.hardware.camera2.CaptureFailure,
        ) {
            if (session !== this@Camera2Controller.session) return
            if (activeBurstTag(request) == null) return
            captureFailedCallbacks += 1
            maybeFinishCapture()
        }
    }

    private val rawImageListener = ImageReader.OnImageAvailableListener { reader ->
        if (captureExpected > 0) {
            while (true) {
                val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: break
                try {
                    val timestamp = image.timestamp
                    pendingImages[timestamp] = packRaw(image)
                    while (pendingImages.size > captureExpected + 4) pendingImages.pollFirstEntry()
                    tryPairFromImage(timestamp)
                    if (previewMode == PreviewMode.RAW) renderRawPreview(image)
                } finally {
                    image.close()
                }
            }
        } else if (previewMode == PreviewMode.RAW) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@OnImageAvailableListener
            try {
                renderRawPreview(image)
            } finally {
                image.close()
            }
        } else {
            while (true) {
                val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: break
                image.close()
            }
        }
    }

    private fun activeBurstTag(request: CaptureRequest): BurstRequestTag? {
        val tag = request.tag as? BurstRequestTag ?: return null
        return tag.takeIf { isCaptureActive(it.captureId, it.generation) }
    }

    private fun isCaptureActive(captureId: Long, token: Long): Boolean =
        captureExpected > 0 &&
            activeCaptureId == captureId &&
            captureGeneration == token &&
            generation == token

    private fun tryPairFromImage(imageTimestamp: Long) {
        if (!pendingImages.containsKey(imageTimestamp)) return
        val resultTimestamp = RawTimestampPairingPolicy.match(imageTimestamp, pendingResults.keys) ?: return
        consumePair(imageTimestamp, resultTimestamp)
    }

    private fun tryPairFromResult(resultTimestamp: Long) {
        if (!pendingResults.containsKey(resultTimestamp)) return
        val imageTimestamp = RawTimestampPairingPolicy.match(resultTimestamp, pendingImages.keys) ?: return
        consumePair(imageTimestamp, resultTimestamp)
    }

    private fun consumePair(imageTimestamp: Long, resultTimestamp: Long) {
        val image = pendingImages.remove(imageTimestamp) ?: return
        val result = pendingResults.remove(resultTimestamp) ?: return
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: latestExposureNs ?: 10_000_000L
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: latestIso ?: 100
        captured += CapturedRaw(resultTimestamp, image, result, exposure, iso)
        maybeFinishCapture()
    }

    private fun maybeFinishCapture() {
        if (captureExpected <= 0) return
        if (captured.size >= captureExpected) {
            finishCapture()
            return
        }
        val terminalCallbacks = captureCompletedCallbacks + captureFailedCallbacks
        if (terminalCallbacks < captureExpected) return
        if (captured.size < captureCompletedCallbacks) return

        if (captured.size >= captureMinimum) {
            finishCapture()
        } else {
            abortCapture("RAW burst produced only ${captured.size} usable frames; ${captureMinimum} required")
        }
    }

    private fun handleCaptureTimeout() {
        if (captureExpected <= 0) return
        if (captured.size >= captureMinimum) {
            finishCapture()
        } else {
            abortCapture("RAW burst timed out with ${captured.size} usable frames; ${captureMinimum} required")
        }
    }

    private fun finishCapture() {
        val frames = captured.toList().sortedBy { it.timestamp }
        if (frames.isEmpty()) {
            abortCapture("Capture finished without usable RAW frames")
            return
        }
        val route = activeRoute ?: return abortCapture("Active route disappeared during capture")
        val size = currentRawSize ?: return abortCapture("RAW size disappeared during capture")
        val characteristics = currentCharacteristics ?: return abortCapture("Camera characteristics disappeared during capture")
        val generationAtSubmit = captureGeneration
        val orientationAtSubmit = captureOrientation
        val shouldResumeRawPreview = capturePausedRawPreview

        clearCaptureState()
        if (shouldResumeRawPreview) resumeRawPreview()

        processing.execute {
            try {
                if (generationAtSubmit != generation) return@execute
                val outcome = NativeRawMerger.mergePackedRaw(
                    frames = frames.map { it.bytes }.toTypedArray(),
                    width = size.width,
                    height = size.height,
                    exposureNs = frames.map { it.exposureNs }.toLongArray(),
                    iso = frames.map { it.iso }.toIntArray(),
                    blackLevels = route.blackLevels,
                    whiteLevel = route.whiteLevel ?: 65535,
                    cfa = route.cfa ?: 0,
                )
                if (generationAtSubmit != generation) return@execute
                val reference = frames[outcome.referenceIndex].result
                val uri = DngOutputWriter.write(
                    context,
                    characteristics,
                    reference,
                    size,
                    outcome.output,
                    orientationAtSubmit,
                    commitGuard = { publish -> commitIfGenerationCurrent(generationAtSubmit, publish) },
                )
                mainHandler.post {
                    if (generationAtSubmit == generation) {
                        listener.onCaptureCompleted(uri, outcome.acceptedFrames)
                    }
                }
            } catch (_: CancellationException) {
                // A lens/session generation superseded this background job before publication.
            } catch (t: Throwable) {
                mainHandler.post {
                    if (generationAtSubmit == generation) listener.onError("RAW merge/DNG write failed", t)
                }
            }
        }
    }

    private fun resumeRawPreview() {
        if (previewMode != PreviewMode.RAW) return
        val configured = session ?: return
        val camera = device ?: return
        runCatching { startRepeating(configured, camera) }
            .onFailure { reportError("RAW preview could not resume after capture", it) }
    }

    private fun renderRawPreview(image: Image) {
        val route = activeRoute ?: return
        val view = rawPreviewView ?: return
        val plane = image.planes.firstOrNull() ?: return
        val outWidth = 960.coerceAtMost(image.width.coerceAtLeast(1))
        val outHeight = ((outWidth.toLong() * image.height) / image.width.coerceAtLeast(1))
            .toInt()
            .coerceIn(240, 960)
        val raw = plane.buffer.duplicate()
        if (!raw.isDirect) return
        val pixels = runCatching {
            NativeRawMerger.renderRawPreview(
                raw,
                image.width,
                image.height,
                plane.rowStride,
                plane.pixelStride,
                outWidth,
                outHeight,
                route.blackLevels,
                route.whiteLevel ?: 65535,
                route.cfa ?: 0,
            )
        }.getOrNull() ?: return
        val bitmap = Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val token = generation
        mainHandler.post {
            if (token != generation) return@post
            view.setImageBitmap(bitmap)
            PreviewGeometry.applyViewTransform(
                view,
                Size(outWidth, outHeight),
                route.sensorOrientation,
                displayRotation,
                route.facing,
                mirrorFront,
            )
            listener.onRawPreviewFrame()
        }
    }

    private fun packRaw(image: Image): ByteBuffer {
        val plane = image.planes.first()
        val source = plane.buffer.duplicate()
        val output = ByteBuffer.allocateDirect(image.width * image.height * 2).order(ByteOrder.nativeOrder())
        for (y in 0 until image.height) {
            val row = y * plane.rowStride
            for (x in 0 until image.width) {
                val offset = row + x * plane.pixelStride
                output.put(source.get(offset))
                output.put(source.get(offset + 1))
            }
        }
        output.rewind()
        return output
    }

    private fun abortCapture(message: String, throwable: Throwable? = null) {
        val shouldResumeRawPreview = capturePausedRawPreview
        clearCaptureState()
        if (shouldResumeRawPreview) resumeRawPreview()
        reportError(message, throwable)
    }

    private fun clearCaptureState() {
        captureExpected = 0
        captureMinimum = 0
        captureCompletedCallbacks = 0
        captureFailedCallbacks = 0
        capturePausedRawPreview = false
        activeCaptureId = 0L
        captureGeneration = 0L
        pendingImages.clear()
        pendingResults.clear()
        captured.clear()
    }

    private fun closeSessionPiecesForRetry() {
        clearCaptureState()
        runCatching { session?.close() }
        session = null
        runCatching { rawReader?.close() }
        rawReader = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        runCatching { device?.close() }
        device = null
        activeRoute = null
        currentPreviewSize = null
        currentRawSize = null
        currentCharacteristics = null
    }

    private fun closeOwned() {
        clearCaptureState()
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        runCatching { session?.close() }
        session = null
        runCatching { rawReader?.close() }
        rawReader = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        runCatching { device?.close() }
        device = null
        activeRoute = null
        currentPreviewSize = null
        currentRawSize = null
        currentCharacteristics = null
        latestExposureNs = null
        latestIso = null
    }

    private fun advanceGeneration(): Long = synchronized(generationGuard) {
        generation += 1L
        generation
    }

    private fun commitIfGenerationCurrent(token: Long, action: () -> Unit): Boolean = synchronized(generationGuard) {
        if (token != generation) {
            false
        } else {
            action()
            true
        }
    }

    private fun reportError(message: String, throwable: Throwable? = null) {
        mainHandler.post { listener.onError(message, throwable) }
    }
}
