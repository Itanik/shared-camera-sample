package com.example.sharedcamerasample.camera

import android.graphics.ImageFormat
import android.graphics.Point
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import android.view.Display
import kotlin.math.max
import kotlin.math.min

/** Helper class used to pre-compute shortest and longest sides of a [Size] */
class SmartSize(width: Int, height: Int) {
    var size = Size(width, height)
    var long = max(size.width, size.height)
    var short = min(size.width, size.height)
    override fun toString() = "SmartSize(${long}x${short})"
}

/** Standard High Definition size for pictures and video */
val SIZE_1080P: SmartSize = SmartSize(1920, 1080)

fun getDisplaySmartSize(display: Display): SmartSize {
    val outPoint = Point()
    display.getRealSize(outPoint)
    return SmartSize(outPoint.x, outPoint.y)
}

/**
 * Returns the largest available PREVIEW size. For more information, see:
 * https://d.android.com/reference/android/hardware/camera2/CameraDevice and
 * https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
 */
fun <T>getPreviewOutputSize(
    display: Display,
    characteristics: CameraCharacteristics,
    targetClass: Class<T>,
    format: Int? = null
): Size {

    // Find which is smaller: screen or 1080p
    val screenSize = getDisplaySmartSize(display)
    val hdScreen = screenSize.long >= SIZE_1080P.long || screenSize.short >= SIZE_1080P.short
    val maxSize = if (hdScreen) SIZE_1080P else screenSize

    // If image format is provided, use it to determine supported sizes; else use target class
    val config = characteristics.get(
        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    if (format == null)
        assert(StreamConfigurationMap.isOutputSupportedFor(targetClass))
    else
        assert(config.isOutputSupportedFor(format))
    val allSizes = if (format == null)
        config.getOutputSizes(targetClass) else config.getOutputSizes(format)

    // Get available sizes and sort them by area from largest to smallest
    val validSizes = allSizes
        .sortedWith(compareBy { it.height * it.width })
        .map { SmartSize(it.width, it.height) }.reversed()

    // Then, get the largest output size that is smaller or equal than our max size
    return validSizes.first { it.long <= maxSize.long && it.short <= maxSize.short }.size
}

val CameraManager.backCameraId: String
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

fun CameraCharacteristics.getTargetSize(target: Size): Size {
    val targetRatioRange =
        target.width.toFloat() / target.height.toFloat() - 0.1f..
        target.width.toFloat() / target.height.toFloat() + 0.1f

    val sizes = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        .getOutputSizes(ImageFormat.JPEG)
    val horizontalOriented = sizes.none { (it.height.toFloat() / it.width.toFloat()) > 1 }
    val wideSizes = sizes.filter {
        val ratio = if (horizontalOriented)
            it.width.toFloat() / it.height.toFloat()
        else
            it.height.toFloat() / it.width.toFloat()
        ratio in targetRatioRange
    }
    val finalTarget = wideSizes.filter {
        if (horizontalOriented)
            it.height == target.height && it.width == target.width
        else
            it.height == target.width && it.width == target.height
    }
    return if (finalTarget.isNotEmpty())
        finalTarget[0]
    else
        sizes.maxByOrNull { it.width * it.height } ?: sizes[0]
}