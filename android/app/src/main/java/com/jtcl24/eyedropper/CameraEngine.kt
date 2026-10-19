package com.jtcl24.eyedropper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class Exposure { IDLE, OK, ADJUSTING, BRIGHT, DARK }

data class UiState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val hasTorch: Boolean = false,
    val torchOn: Boolean = false,
    val variableTorch: Boolean = false,
    val lightLevel: Float = 0f,
    val exposure: Exposure = Exposure.IDLE,
    val calibrated: Boolean = false,
    val calibrating: Boolean = false,
    val rgb: Rgb? = null,
    val clipping: Boolean = false,
    val bright: Boolean = false,
    val reticlePx: Float = 0f,
    val hint: String = "",
)

class Frame(val bitmap: ImageBitmap, val id: Long)

/**
 * Camera2 pipeline: opens the rear camera with its torch, analyses every YUV frame
 * (average colour of the centre square + clipping stats), renders a display bitmap
 * for the glass UI, and runs the light/exposure auto-tuner and white calibration.
 *
 * Everything below runs on one camera thread, so no locking is needed.
 */
class CameraEngine(context: Context) {

    val state = MutableStateFlow(UiState())
    val frame = MutableStateFlow<Frame?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val manager = context.getSystemService(CameraManager::class.java)
    private val prefs = context.getSharedPreferences("eyedropper", Context.MODE_PRIVATE)
    private val thread = HandlerThread("eyedropper-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())

    @Volatile private var viewW = 0
    @Volatile private var viewH = 0
    fun setViewport(w: Int, h: Int) { viewW = w; viewH = h }

    // ---- camera objects ----
    private class Caps(
        val id: String,
        val orientation: Int,
        val hasFlash: Boolean,
        val torchMax: Int,
        val manualSensor: Boolean,
        val manualPost: Boolean,
        val expRange: Range<Long>?,
        val isoRange: Range<Int>?,
        val evRange: Range<Int>,
        val evStep: Float,
        val awbLock: Boolean,
        val afModes: IntArray,
        val size: Size,
    ) {
        val variableTorch get() = torchMax > 1 && Build.VERSION.SDK_INT >= 35
    }

    private var caps: Caps? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var lastResult: TotalCaptureResult? = null
    private var tuneJob: Job? = null
    private var calJob: Job? = null

    // ---- control parameters ----
    private var torchOn = false
    private var torchLevel = 1
    private var manualExposure = false
    private var expNs = 10_000_000L
    private var iso = 100
    private var ev = 0
    private var wbGains: RggbChannelVector? = null
    private var wbTransform: ColorSpaceTransform? = null
    private var awbLocked = false

    // ---- analysis state ----
    private var rawLin = FloatArray(3)
    private var clip = 0f
    private var peak = 0f
    private var out: FloatArray? = null
    private var bright = false
    private var reticlePx = 0f
    private var exposure = Exposure.IDLE
    private var okStreak = 0
    private var applying = false
    private var calibrating = false
    private var lastPush = 0L
    private var hint = ""
    private val collectors = mutableListOf<(FloatArray, Float) -> Unit>()

    private var yArr = ByteArray(0); private var uArr = ByteArray(0); private var vArr = ByteArray(0)
    private var yRow = 0; private var uRow = 0; private var vRow = 0; private var uPix = 1; private var vPix = 1
    private var pixels = IntArray(0)
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var bmpIndex = 0
    private var frameId = 0L

    // ---- calibration ----
    private class Calibration(
        val gains: FloatArray,
        val mode: String,          // "manual" | "ev" | "none"
        val expNs: Long, val iso: Int, val torchLevel: Int, val ev: Int,
        val wb: FloatArray?, val xform: IntArray?,
    )

    private var cal: Calibration? = loadCal()

    init {
        hint = if (cal != null) "Saved calibration found. Tap Start." else "Tap Start, then hold the lens 1–3 cm above the surface."
        push(force = true)
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    fun start() = scope.launch {
        if (device != null || state.value.starting) return@launch
        state.update { it.copy(starting = true) }
        try {
            val c = pickCamera() ?: throw IllegalStateException("No rear camera found.")
            caps = c
            val dev = openCamera(c.id)
            device = dev
            val rdr = ImageReader.newInstance(c.size.width, c.size.height, ImageFormat.YUV_420_888, 3)
            rdr.setOnImageAvailableListener({ onImage(it) }, handler)
            reader = rdr
            session = createSession(dev, rdr)

            val saved = cal
            torchOn = c.hasFlash
            torchLevel = c.torchMax
            manualExposure = false
            ev = 0
            wbGains = null; wbTransform = null; awbLocked = false
            out = null
            submit()
            push(force = true)

            delay(900) // let AE/AWB settle with the torch on

            // Switch to manual exposure so brightness is measured, not normalised away by AE
            val er = c.expRange; val ir = c.isoRange
            if (c.manualSensor && er != null && ir != null) {
                if (saved != null && saved.mode == "manual") {
                    expNs = saved.expNs.coerceIn(er.lower, er.upper)
                    iso = saved.iso.coerceIn(ir.lower, ir.upper)
                    torchLevel = saved.torchLevel.coerceIn(1, c.torchMax)
                } else {
                    var e = lastResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
                    var s = lastResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 200
                    if (e > EXP_CAP) { s = (s.toLong() * e / EXP_CAP).toInt(); e = EXP_CAP }
                    expNs = e.coerceIn(er.lower, er.upper)
                    iso = s.coerceIn(ir.lower, ir.upper)
                }
                manualExposure = true
            } else if (saved != null && saved.mode == "ev") {
                ev = saved.ev.coerceIn(c.evRange.lower, c.evRange.upper)
            }

            // Fix white balance so AWB can't "neutralise" coloured surfaces
            if (c.manualPost && saved?.wb != null && saved.xform != null) {
                wbGains = RggbChannelVector(saved.wb[0], saved.wb[1], saved.wb[2], saved.wb[3])
                wbTransform = ColorSpaceTransform(saved.xform)
            } else {
                lockWhiteBalance()
            }
            submit()

            hint = when {
                saved != null && !c.manualPost -> "Re-calibrate: this camera can't restore white balance after reopening."
                saved != null -> "Using saved calibration. Re-calibrate if the lighting has changed."
                else -> "Place the lens over a white reference and tap Calibrate."
            }
            state.update { it.copy(starting = false, running = true) }
            push(force = true)
            if (!c.hasFlash) messages.tryEmit("This camera has no flashlight")

            tuneJob = launch {
                while (isActive) { delay(260); tune() }
            }
        } catch (e: Exception) {
            closeCamera()
            hint = e.message ?: "Could not start the camera."
            state.update { it.copy(starting = false, running = false) }
            push(force = true)
        }
    }

    fun stop() = scope.launch {
        if (device == null) return@launch
        closeCamera()
        hint = "Camera paused. Tap Start to resume."
        push(force = true)
    }

    fun release() {
        scope.launch { closeCamera() }.invokeOnCompletion {
            scope.cancel()
            thread.quitSafely()
        }
    }

    fun permissionDenied() {
        hint = "Camera permission denied."
        push(force = true)
    }

    private fun closeCamera() {
        tuneJob?.cancel(); tuneJob = null
        calJob?.cancel(); calJob = null
        collectors.clear()
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null
        torchOn = false
        exposure = Exposure.IDLE
        applying = false
        calibrating = false
        frame.value = null
        state.update { it.copy(running = false, starting = false) }
    }

    // =====================================================================
    // Camera setup
    // =====================================================================

    private fun pickCamera(): Caps? {
        var chosen: String? = null
        for (id in manager.cameraIdList) {
            val ch = manager.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (chosen == null) chosen = id
            if (flash) { chosen = id; break }
        }
        val id = chosen ?: return null
        val ch = manager.getCameraCharacteristics(id)
        val capsList = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val torchMax = if (Build.VERSION.SDK_INT >= 35) ch.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1 else 1
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val target = 1280f * 960f
        val size = sizes.filter { it.width <= 1920 && it.height <= 1440 }.ifEmpty { sizes.toList() }.minBy {
            val aspect = max(it.width, it.height).toFloat() / min(it.width, it.height)
            abs(aspect - 4f / 3f) * 4f + abs(it.width * it.height - target) / target
        }
        return Caps(
            id = id,
            orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            torchMax = max(1, torchMax),
            manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capsList,
            manualPost = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in capsList,
            expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
            evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 3f),
            awbLock = ch.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
            afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0),
            size = size,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { cont.resume(d) }
            override fun onDisconnected(d: CameraDevice) {
                d.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera is in use by another app."))
                else scope.launch { if (device === d) { closeCamera(); hint = "Camera disconnected. Tap Start."; push(force = true) } }
            }
            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error ($error)."))
                else scope.launch { if (device === d) { closeCamera(); hint = "Camera error. Tap Start."; push(force = true) } }
            }
        }, handler)
    }

    private suspend fun createSession(dev: CameraDevice, rdr: ImageReader): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val cfg = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(rdr.surface)),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { cont.resume(s) }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Could not configure the camera."))
                    }
                },
            )
            dev.createCaptureSession(cfg)
        }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            lastResult = result
        }
    }

    /** Push the current control parameters as the repeating request. */
    private fun submit() {
        val s = session ?: return
        val dev = device ?: return
        val c = caps ?: return
        val rdr = reader ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(rdr.surface)
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in c.afModes) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            if (manualExposure) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(expNs, FRAME_NS))
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
            }
            b.set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            if (torchOn && c.variableTorch && Build.VERSION.SDK_INT >= 35) {
                b.set(CaptureRequest.FLASH_STRENGTH_LEVEL, torchLevel)
            }
            val g = wbGains
            val t = wbTransform
            if (g != null && t != null) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, g)
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, t)
            } else {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                if (c.awbLock) b.set(CaptureRequest.CONTROL_AWB_LOCK, awbLocked)
            }
            s.setRepeatingRequest(b.build(), captureCallback, handler)
        } catch (e: Exception) {
            // Session may be closing; the next submit will retry.
        }
    }

    /** Freeze the white balance the camera is currently using. */
    private fun lockWhiteBalance() {
        val c = caps ?: return
        val r = lastResult
        val g = r?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val t = r?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        if (c.manualPost && g != null && t != null) {
            wbGains = g; wbTransform = t
        } else {
            wbGains = null; wbTransform = null
            awbLocked = c.awbLock
        }
    }

    // =====================================================================
    // Frame analysis
    // =====================================================================

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            if (device != null) analyze(img)
        } finally {
            img.close()
        }
    }

    private fun copyPlanes(img: Image) {
        val p = img.planes
        val yb = p[0].buffer; val ub = p[1].buffer; val vb = p[2].buffer
        if (yArr.size < yb.remaining()) yArr = ByteArray(yb.remaining())
        if (uArr.size < ub.remaining()) uArr = ByteArray(ub.remaining())
        if (vArr.size < vb.remaining()) vArr = ByteArray(vb.remaining())
        yb.get(yArr, 0, yb.remaining()); ub.get(uArr, 0, ub.remaining()); vb.get(vArr, 0, vb.remaining())
        yRow = p[0].rowStride
        uRow = p[1].rowStride; uPix = p[1].pixelStride
        vRow = p[2].rowStride; vPix = p[2].pixelStride
    }

    /** Full-range BT.601 YUV → packed 0xRRGGBB at a sensor pixel. */
    private fun rgbAt(x: Int, y: Int): Int {
        val yy = yArr[y * yRow + x].toInt() and 0xFF
        val cx = x shr 1; val cy = y shr 1
        val u = (uArr[cy * uRow + cx * uPix].toInt() and 0xFF) - 128
        val v = (vArr[cy * vRow + cx * vPix].toInt() and 0xFF) - 128
        val r = (yy + ((1436 * v) shr 10)).coerceIn(0, 255)
        val g = (yy - ((352 * u + 731 * v) shr 10)).coerceIn(0, 255)
        val b = (yy + ((1815 * u) shr 10)).coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun analyze(img: Image) {
        val c = caps ?: return
        copyPlanes(img)
        val w = img.width; val h = img.height
        val s2l = ColorMath.S2L

        // ---- 1. average colour of the centre square (linear light) ----
        val side = (min(w, h) * SAMPLE_FRACTION).toInt()
        val x0 = (w - side) / 2; val y0 = (h - side) / 2
        val step = max(1, side / 64)
        var sr = 0f; var sg = 0f; var sb = 0f
        var n = 0; var clipped = 0
        var y = y0
        while (y < y0 + side) {
            var x = x0
            while (x < x0 + side) {
                val p = rgbAt(x, y)
                val r = p shr 16; val g = (p shr 8) and 255; val b = p and 255
                sr += s2l[r]; sg += s2l[g]; sb += s2l[b]
                if (r >= CLIP_LEVEL || g >= CLIP_LEVEL || b >= CLIP_LEVEL) clipped++
                n++
                x += step
            }
            y += step
        }
        val raw = floatArrayOf(sr / n, sg / n, sb / n)
        rawLin = raw
        clip = clipped.toFloat() / n
        peak = ColorMath.lin2srgb(max(raw[0], max(raw[1], raw[2]))) * 255f
        for (col in collectors.toList()) col(raw, clip)

        // Corrected colour = raw × white-balance gains × exposure change since calibration
        val gains = cal?.gains ?: floatArrayOf(1f, 1f, 1f)
        val k = exposureRatio()
        val corr = FloatArray(3) { raw[it] * gains[it] * k }
        val prev = out
        out = if (prev == null) corr else FloatArray(3) { prev[it] + (corr[it] - prev[it]) * 0.22f }

        // ---- 2. display bitmap: the part of the frame visible on screen ----
        renderDisplay(img.width, img.height, c.orientation)

        push()
    }

    /**
     * Same layout as the web app: the frame is "cover"-scaled into a box 5/3 of the
     * screen tall, centred on the middle of the top third, so the sampled centre of
     * the frame sits in the clear zone. Only the on-screen crop is converted.
     */
    private fun renderDisplay(w: Int, h: Int, orientation: Int) {
        val sw = viewW.toFloat(); val sh = viewH.toFloat()
        if (sw <= 0f || sh <= 0f) return
        val rotated = orientation == 90 || orientation == 270
        val pw = if (rotated) h else w
        val ph = if (rotated) w else h
        val scale = max(sw / pw, sh * 5f / 3f / ph)
        reticlePx = SAMPLE_FRACTION * min(pw, ph) * scale

        val cropW = sw / scale; val cropH = sh / scale
        val cx0 = pw / 2f - cropW / 2f
        val cy0 = ph / 2f - (sh / 6f) / scale
        val k = max(1f, ceil(cropW / 360f))
        val bw = max(1, (cropW / k).toInt()); val bh = max(1, (cropH / k).toInt())

        if (pixels.size != bw * bh) pixels = IntArray(bw * bh)
        bmpIndex = (bmpIndex + 1) % bitmaps.size
        var bmp = bitmaps[bmpIndex]
        if (bmp == null || bmp.width != bw || bmp.height != bh) {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bitmaps[bmpIndex] = bmp
        }

        val s2l = ColorMath.S2L
        var lum = 0f; var ln = 0
        for (j in 0 until bh) {
            val py = (cy0 + j * k).toInt().coerceIn(0, ph - 1)
            val row = j * bw
            for (i in 0 until bw) {
                val px = (cx0 + i * k).toInt().coerceIn(0, pw - 1)
                val sx: Int; val sy: Int
                when (orientation) {
                    90 -> { sx = py; sy = h - 1 - px }
                    270 -> { sx = w - 1 - py; sy = px }
                    180 -> { sx = w - 1 - px; sy = h - 1 - py }
                    else -> { sx = px; sy = py }
                }
                val p = rgbAt(sx, sy)
                pixels[row + i] = p or OPAQUE
                if ((i and 7) == 0 && (j and 7) == 0) {
                    lum += 0.2126f * s2l[p shr 16] + 0.7152f * s2l[(p shr 8) and 255] + 0.0722f * s2l[p and 255]
                    ln++
                }
            }
        }
        bmp!!.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        frame.value = Frame(bmp.asImageBitmap(), ++frameId)
        if (ln > 0) {
            val l = lum / ln
            bright = if (bright) l > 0.38f else l > 0.42f
        }
    }

    private fun exposureRatio(): Float {
        val c = cal ?: return 1f
        val cp = caps ?: return 1f
        return when {
            manualExposure && c.mode == "manual" -> {
                val tNow = if (cp.variableTorch && torchOn) torchLevel.toDouble() else 1.0
                val tCal = if (cp.variableTorch) c.torchLevel.toDouble() else 1.0
                ((c.expNs.toDouble() * c.iso * tCal) / (expNs.toDouble() * iso * tNow)).toFloat()
            }
            !manualExposure && c.mode == "ev" -> 2f.pow((c.ev - ev) * cp.evStep)
            else -> 1f
        }
    }

    // =====================================================================
    // Auto light / exposure tuner
    // =====================================================================

    private fun tune() {
        if (applying || session == null) return
        var dir = 0; var f = 1f
        if (clip > 0.01f || peak > 242f) { dir = -1; f = if (clip > 0.15f || peak > 252f) 0.55f else 0.8f }
        else if (peak < 150f && clip < 0.002f) { dir = 1; f = if (peak < 70f) 1.8f else 1.18f }

        if (dir == 0) {
            exposure = Exposure.OK; okStreak++
            push(force = true); return
        }
        okStreak = 0
        val moved = adjust(f)
        exposure = if (moved) Exposure.ADJUSTING else if (dir < 0) Exposure.BRIGHT else Exposure.DARK
        if (moved) {
            submit()
            applying = true
            scope.launch { delay(160); applying = false }
        }
        push(force = true)
    }

    /**
     * Scale the light reaching the sensor by roughly [f].
     * Order: torch strength first (the variable flashlight), then exposure time, then ISO.
     */
    private fun adjust(f: Float): Boolean {
        val c = caps ?: return false
        val er = c.expRange; val ir = c.isoRange
        if (manualExposure && er != null && ir != null) {
            val variable = torchOn && c.variableTorch
            val expMax = min(er.upper, EXP_CAP)
            val isoMax = min(ir.upper, ISO_CAP)
            if (f < 1f) {
                if (variable && torchLevel > 1) {
                    torchLevel = min(torchLevel - 1, floor(torchLevel * f).toInt()).coerceAtLeast(1); return true
                }
                if (expNs > er.lower) { expNs = max(er.lower, (expNs * f).toLong()); return true }
                if (iso > ir.lower) { iso = max(ir.lower, (iso * f).toInt()); return true }
            } else {
                if (variable && torchLevel < c.torchMax) {
                    torchLevel = max(torchLevel + 1, ceil(torchLevel * f).toInt()).coerceAtMost(c.torchMax); return true
                }
                if (expNs < expMax) { expNs = min(expMax, (expNs * f).toLong() + 1); return true }
                if (iso < isoMax) { iso = min(isoMax, (iso * f).toInt() + 1); return true }
            }
            return false
        }
        if (c.evRange.lower == c.evRange.upper) return false
        var steps = (ln(f) / ln(2f) / c.evStep).roundToInt()
        if (steps == 0) steps = if (f > 1f) 1 else -1
        val next = (ev + steps).coerceIn(c.evRange.lower, c.evRange.upper)
        if (next == ev) return false
        ev = next
        return true
    }

    private fun lightLevel(): Float {
        val c = caps ?: return 0f
        if (c.hasFlash && !torchOn) return 0f
        if (c.variableTorch) return torchLevel.toFloat() / c.torchMax
        val er = c.expRange; val ir = c.isoRange
        if (manualExposure && er != null && ir != null) {
            val lo = ln(er.lower.toDouble() * ir.lower)
            val hi = ln(min(er.upper, EXP_CAP).toDouble() * min(ir.upper, ISO_CAP))
            return ((ln(expNs.toDouble() * iso) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
        }
        if (c.evRange.upper > c.evRange.lower) return (ev - c.evRange.lower).toFloat() / (c.evRange.upper - c.evRange.lower)
        return if (torchOn) 1f else 0f
    }

    // =====================================================================
    // Controls
    // =====================================================================

    fun setTorch(on: Boolean) = scope.launch {
        val c = caps ?: return@launch
        if (!c.hasFlash || device == null) return@launch
        torchOn = on
        submit()
        push(force = true)
        if (!on && cal != null) messages.tryEmit("Calibration assumes the light is on")
    }

    fun clearCalibration() = scope.launch {
        cal = null
        prefs.edit().remove(KEY_CAL).apply()
        out = null
        hint = "Calibration cleared. Showing raw camera color."
        push(force = true)
    }

    fun calibrate() = scope.launch {
        val c = caps ?: return@launch
        if (session == null || calibrating) return@launch
        calJob = coroutineContext.job
        calibrating = true
        hint = "Hold steady over the white reference…"
        push(force = true)
        try {
            if (c.hasFlash && !torchOn) { torchOn = true; submit() }

            // Let AWB neutralise the white reference while the tuner finds a bright, unclipped exposure
            wbGains = null; wbTransform = null; awbLocked = false
            submit()
            val t0 = SystemClock.elapsedRealtime()
            okStreak = 0
            while (SystemClock.elapsedRealtime() - t0 < 5000) {
                delay(100)
                val elapsed = SystemClock.elapsedRealtime() - t0
                val awb = lastResult?.get(CaptureResult.CONTROL_AWB_STATE)
                val awbDone = awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED || elapsed > 1500
                if (awbDone && okStreak >= 3 && elapsed > 600) break
            }
            lockWhiteBalance()
            submit()
            delay(350)
            while (applying) delay(30)

            val (avg, clipAvg) = collectFrames(24)
            val mx = max(avg[0], max(avg[1], avg[2]))
            val mn = min(avg[0], min(avg[1], avg[2]))
            if (clipAvg > 0.05f) throw CalibrationError("Overexposed — lift the phone a little and retry.")
            if (mx < 0.04f) throw CalibrationError("Too dark — cover the reference with the light on.")
            if (mn / mx < 0.35f) throw CalibrationError("That doesn’t look white. Use a white reference.")

            val g = wbGains
            val t = wbTransform
            val newCal = Calibration(
                gains = FloatArray(3) { 1f / avg[it] }, // white → (1,1,1) → rgb(255,255,255)
                mode = when {
                    manualExposure -> "manual"
                    c.evRange.upper > c.evRange.lower -> "ev"
                    else -> "none"
                },
                expNs = expNs, iso = iso, torchLevel = torchLevel, ev = ev,
                wb = g?.let { floatArrayOf(it.red, it.greenEven, it.greenOdd, it.blue) },
                xform = t?.let { IntArray(18).also { arr -> t.copyElements(arr, 0) } },
            )
            cal = newCal
            saveCal(newCal)
            out = null
            messages.tryEmit("Calibrated to white ✓")
            hint = when {
                !manualExposure -> "Calibrated. This camera can’t lock exposure, so brightness may drift."
                g == null -> "Calibrated. Re-calibrate each time you reopen the app."
                else -> "Calibrated. Readings are relative to your white reference."
            }
        } catch (e: CalibrationError) {
            messages.tryEmit(e.message ?: "Calibration failed")
            hint = e.message ?: "Calibration failed"
        } finally {
            calibrating = false
            push(force = true)
        }
    }

    private class CalibrationError(msg: String) : Exception(msg)

    private suspend fun collectFrames(count: Int): Pair<FloatArray, Float> = suspendCancellableCoroutine { cont ->
        val sum = FloatArray(3)
        var seen = 0
        var clipSum = 0f
        lateinit var collector: (FloatArray, Float) -> Unit
        collector = { raw, cl ->
            sum[0] += raw[0]; sum[1] += raw[1]; sum[2] += raw[2]
            clipSum += cl; seen++
            if (seen >= count) {
                collectors.remove(collector)
                if (cont.isActive) cont.resume(FloatArray(3) { sum[it] / seen } to clipSum / seen)
            }
        }
        collectors.add(collector)
        cont.invokeOnCancellation { handler.post { collectors.remove(collector) } }
    }

    // =====================================================================
    // State publishing & persistence
    // =====================================================================

    private fun push(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPush < 80) return
        lastPush = now
        val c = caps
        val o = out
        val running = device != null && session != null
        state.update {
            it.copy(
                hasTorch = c?.hasFlash == true,
                torchOn = torchOn,
                variableTorch = c?.variableTorch == true,
                lightLevel = lightLevel(),
                exposure = if (running) exposure else Exposure.IDLE,
                calibrated = cal != null,
                calibrating = calibrating,
                rgb = if (running && o != null) Rgb(ColorMath.to8(o[0]), ColorMath.to8(o[1]), ColorMath.to8(o[2])) else it.rgb,
                clipping = running && clip > 0.02f && !calibrating,
                bright = running && bright,
                reticlePx = reticlePx,
                hint = hint,
            )
        }
    }

    private fun saveCal(c: Calibration) {
        val j = JSONObject()
            .put("gains", JSONArray(c.gains.map { it.toDouble() }))
            .put("mode", c.mode).put("expNs", c.expNs).put("iso", c.iso)
            .put("torchLevel", c.torchLevel).put("ev", c.ev)
        c.wb?.let { j.put("wb", JSONArray(it.map { v -> v.toDouble() })) }
        c.xform?.let { j.put("xform", JSONArray(it.toList())) }
        prefs.edit().putString(KEY_CAL, j.toString()).apply()
    }

    private fun loadCal(): Calibration? = try {
        prefs.getString(KEY_CAL, null)?.let { s ->
            val j = JSONObject(s)
            val g = j.getJSONArray("gains")
            val gains = FloatArray(3) { g.getDouble(it).toFloat() }
            if (gains.any { it <= 0f || !it.isFinite() }) null
            else Calibration(
                gains = gains,
                mode = j.optString("mode", "none"),
                expNs = j.optLong("expNs"), iso = j.optInt("iso"),
                torchLevel = j.optInt("torchLevel", 1), ev = j.optInt("ev"),
                wb = j.optJSONArray("wb")?.let { a -> FloatArray(4) { a.getDouble(it).toFloat() } },
                xform = j.optJSONArray("xform")?.let { a -> IntArray(18) { a.getInt(it) } },
            )
        }
    } catch (_: Exception) { null }

    companion object {
        private const val SAMPLE_FRACTION = 0.18f // side of the sampled square vs. the frame's short side
        private const val CLIP_LEVEL = 250
        private const val EXP_CAP = 33_333_333L   // keep ≥30 fps
        private const val FRAME_NS = 33_333_333L
        private const val ISO_CAP = 1600
        private const val OPAQUE = 0xFF000000.toInt()
        private const val KEY_CAL = "calibration"
    }
}
