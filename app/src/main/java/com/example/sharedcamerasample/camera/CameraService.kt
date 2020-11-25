package com.example.sharedcamerasample.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.example.sharedcamerasample.rendering.BackgroundRenderer
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.SharedCamera
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


class CameraService(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val cameraPreview: GLSurfaceView
) : GLSurfaceView.Renderer, LifecycleObserver{

    companion object {
        // Maximum number of images that will be held in the reader's buffer
        private const val IMAGE_BUFFER_SIZE: Int = 1
    }
    private lateinit var cameraId: String
    private lateinit var sharedCamera: SharedCamera
    private lateinit var arSession: Session
    private lateinit var cameraDevice: CameraDevice
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val backgroundRenderer = BackgroundRenderer()
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageFile: File
    var onImageTaken: (File) -> Unit = {}
    private val imageReader: ImageReader by lazy {
        val size = characteristics.getTargetSize(Size(1920, 1080))
        Timber.d("imageReader final size = $size")

        ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.JPEG,
            IMAGE_BUFFER_SIZE
        ).apply {
            setOnImageAvailableListener({ reader ->
                cameraHandler.post(ImageSaver(reader.acquireNextImage(), imageFile))
                onImageTaken(imageFile)
            }, cameraHandler)
        }
    }

    init {
        start()
    }

    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraId)
    }

    private fun initializeSharedCamera(context: Context) {
        arSession = createArSession(context)
        sharedCamera = arSession.sharedCamera
        cameraId = arSession.cameraConfig.cameraId
    }

    @SuppressLint("MissingPermission")
    private suspend fun initializeCamera(context: Context) {
        cameraDevice = openCamera(cameraManager, cameraId, cameraHandler)
        val targets = listOf(cameraPreview.holder.surface, imageReader.surface)
        captureSession = createCaptureSession(cameraDevice, targets, cameraHandler)
        val captureRequest = cameraDevice.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW
        ).apply { addTarget(cameraPreview.holder.surface) }

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        captureSession.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
    }

    fun closeCamera() {
        try {
            cameraDevice.close()
        } catch (exc: Throwable) {
            Timber.e(exc, "Error closing camera")
        }
    }

    fun stopBackgroundThread() {
        cameraThread.quitSafely()
    }

    fun capture(file: File) {
        imageFile = file
        try {
            val captureRequestBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuilder.addTarget(imageReader.surface)
            captureSession.capture(
                captureRequestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {

                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            Timber.e(e)
        }
    }

    fun initCamera(context: Context, view: View, lifecycleScope: LifecycleCoroutineScope) {
        cameraPreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit

            @SuppressLint("MissingPermission")
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                    cameraPreview.display,
                    cameraManager.getCameraCharacteristics(cameraId),
                    SurfaceHolder::class.java
                )
                Timber.d("cameraPreview size: ${cameraPreview.width} x ${cameraPreview.height}")
                Timber.d("Selected preview size: ${previewSize.width} x ${previewSize.height}")
//                cameraPreview.setAspectRatio(cameraPreview.width, previewSize.height)

                view.post {
                    lifecycleScope.launch(Dispatchers.Main) {
                        initializeSharedCamera(context)
                    }
                }
            }
        })
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Timber.d("Camera $cameraId has been disconnected")
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
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Timber.e(exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }


    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Timber.e(exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    private fun createArSession(context: Context): Session =
        Session(context, EnumSet.of(Session.Feature.SHARED_CAMERA)).apply {
            val newConfig: Config = config
            newConfig.focusMode = Config.FocusMode.AUTO
            configure(newConfig)
        }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

        try {
            backgroundRenderer.createOnGlThread(context)
        } catch (e: IOException) {
            Timber.e(e, "Failed to read an asset file")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)

        val displayRotation: Int =
            context.display!!.rotation
        arSession.setDisplayGeometry(displayRotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (!this::arSession.isInitialized) return

        try {
            arSession.setCameraTextureName(backgroundRenderer.textureId)

            val frame: Frame = arSession.update()

            backgroundRenderer.draw(frame)
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    fun start() {
        cameraPreview.preserveEGLContextOnPause = true
        cameraPreview.setEGLContextClientVersion(2)
        cameraPreview.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.

        cameraPreview.setRenderer(this)
        cameraPreview.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun resume() {
        if (!this::arSession.isInitialized)
            arSession = Session(context)
        try {
            arSession.resume()
        } catch (e: CameraNotAvailableException) {
            return
        }
        cameraPreview.onResume()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun pause() {
        cameraPreview.onPause()
        arSession.pause()
    }
}