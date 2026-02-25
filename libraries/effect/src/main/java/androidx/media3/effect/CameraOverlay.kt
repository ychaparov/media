/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.Log
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A [BitmapOverlay] that shows the camera preview using ImageAnalysis.toBitmap().
 * 
 * This ensures we always have a software-backed bitmap, avoiding hardware bitmap issues
 * with GlUtil.setTexture().
 */
@UnstableApi
open class CameraOverlay(private val context: Context) : BitmapOverlay() {
  private val internalExecutor = Executors.newSingleThreadExecutor()
  private val latestCameraBitmap = AtomicReference<Bitmap?>()
  private val latestSize = AtomicInteger(0) // Packed width and height
  internal val cameraRotationDegrees = AtomicInteger(0)
  internal val isCameraMirrored = AtomicBoolean(false)
  private val isReleased = AtomicBoolean(false)

  init {
    (context as? LifecycleOwner)?.let { setLifecycleOwner(it) }
  }

  /**
   * Sets the [LifecycleOwner] to bind the camera to.
   */
  fun setLifecycleOwner(lifecycleOwner: LifecycleOwner) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
      {
        if (isReleased.get()) return@addListener
        
        val cameraProvider = cameraProviderFuture.get()
        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(internalExecutor) { imageProxy ->
          cameraRotationDegrees.set(imageProxy.imageInfo.rotationDegrees)
          // Front camera is typically mirrored. CameraX doesn't explicitly expose
          // isMirrored in ImageInfo for ImageAnalysis easily, so we assume true for front.
          isCameraMirrored.set(cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)

          try {
            val bitmap = imageProxy.toBitmap()
            latestSize.set((bitmap.width shl 16) or bitmap.height)
            val oldBitmap = latestCameraBitmap.getAndSet(bitmap)
            oldBitmap?.recycle()
          } catch (e: Exception) {
            Log.e("CameraOverlay", "Failed to convert ImageProxy to Bitmap", e)
          } finally {
            imageProxy.close()
          }
        }

        try {
          cameraProvider.unbindAll()
          cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
        } catch (e: Exception) {
          Log.e("CameraOverlay", "Use case binding failed", e)
        }
      },
      ContextCompat.getMainExecutor(context),
    )
  }

  override fun getBitmap(presentationTimeUs: Long): Bitmap {
    return latestCameraBitmap.get() ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
  }

  override fun getTextureSize(presentationTimeUs: Long): Size {
    val packed = latestSize.get()
    return if (packed == 0) Size(1, 1) else Size(packed shr 16, packed and 0xFFFF)
  }

  override fun release() {
    if (isReleased.getAndSet(true)) return
    super.release()
    internalExecutor.shutdown()
    latestCameraBitmap.getAndSet(null)?.recycle()
  }
}
