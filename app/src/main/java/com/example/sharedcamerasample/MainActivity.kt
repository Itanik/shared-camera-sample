package com.example.sharedcamerasample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.sharedcamerasample.presentation.SharedCameraFragment
import kotlinx.android.synthetic.main.activity_main.*
import timber.log.Timber

class MainActivity : AppCompatActivity() {
    private lateinit var arFragment: SharedCameraFragment

    companion object {
        private const val REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Timber.plant(Timber.DebugTree())
        if (hasPermissions())
            initFragment()
        else
            requestPermissions(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), REQUEST_CODE
            )
    }

    private fun hasPermissions(): Boolean =
        (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initFragment()
            }
        }
    }

    private fun initFragment() {
        arFragment = SharedCameraFragment(R.layout.fragment_shared_camera)
        supportFragmentManager.beginTransaction()
            .add(R.id.arContainer, arFragment)
            .commit()
        arFragment.onFragmentInitialized = {
            arFragment.onImageTaken = {
                Toast.makeText(applicationContext, "Picture taken, file =$it", Toast.LENGTH_LONG).show()
            }
        }
        buttonCapture.setOnClickListener {
            arFragment.performTakePicture()
        }
    }
}