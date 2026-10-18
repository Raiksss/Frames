package ee.raiks.frames

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private enum class Ctl { ISO, SHUTTER, EV, FOCUS, WB, LOOK }

    // ---- Views ----
    private lateinit var texture: TextureView
    private lateinit var overlay: OverlayView
    private lateinit var flash: View
    private lateinit var panel: View
    private lateinit var panelTitle: TextView
    private lateinit var panelValue: TextView
    private lateinit var panelAuto: TextView
    private lateinit var seek: SeekBar
    private lateinit var lookScroll: View
    private lateinit var lookRow: LinearLayout
    private var lookPreview: Bitmap? = null
    private lateinit var thumb: ImageView
    private lateinit var shutterBtn: View
    private lateinit var modeText: TextView
    private lateinit var gridText: TextView
    private lateinit var chips: Map<Ctl, TextView>
    private lateinit var orientationListener: OrientationEventListener

    // ---- Camera ----
    private val cameraManager by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val saver = Executors.newSingleThreadExecutor()
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private lateinit var chars: CameraCharacteristics
    private var cameraId = ""
    private var previewSize = Size(1440, 1080)
    private var jpegSize = Size(4000, 3000)

    // ---- Capabilities ----
    private var manualSensor = false
    private var manualFocusSupported = false
    private var isoRange: Range<Int> = Range(100, 800)
    private var expRange: Range<Long> = Range(100_000L, 100_000_000L)
    private var evRange: Range<Int> = Range(0, 0)
    private var evStep = 0f
    private var minFocus = 0f
    private var sensorOrientation = 90
    private var isoList = listOf(100)
    private var shutterList = listOf(8_000_000L)
    private var wbModes = listOf(ALL_WB[0])

    // ---- User settings ----
    private var manual = false
    private var iso = 100
    private var exposureNs = 8_000_000L
    private var evIndex = 0
    private var focusAuto = true
    private var focusDiopters = 0f
    private var wbIndex = 0
    private var lookIndex = 0
    private var activeControl: Ctl? = null

    // ---- Live values from the auto exposure, shown in the chips ----
    @Volatile private var liveIso = 0
    @Volatile private var liveExp = 0L
    @Volatile private var liveFocus = 0f
    private var frameCount = 0
    @Volatile private var deviceOrientation = 0

    // ---- Capture pairing (JPEG + metadata) ----
    @Volatile private var capturing = false
    private var jpegDone = false
    private var pendingResult: TotalCaptureResult? = null
    private var pendingJpeg: ByteArray? = null
    private var pendingRotation = 0
    private var pendingLook = Looks.all[0]
    private var pendingStamp = ""
    private var lastUri: Uri? = null

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        texture = findViewById(R.id.texture)
        overlay = findViewById(R.id.overlay)
        flash = findViewById(R.id.flash)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panelTitle)
        panelValue = findViewById(R.id.panelValue)
        panelAuto = findViewById(R.id.panelAuto)
        seek = findViewById(R.id.seek)
        lookScroll = findViewById(R.id.lookScroll)
        lookRow = findViewById(R.id.lookRow)
        thumb = findViewById(R.id.thumb)
        shutterBtn = findViewById(R.id.shutter)
        modeText = findViewById(R.id.modeText)
        gridText = findViewById(R.id.gridText)
        chips = mapOf(
            Ctl.ISO to findViewById(R.id.chipIso),
            Ctl.SHUTTER to findViewById(R.id.chipShutter),
            Ctl.EV to findViewById(R.id.chipEv),
            Ctl.FOCUS to findViewById(R.id.chipFocus),
            Ctl.WB to findViewById(R.id.chipWb),
            Ctl.LOOK to findViewById(R.id.chipLook),
        )
        thumb.clipToOutline = true

        chips.forEach { (c, v) -> v.setOnClickListener { toggleControl(c) } }
        panelAuto.setOnClickListener { activeControl?.let { setAuto(it) } }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onSeek(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        shutterBtn.setOnClickListener { takePicture() }
        gridText.setOnClickListener { overlay.showGrid = !overlay.showGrid; refreshUi() }
        modeText.setOnClickListener {
            if (manual) setAuto(Ctl.ISO) else enterManual()
            refreshUi()
        }
        thumb.setOnClickListener {
            lastUri?.let {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }

        // Fit a 3:4 viewfinder into the available space.
        val frame = findViewById<View>(R.id.previewFrame)
        val box = findViewById<View>(R.id.previewBox)
        frame.post {
            var w = frame.width
            var h = w * 4 / 3
            if (h > frame.height) { h = frame.height; w = h * 3 / 4 }
            box.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o != ORIENTATION_UNKNOWN) deviceOrientation = ((o + 45) / 90 * 90) % 360
            }
        }
        applyLook()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        thread = HandlerThread("camera").also { it.start(); handler = Handler(it.looper) }
        orientationListener.enable()
        texture.surfaceTextureListener = surfaceListener
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM)
        } else maybeOpen()
    }

    override fun onPause() {
        closeCamera()
        orientationListener.disable()
        thread?.quitSafely()
        thread?.join()
        thread = null
        handler = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_CAM) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) maybeOpen()
            else toast("Camera permission is needed")
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = maybeOpen()
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    // =====================================================================
    // Camera setup
    // =====================================================================

    private fun maybeOpen() {
        if (device == null && handler != null && texture.isAvailable &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) openCamera()
    }

    private fun setupCharacteristics() {
        val ids = cameraManager.cameraIdList
        cameraId = ids.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.first()
        chars = cameraManager.getCameraCharacteristics(cameraId)

        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        manualFocusSupported = manualSensor && minFocus > 0f
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 800)
        expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 100_000_000L)
        evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

        isoList = ISO_STOPS.filter { isoRange.contains(it) }.ifEmpty { listOf(isoRange.lower, isoRange.upper) }
        shutterList = SHUTTER_STOPS.filter { expRange.contains(it) }.ifEmpty { listOf(expRange.lower, expRange.upper) }
        val awb = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?: intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
        wbModes = ALL_WB.filter { awb.contains(it.second) }.ifEmpty { listOf(ALL_WB[0]) }

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val jpegs = map.getOutputSizes(ImageFormat.JPEG)
        jpegSize = jpegs.filter { it.width * 3 == it.height * 4 }.maxByOrNull { it.width.toLong() * it.height }
            ?: jpegs.maxBy { it.width.toLong() * it.height }
        previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width * 3 == it.height * 4 && it.width <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height } ?: Size(1440, 1080)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            setupCharacteristics()
            refreshUi()
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; startSession() }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); device = null
                    runOnUiThread { toast("Camera error $error") }
                }
            }, handler)
        } catch (e: Exception) {
            toast("Couldn't open camera: ${e.message}")
        }
    }

    private fun startSession() {
        val d = device ?: return
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val ps = Surface(st).also { previewSurface = it }

        val jr = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        jr.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            img.close()
            pendingJpeg = bytes
            tryFinish()
        }, handler)
        jpegReader = jr

        val targets = listOf(ps, jr.surface)
        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; updatePreview() }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread { toast("Camera session failed") }
                }
            }, handler)
        } catch (e: Exception) {
            runOnUiThread { toast("Session error: ${e.message}") }
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        device?.close(); device = null
        jpegReader?.close(); jpegReader = null
        previewSurface = null
        capturing = false
        shutterBtn.isEnabled = true
    }

    // =====================================================================
    // Requests
    // =====================================================================

    private fun buildRequest(d: CameraDevice, template: Int, forPreview: Boolean): CaptureRequest.Builder {
        val b = d.createCaptureRequest(template)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        if (manual && manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            var e = exposureNs.coerceIn(expRange.lower, expRange.upper)
            var s = iso
            // Keep the viewfinder fluid for long exposures: shorten it and raise ISO to match brightness.
            if (forPreview && e > PREVIEW_MAX_EXP) {
                val factor = e.toDouble() / PREVIEW_MAX_EXP
                e = PREVIEW_MAX_EXP.coerceAtMost(expRange.upper)
                s = (s * factor).toInt().coerceIn(isoRange.lower, isoRange.upper)
            }
            b.set(CaptureRequest.SENSOR_SENSITIVITY, s)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (forPreview) max(e, 33_333_333L) else e)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        }

        if (!focusAuto && manualFocusSupported) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        b.set(CaptureRequest.CONTROL_AWB_MODE, wbModes[wbIndex.coerceIn(0, wbModes.size - 1)].second)
        return b
    }

    private fun updatePreview() {
        val d = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = buildRequest(d, CameraDevice.TEMPLATE_PREVIEW, true)
            b.addTarget(ps)
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (_: Exception) {}
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { liveIso = it }
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { liveExp = it }
            res.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { liveFocus = it }
            if (++frameCount % 15 == 0) runOnUiThread { refreshUi() }
        }
    }

    // =====================================================================
    // Capture & save
    // =====================================================================

    private fun takePicture() {
        val d = device ?: return
        val s = session ?: return
        val jr = jpegReader ?: return
        if (capturing) return
        capturing = true
        shutterBtn.isEnabled = false

        jpegDone = false
        pendingResult = null; pendingJpeg = null
        pendingRotation = (sensorOrientation + deviceOrientation) % 360
        pendingLook = Looks.all[lookIndex]
        pendingStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        try {
            val b = buildRequest(d, CameraDevice.TEMPLATE_STILL_CAPTURE, false)
            b.addTarget(jr.surface)
            b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())

            flash.alpha = 0.85f
            flash.animate().alpha(0f).setDuration(220).start()
            shutterBtn.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80)
                .withEndAction { shutterBtn.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
            if (manual && exposureNs >= 1_000_000_000L) toast("Exposing ${shutterLabel(exposureNs)} — hold still")

            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    pendingResult = result
                    tryFinish()
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    capturing = false
                    runOnUiThread { shutterBtn.isEnabled = true; toast("Capture failed") }
                }
            }, handler)
        } catch (e: Exception) {
            capturing = false
            shutterBtn.isEnabled = true
            toast("Capture error: ${e.message}")
        }
    }

    /** Runs on the camera thread; saves each file once its image and metadata have both arrived. */
    private fun tryFinish() {
        val res = pendingResult ?: return
        val stamp = pendingStamp
        val rotation = pendingRotation
        pendingJpeg?.let { bytes ->
            pendingJpeg = null; jpegDone = true
            val look = pendingLook
            saver.execute { saveJpeg(bytes, res, rotation, look, stamp) }
        }
        if (jpegDone) {
            pendingResult = null
            capturing = false
            runOnUiThread { shutterBtn.isEnabled = true }
        }
    }

    private fun saveJpeg(bytes: ByteArray, res: TotalCaptureResult, rotation: Int, look: Look, stamp: String) {
        try {
            val tmp = File(cacheDir, "capture.jpg")
            var thumbBmp: Bitmap? = null

            if (look.identity) {
                tmp.writeBytes(bytes) // untouched sensor JPEG, orientation set via EXIF
            } else {
                val opts = BitmapFactory.Options()
                val src = try {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                } catch (e: OutOfMemoryError) {
                    opts.inSampleSize = 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                } ?: throw IllegalStateException("decode failed")
                val out = Looks.render(src, look, rotation)
                tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                thumbBmp = Bitmap.createScaledBitmap(out, 200, 200 * out.height / out.width, true)
                out.recycle()
            }

            ExifInterface(tmp.absolutePath).apply {
                val orient = if (look.identity) exifOrientation(rotation) else ExifInterface.ORIENTATION_NORMAL
                setAttribute(ExifInterface.TAG_ORIENTATION, orient.toString())
                setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
                setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
                setAttribute(ExifInterface.TAG_SOFTWARE, "Frames (${look.name})")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date()))
                res.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
                    setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString())
                }
                res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
                    setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "${it / 1000}/1000000")
                }
                res.get(CaptureResult.LENS_APERTURE)?.let {
                    setAttribute(ExifInterface.TAG_F_NUMBER, "${(it * 100).roundToInt()}/100")
                }
                res.get(CaptureResult.LENS_FOCAL_LENGTH)?.let {
                    setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).roundToInt()}/1000")
                }
                saveAttributes()
            }

            val uri = insertMedia("FRM_$stamp.jpg", "image/jpeg") { os ->
                tmp.inputStream().use { it.copyTo(os) }
            }

            if (thumbBmp == null) {
                val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = 16 })
                if (small != null) {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    thumbBmp = Bitmap.createBitmap(small, 0, 0, small.width, small.height, m, true)
                }
            }
            val t = thumbBmp
            runOnUiThread {
                lastUri = uri
                t?.let { thumb.setImageBitmap(it) }
                if (uri != null) toast("Saved to gallery · ${look.name}")
            }
        } catch (e: Exception) {
            runOnUiThread { toast("Save failed: ${e.message}") }
        }
    }

    private fun insertMedia(name: String, mime: String, write: (OutputStream) -> Unit): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Frames")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use(write)
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun exifOrientation(r: Int) = when (r) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    // =====================================================================
    // Controls
    // =====================================================================

    private fun enterManual() {
        if (!manualSensor) { toast("Manual exposure isn't supported on this camera"); return }
        if (!manual) {
            manual = true
            if (liveIso > 0) iso = isoList[nearestIso(liveIso)]
            if (liveExp > 0) exposureNs = shutterList[nearestShutter(liveExp)]
        }
        updatePreview()
    }

    private fun toggleControl(c: Ctl) {
        if (activeControl == c) {
            activeControl = null
            panel.visibility = View.GONE
            refreshUi()
            return
        }
        when (c) {
            Ctl.ISO, Ctl.SHUTTER -> {
                if (!manualSensor) { toast("Manual exposure isn't supported on this camera"); return }
                enterManual()
            }
            Ctl.FOCUS -> if (!manualFocusSupported) { toast("Manual focus isn't supported on this camera"); return }
            Ctl.EV -> if (manual) toast("EV applies in auto exposure — tap AUTO to switch")
            else -> {}
        }
        activeControl = c
        panel.visibility = View.VISIBLE
        if (c == Ctl.LOOK) {
            seek.visibility = View.GONE
            lookScroll.visibility = View.VISIBLE
            buildLookTiles()
        } else {
            lookScroll.visibility = View.GONE
            seek.visibility = View.VISIBLE
            seek.max = maxFor(c)
            seek.progress = progressFor(c)
        }
        refreshUi()
    }

    private fun maxFor(c: Ctl) = when (c) {
        Ctl.ISO -> isoList.size - 1
        Ctl.SHUTTER -> shutterList.size - 1
        Ctl.EV -> evRange.upper - evRange.lower
        Ctl.FOCUS -> 100
        Ctl.WB -> wbModes.size - 1
        Ctl.LOOK -> Looks.all.size - 1
    }

    private fun progressFor(c: Ctl) = when (c) {
        Ctl.ISO -> nearestIso(iso)
        Ctl.SHUTTER -> nearestShutter(exposureNs)
        Ctl.EV -> evIndex - evRange.lower
        Ctl.FOCUS -> {
            val d = if (focusAuto) liveFocus else focusDiopters
            if (minFocus > 0f) ((1f - d / minFocus) * 100f).roundToInt().coerceIn(0, 100) else 100
        }
        Ctl.WB -> wbIndex
        Ctl.LOOK -> lookIndex
    }

    private fun onSeek(p: Int) {
        when (activeControl ?: return) {
            Ctl.ISO -> { manual = true; iso = isoList[p] }
            Ctl.SHUTTER -> { manual = true; exposureNs = shutterList[p] }
            Ctl.EV -> evIndex = p + evRange.lower
            Ctl.FOCUS -> { focusAuto = false; focusDiopters = minFocus * (1f - p / 100f) }
            Ctl.WB -> wbIndex = p
            Ctl.LOOK -> { lookIndex = p; applyLook() }
        }
        refreshUi()
        updatePreview()
    }

    private fun setAuto(c: Ctl) {
        when (c) {
            Ctl.ISO, Ctl.SHUTTER -> manual = false
            Ctl.EV -> evIndex = 0
            Ctl.FOCUS -> focusAuto = true
            Ctl.WB -> wbIndex = 0
            Ctl.LOOK -> { lookIndex = 0; applyLook(); updateLookTiles() }
        }
        if (activeControl == c && c != Ctl.LOOK) seek.progress = progressFor(c)
        refreshUi()
        updatePreview()
    }

    // ---- Look picker boxes ----

    /** Builds one box per look, each showing the current viewfinder frame in that look. */
    private fun buildLookTiles() {
        lookPreview = try { if (texture.isAvailable) texture.getBitmap(150, 200) else null } catch (_: Exception) { null }
        lookRow.removeAllViews()
        val d = resources.displayMetrics.density
        Looks.all.forEachIndexed { i, look ->
            // a small gap between the basic looks and the 35mm film looks
            val gap = if (i > 0 && look.film != null && Looks.all[i - 1].film == null) 14 else 0
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = ((4 + gap) * d).toInt(); marginEnd = (4 * d).toInt() }
                tag = i
                setOnClickListener {
                    lookIndex = i
                    applyLook()
                    updateLookTiles()
                    refreshUi()
                }
            }
            val img = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((58 * d).toInt(), (76 * d).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = getDrawable(R.drawable.thumb_bg)
                lookPreview?.let { setImageBitmap(it) }
                colorFilter = ColorMatrixColorFilter(look.previewMatrix)
            }
            val label = TextView(this).apply {
                text = look.short
                textSize = 11f
                setPadding(0, (5 * d).toInt(), 0, 0)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            tile.addView(img)
            tile.addView(label)
            lookRow.addView(tile)
        }
        updateLookTiles()
        // keep the selected box in view
        lookRow.post {
            lookRow.getChildAt(lookIndex)?.let { (lookScroll as android.widget.HorizontalScrollView).smoothScrollTo(it.left - (40 * d).toInt(), 0) }
        }
    }

    private fun updateLookTiles() {
        val accent = getColor(R.color.accent)
        for (k in 0 until lookRow.childCount) {
            val tile = lookRow.getChildAt(k) as LinearLayout
            val selected = tile.tag == lookIndex
            tile.background = getDrawable(if (selected) R.drawable.tile_bg_selected else R.drawable.tile_bg)
            (tile.getChildAt(1) as TextView).setTextColor(if (selected) accent else 0xFFAAAAAA.toInt())
        }
    }

    private fun applyLook() {
        val look = Looks.all[lookIndex]
        texture.setLayerPaint(Paint().apply { colorFilter = ColorMatrixColorFilter(look.previewMatrix) })
        overlay.vignette = look.vignette
        overlay.grainSize = look.film?.grainSize ?: 1f
        overlay.grain = look.film?.grain ?: 0f
        overlay.crop32 = look.crop32
    }

    // =====================================================================
    // UI text
    // =====================================================================

    private fun title(c: Ctl) = when (c) {
        Ctl.ISO -> "ISO"; Ctl.SHUTTER -> "SHUTTER"; Ctl.EV -> "EV"
        Ctl.FOCUS -> "FOCUS"; Ctl.WB -> "WB"; Ctl.LOOK -> "LOOK"
    }

    private fun valueLabel(c: Ctl): String = when (c) {
        Ctl.ISO -> if (manual) "$iso" else if (liveIso > 0) "A$liveIso" else "A"
        Ctl.SHUTTER -> if (manual) shutterLabel(exposureNs) else if (liveExp > 0) "A" + shutterLabel(liveExp) else "A"
        Ctl.EV -> if (manual) "—" else String.format(Locale.US, "%+.1f", evIndex * evStep)
        Ctl.FOCUS -> if (focusAuto) "AF" else focusLabel(focusDiopters)
        Ctl.WB -> wbModes[wbIndex.coerceIn(0, wbModes.size - 1)].first
        Ctl.LOOK -> Looks.all[lookIndex].short
    }

    private fun isUserSet(c: Ctl) = when (c) {
        Ctl.ISO, Ctl.SHUTTER -> manual
        Ctl.EV -> evIndex != 0 && !manual
        Ctl.FOCUS -> !focusAuto
        Ctl.WB -> wbIndex != 0
        Ctl.LOOK -> lookIndex != 0
    }

    private fun refreshUi() {
        val accent = getColor(R.color.accent)
        chips.forEach { (c, v) ->
            val color = when {
                activeControl == c -> accent
                isUserSet(c) -> 0xFFFFFFFF.toInt()
                else -> 0xFF9A9A9A.toInt()
            }
            val shortTitle = if (c == Ctl.SHUTTER) "S" else title(c)
            v.text = chipText(shortTitle, valueLabel(c), color)
        }
        activeControl?.let { c ->
            panelTitle.text = title(c)
            panelValue.text = if (c == Ctl.LOOK) Looks.all[lookIndex].name else valueLabel(c)
            panelAuto.text = if (c == Ctl.LOOK) "RESET" else "AUTO"
        }
        modeText.text = if (manual) "MANUAL" else "AUTO"
        modeText.setTextColor(if (manual) accent else 0xFF9A9A9A.toInt())
        gridText.setTextColor(if (overlay.showGrid) accent else 0xFF9A9A9A.toInt())
    }

    private fun chipText(title: String, value: String, color: Int): CharSequence {
        val sb = SpannableStringBuilder(title).append("\n").append(value)
        sb.setSpan(RelativeSizeSpan(0.68f), 0, title.length, 0)
        sb.setSpan(ForegroundColorSpan(0xFF6E6E6E.toInt()), 0, title.length, 0)
        sb.setSpan(ForegroundColorSpan(color), title.length + 1, sb.length, 0)
        return sb
    }

    private fun shutterLabel(ns: Long): String =
        if (ns >= 400_000_000L) {
            val s = ns / 1e9
            if (s >= 1.0) "${s.roundToInt()}s" else String.format(Locale.US, "%.1fs", s)
        } else "1/${(1e9 / ns).roundToInt()}"

    private fun focusLabel(d: Float): String =
        if (d <= 0.001f) "∞" else String.format(Locale.US, "%.2fm", 1f / d)

    private fun nearestIso(v: Int) = isoList.indices.minByOrNull { abs(isoList[it] - v) } ?: 0
    private fun nearestShutter(v: Long) =
        shutterList.indices.minByOrNull { abs(ln(shutterList[it].toDouble() / v)) } ?: 0

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_CAM = 7
        private const val PREVIEW_MAX_EXP = 66_666_666L // 1/15 s

        private val ISO_STOPS = listOf(
            25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000,
            1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 25600
        )

        private val SHUTTER_STOPS: List<Long> =
            listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400,
                320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3)
                .map { 1_000_000_000L / it } +
                listOf(0.5, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0).map { (it * 1_000_000_000L).toLong() }

        private val ALL_WB = listOf(
            "Auto" to CameraMetadata.CONTROL_AWB_MODE_AUTO,
            "Daylight" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            "Cloudy" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            "Shade" to CameraMetadata.CONTROL_AWB_MODE_SHADE,
            "Tungsten" to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            "Fluoro" to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT,
        )
    }
}
