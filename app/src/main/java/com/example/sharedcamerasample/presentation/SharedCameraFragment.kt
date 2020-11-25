package com.example.sharedcamerasample.presentation

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.sharedcamerasample.camera.CameraService
import kotlinx.android.synthetic.main.fragment_shared_camera.*
import java.io.File

class SharedCameraFragment(contentLayoutId: Int) : Fragment(contentLayoutId) {
    private lateinit var cameraService: CameraService
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val cameraManager =
            requireActivity().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraService = CameraService(cameraManager, cameraPreview)
        onFragmentInitialized()
        cameraService.initCamera(requireContext(), view, lifecycleScope)
    }

    var onFragmentInitialized: () -> Unit = {}
    var onImageTaken
        get() = cameraService.onImageTaken
        set(value) {
            cameraService.onImageTaken = value
        }

    fun performTakePicture() {
        cameraService.capture(createFile())
    }

    private fun createFile(): File {
        return File(
            requireContext().getExternalFilesDir(Environment.DIRECTORY_DCIM),
            "${System.currentTimeMillis()}.jpg"
        )
    }

    override fun onStop() {
        super.onStop()
        cameraService.closeCamera()
    }

    override fun onDestroy() {
        cameraService.stopBackgroundThread()
        super.onDestroy()
    }
}