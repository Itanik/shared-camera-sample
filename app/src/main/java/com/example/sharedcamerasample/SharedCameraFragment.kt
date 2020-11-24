package com.example.sharedcamerasample

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.TextureView
import android.view.View
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.fragment_shared_camera.*

class SharedCameraFragment(contentLayoutId: Int) : Fragment(contentLayoutId) {
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        @SuppressLint("MissingPermission")
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            cameraService.openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    }
    private lateinit var cameraService: CameraService

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val cameraManager =
            requireActivity().getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraService = CameraService(cameraManager, cameraPreview)
    }

    @SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        if (cameraPreview.isAvailable) {
            cameraService.openCamera()
        } else {
            cameraPreview.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        cameraService.closeCamera()
        super.onPause()
    }
}