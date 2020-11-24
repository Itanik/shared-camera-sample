package com.example.sharedcamerasample.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.SurfaceHolder
import com.example.sharedcamerasample.presentation.AutoFitSurfaceView
import timber.log.Timber
import java.io.File


class CameraService(
    private val cameraManager: CameraManager,
    private val cameraPreview: AutoFitSurfaceView
) {

    companion object {
        // Maximum number of images that will be held in the reader's buffer
        private const val IMAGE_BUFFER_SIZE: Int = 1
    }
    private val cameraId: String = cameraManager.backCameraId
    private var cameraDevice: CameraDevice? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private lateinit var captureSession: CameraCaptureSession
    private val imageReader: ImageReader by lazy {
        val size = characteristics.getTargetSize(Size(1920,1080))
        Timber.d("imageReader final size = $size")

        ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.JPEG,
            IMAGE_BUFFER_SIZE
        ).apply {
            setOnImageAvailableListener({ reader ->
                backgroundHandler?.post(ImageSaver(reader.acquireNextImage(), imageFile))
                onImageTaken(imageFile)
            }, backgroundHandler)
        }
    }
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraId)
    }

    var onImageTaken: (File) -> Unit = {}
    private lateinit var imageFile: File


    private fun createCameraPreviewSession() {
        val previewSurface = cameraPreview.holder.surface
        try {
            val captureRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(previewSurface!!)
            cameraDevice!!.createCaptureSession(
                mutableListOf(previewSurface, imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        captureSession.setRepeatingRequest(
                            captureRequestBuilder.build(),
                            null,
                            backgroundHandler
                        )
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Timber.e("createCameraPreviewSession, CameraCaptureSession.StateCallback - configure failed")
                    }

                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Timber.e(e)
        }
    }

    fun isOpen(): Boolean = cameraDevice != null

    @SuppressLint("MissingPermission")
    fun openCamera() {
        startBackgroundThread()
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Timber.e("cameraStateCallback.onError errorCode = $error")
                }

            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Timber.e(e)
        }
    }

    fun closeCamera() {
        cameraDevice?.close()
        cameraDevice = null
        stopBackgroundThread()
//        surface?.release()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraThread")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        if (backgroundThread == null) return
        backgroundThread!!.quitSafely()
        try {
            backgroundThread!!.join()
            backgroundThread = null
            backgroundHandler = null
            cameraPreview.holder.addCallback(null)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    fun capture(file: File) {
        Timber.d( "cameraPreview size: ${cameraPreview.width} x ${cameraPreview.height}")
        if (cameraDevice == null) return
        imageFile = file
        try {
            val captureRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuilder.addTarget(imageReader.surface)
            captureSession.apply {
                capture(
                    captureRequestBuilder.build(),
                    object : CameraCaptureSession.CaptureCallback() {

                    },
                    backgroundHandler
                )
            }
        } catch (e: CameraAccessException) {
            Timber.e(e)
        }
    }

    fun performOpenCamera() {
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

                openCamera()
            }
        })
    }
}