package com.example.sharedcamerasample.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.sharedcamerasample.R
import com.example.sharedcamerasample.rendering.BackgroundRenderer
import com.google.ar.core.*
import kotlinx.android.synthetic.main.fragment_shared_camera.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class BaseArFragment : Fragment(), GLSurfaceView.Renderer {
    private lateinit var cameraManager: CameraManager
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var arSession: Session
    private lateinit var sharedCamera: SharedCamera
    private lateinit var gpuTextureSize: Size
    private lateinit var imageReader: ImageReader
    private var onFrameUpdate: (Frame) -> Unit = {}
    private val backgroundRenderer = BackgroundRenderer()
    private val cameraThread: HandlerThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler: Handler = Handler(cameraThread.looper)
    private val readerThread: HandlerThread = HandlerThread("ReaderThread").apply { start() }
    private val readerHandler: Handler = Handler(readerThread.looper)
    private val shouldUpdateSurfaceView: AtomicBoolean = AtomicBoolean(false)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_shared_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // важно инициализировать GLSurfaceView в основном потоке, без использования корутин
        initSurfaceView()
        cameraManager =
            requireActivity().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // для работы suspend функций. операции с камерой при этом должны выполняться в UI потоке
        lifecycleScope.launch(Dispatchers.Main) {
            initArCore(cameraManager)
        }
    }

    private fun initSurfaceView() {
        arSurfaceView.preserveEGLContextOnPause = true
        arSurfaceView.setEGLContextClientVersion(2)
        arSurfaceView.setEGLConfigChooser(
            8, 8, 8,
            8, 16, 0
        ) // Alpha used for plane blending.

        arSurfaceView.setRenderer(this)
        arSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private suspend fun initArCore(cameraManager: CameraManager) {
        arSession = createArSession()
        sharedCamera = arSession.sharedCamera
        gpuTextureSize =
            arSession.getSupportedCameraConfigs(CameraConfigFilter(arSession))[0]!!.textureSize
        imageReader = ImageReader.newInstance(
            gpuTextureSize.width,
            gpuTextureSize.height,
            ImageFormat.JPEG,
            1
        )
        val cameraId = arSession.cameraConfig.cameraId
        val cameraDevice = openCamera(cameraManager, cameraId, sharedCamera, cameraHandler)
        arSession.setCameraTextureName(backgroundRenderer.textureId)

        val captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        val surfaces: List<Surface> = sharedCamera.arCoreSurfaces.apply {
            add(imageReader.surface)
        }
        surfaces.forEach { surface ->
            captureRequestBuilder.addTarget(surface)
        }
        captureSession =
            createCaptureSession(cameraDevice, sharedCamera, surfaces, cameraHandler) {
                Timber.d("CameraCaptureSession.StateCallback: onActive. resuming arsession and camerapreview")
                resumeSession()
            }
        captureSession.setRepeatingRequest(
            captureRequestBuilder.build(),
            object : CameraCaptureSession.CaptureCallback() {

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    shouldUpdateSurfaceView.set(true)
                    Timber.d("CaptureCallback: onCaptureCompleted")
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Timber.e("onCaptureFailed: ${failure.frameNumber} ${failure.reason}")
                }
            },
            cameraHandler
        )

    }

    // работает в OpenGL потоке
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!shouldUpdateSurfaceView.get()) return

        try {
            val frame: Frame = arSession.update()
//            Timber.d("onDrawFrame: frame = ${frame.androidCameraTimestamp}")
            backgroundRenderer.draw(frame)
            onFrameUpdate(frame)
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Set GL clear color to black.
        GLES20.glClearColor(0f, 0f, 0f, 1.0f)
        Timber.d("onSurfaceCreated")

        try {
            backgroundRenderer.createOnGlThread(context)
        } catch (e: IOException) {
            Timber.e(e, "Failed to read an asset file")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Timber.d("onSurfaceChanged")
        if (!this::arSession.isInitialized) return
        val displayRotation: Int =
            requireContext().display!!.rotation
        arSession.setDisplayGeometry(displayRotation, width, height)
    }

    private fun createArSession(): Session =
        Session(requireContext(), EnumSet.of(Session.Feature.SHARED_CAMERA)).apply {
            val newConfig: Config = config
            newConfig.focusMode = Config.FocusMode.AUTO
//            newConfig.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            configure(newConfig)
        }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        sharedCamera: SharedCamera,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        val wrappedCallback = sharedCamera.createARDeviceStateCallback(object :
            CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Timber.d("Camera ${device.id} has been disconnected")
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera ${device.id} error: ($error) $msg")
                Timber.e(exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
        manager.openCamera(cameraId, wrappedCallback, handler)
    }


    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        sharedCamera: SharedCamera,
        targets: List<Surface>,
        handler: Handler? = null,
        onActive: () -> Unit
    ): CameraCaptureSession = suspendCoroutine { cont ->
        val wrappedCallback = sharedCamera.createARSessionStateCallback(object :
            CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onActive(session: CameraCaptureSession) {
                onActive()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Timber.e(exc)
                cont.resumeWithException(exc)
            }
        }, handler)
        device.createCaptureSession(targets, wrappedCallback, handler)
    }

    fun addOnUpdateListener(onUpdateListener: (Frame) -> Unit) {
        onFrameUpdate = onUpdateListener
    }

    fun takePicture() {
        Timber.d("click take picture")
        imageReader.setOnImageAvailableListener({
            Timber.d("reader listener: still image taken")
        }, readerHandler)
        val captureRequest = captureSession.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(imageReader.surface)
        }
        captureSession.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                Timber.d("capture still image started")

            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult) {
                Timber.d("CaptureCallback: capture still image completed")
            }
        }, cameraHandler)
    }

    private fun resumeSession() {
        arSession.resume()
        arSurfaceView.onResume()
        sharedCamera.setCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {},
            cameraHandler
        )
    }

    override fun onResume() {
        super.onResume()
        if (this::arSession.isInitialized) {
            resumeSession()
            shouldUpdateSurfaceView.set(true)
        }
    }

    override fun onPause() {
        super.onPause()
        shouldUpdateSurfaceView.set(false)
        arSurfaceView.onPause()
        if (this::arSession.isInitialized)
            arSession.pause()
    }

    override fun onDestroy() {
        cameraThread.quitSafely()
        super.onDestroy()
    }
}