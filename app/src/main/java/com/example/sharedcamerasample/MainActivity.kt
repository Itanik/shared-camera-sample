package com.example.sharedcamerasample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.sharedcamerasample.presentation.BaseArFragment
import com.google.ar.core.ArCoreApk
import com.google.ar.core.ArCoreApk.Availability
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.android.synthetic.main.activity_main.*
import timber.log.Timber

class MainActivity : AppCompatActivity() {
    private lateinit var arFragment: Fragment

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
        if (!isARCoreSupportedAndUpToDate()) return
        else
            Toast.makeText(applicationContext, "AR available", Toast.LENGTH_LONG).show()
//        arFragment = SharedCameraFragment(R.layout.fragment_shared_camera)
        arFragment = BaseArFragment()
        supportFragmentManager.beginTransaction()
            .add(R.id.arContainer, arFragment)
            .commit()
//        arFragment.onFragmentInitialized = {
//            arFragment.onImageTaken = {
//                Toast.makeText(applicationContext, "Picture taken, file =$it", Toast.LENGTH_LONG)
//                    .show()
//            }
//        }
        buttonCapture.setOnClickListener {
//            arFragment.performTakePicture()
            (arFragment as BaseArFragment).takePicture()
        }
    }

    private fun isARCoreSupportedAndUpToDate(): Boolean {
        // Make sure ARCore is installed and supported on this device.
        val availability = ArCoreApk.getInstance().checkAvailability(applicationContext)
        when (availability) {
            Availability.SUPPORTED_INSTALLED -> {
            }
            Availability.SUPPORTED_APK_TOO_OLD, Availability.SUPPORTED_NOT_INSTALLED -> try {
                // Request ARCore installation or update if needed.
                when (ArCoreApk.getInstance()
                    .requestInstall(this,  /*userRequestedInstall=*/true)) {
                    InstallStatus.INSTALL_REQUESTED -> {
                        Timber.d("ARCore installation requested.")
                        return false
                    }
                    InstallStatus.INSTALLED -> {
                    }
                }
            } catch (e: UnavailableException) {
                Timber.e(e, "ARCore not installed")
                runOnUiThread {
                    Toast.makeText(
                        applicationContext,
                        "ARCore not installed\n$e",
                        Toast.LENGTH_LONG
                    ).show()
                }
                finish()
                return false
            }
            Availability.UNKNOWN_ERROR, Availability.UNKNOWN_CHECKING, Availability.UNKNOWN_TIMED_OUT, Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                Timber.d("ARCore is not supported on this device, ArCoreApk.checkAvailability() returned $availability")
                runOnUiThread {
                    Toast.makeText(
                        applicationContext,
                        "ARCore is not supported on this device, "
                                + "ArCoreApk.checkAvailability() returned "
                                + availability,
                        Toast.LENGTH_LONG
                    )
                        .show()
                }
                return false
            }
        }
        return true
    }
}