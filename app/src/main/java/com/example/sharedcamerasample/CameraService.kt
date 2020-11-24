package com.example.sharedcamerasample

import android.Manifest
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RequiresPermission
import timber.log.Timber


class CameraService(private val cameraManager: CameraManager, private val textureView: TextureView) {
    private val cameraId: String = cameraManager.backCameraId
    private var cameraDevice: CameraDevice? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var previewSurface: Surface? = null
    private var captureSession: CameraCaptureSession? = null

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
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

    }

    private fun createCameraPreviewSession() {
        previewSurface = Surface(textureView.surfaceTexture)
        try {
            val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(previewSurface!!)
            cameraDevice!!.createCaptureSession(mutableListOf(previewSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    captureSession!!.setRepeatingRequest(captureRequestBuilder.build(),null, backgroundHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Timber.e("createCameraPreviewSession, CameraCaptureSession.StateCallback - configure failed")
                }

            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Timber.e(e)
        }
    }

    fun isOpen(): Boolean = cameraDevice != null

    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera() {
        startBackgroundThread()
        try {
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
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
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private val CameraManager.backCameraId: String
        get() {
            var cameraId = ""
            for (id in cameraIdList) {
                if (
                    getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                    == CameraCharacteristics.LENS_FACING_BACK
                ) {
                    cameraId = id
                    break
                }
            }
            if (cameraId.isEmpty()) cameraId = cameraIdList[0]
            return cameraId
        }
}