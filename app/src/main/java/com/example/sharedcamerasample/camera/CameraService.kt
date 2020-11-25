package com.example.sharedcamerasample.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import androidx.lifecycle.LifecycleCoroutineScope
import com.example.sharedcamerasample.presentation.AutoFitSurfaceView
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.SharedCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


class CameraService(
    private val cameraManager: CameraManager,
    private val cameraPreview: AutoFitSurfaceView
) {

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
            CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(cameraPreview.holder.surface) }

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
                cameraPreview.setAspectRatio(cameraPreview.width, previewSize.height)

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
}