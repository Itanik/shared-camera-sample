package com.example.sharedcamerasample

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.View
import androidx.annotation.RequiresPermission
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.fragment_shared_camera.*

class SharedCameraFragment(contentLayoutId: Int) : Fragment(contentLayoutId) {
    private lateinit var cameraService: CameraService
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val cameraManager =
            requireActivity().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraService = CameraService(cameraManager, cameraPreview)
        onFragmentInitialized()
    }

    var onFragmentInitialized: () -> Unit = {}
    var onImageTaken
        get() = cameraService.onImageTaken
        set(value) {
            cameraService.onImageTaken = value
        }

    @RequiresPermission(Manifest.permission.CAMERA)
    override fun onResume() {
        super.onResume()
        cameraService.performOpenCamera()
    }

    override fun onPause() {
        cameraService.closeCamera()
        super.onPause()
    }

    fun performTakePicture() {
        cameraService.capture()
    }
}