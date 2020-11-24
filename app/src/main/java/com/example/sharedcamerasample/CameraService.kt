package com.example.sharedcamerasample

import android.Manifest
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresPermission
import timber.log.Timber


class CameraService(private val cameraManager: CameraManager, private val cameraId: String){
    private var cameraDevice: CameraDevice? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {

        }

        override fun onDisconnected(camera: CameraDevice) {

        }

        override fun onError(camera: CameraDevice, error: Int) {

        }

    }

    fun isOpen(): Boolean = cameraDevice != null

    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera() {
        try {
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Timber.e(e)
        }
    }

    fun closeCamera() {
        if (cameraDevice != null) {
            cameraDevice!!.close()
            cameraDevice = null
        }
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
}