/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import android.graphics.ColorSpace
import android.graphics.HardwareBufferRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.ColorInfo.SDR_BT709_LIMITED
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Log
import androidx.media3.effect.PacketConsumer.Packet
import com.google.common.collect.ImmutableList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// TODO: b/479415308 - Replace HardwareBufferRenderer with another method of copying data to support
// APIs below 34.
/**
 * A [PacketProcessor] that renders the input [HardwareBufferFrame] into a new output buffer using
 * [HardwareBufferRenderer].
 */
@RequiresApi(34)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class DefaultHardwareBufferEffectsPipeline(private val context: Context) :
  RenderingPacketConsumer<ImmutableList<HardwareBufferFrame>, HardwareBufferFrameQueue> {

  /** Executor used for all blocking [SyncFence.await] calls. */
  private val internalExecutor = Executors.newSingleThreadExecutor()
  private val internalDispatcher = internalExecutor.asCoroutineDispatcher()
  private val isReleased = AtomicBoolean(false)
  // TODO: b/479134794 - This being nullable and mutable adds complexity, simplify this.
  private var outputBufferQueue: HardwareBufferFrameQueue? = null

  private val latestCameraBuffer = AtomicReference<HardwareBuffer?>()
  private val cameraRotationDegrees = AtomicInteger(0)
  private val isCameraMirrored = AtomicBoolean(false)

  private var firstFramePresentationTimeUs = C.TIME_UNSET
  private var firstFrameRealtimeMs = 0L

  init {
    (context as? LifecycleOwner)?.let { setLifecycleOwner(it) }
  }

  /**
   * Sets the [LifecycleOwner] to bind the camera to.
   *
   * If not set, the camera will only be initialized if the [context] passed to the constructor is a
   * [LifecycleOwner].
   */
  fun setLifecycleOwner(lifecycleOwner: LifecycleOwner) {
    Log.d(TAG, "Setting lifecycle owner: $lifecycleOwner")
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
      {
        val cameraProvider = cameraProviderFuture.get()
        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(internalExecutor) { request ->
          request.setTransformationInfoListener(internalExecutor) { info ->
            cameraRotationDegrees.set(info.rotationDegrees)
            isCameraMirrored.set(info.isMirroring)
          }

          val resolution = request.resolution
          val reader =
            ImageReader.newInstance(
              resolution.width,
              resolution.height,
              PixelFormat.RGBA_8888,
              2,
              HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
            )
          reader.setOnImageAvailableListener(
            {
              val image = it.acquireLatestImage()
              if (image != null) {
                val buffer = image.hardwareBuffer
                if (buffer != null) {
                  val oldBuffer = latestCameraBuffer.getAndSet(buffer)
                  oldBuffer?.close()
                }
                image.close()
              }
            },
            Handler(Looper.getMainLooper()),
          )

          request.provideSurface(reader.surface, internalExecutor) {
            reader.close()
            latestCameraBuffer.getAndSet(null)?.close()
          }
        }

        try {
          cameraProvider.unbindAll()
          cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
          Log.d(TAG, "Camera bound to lifecycle")
        } catch (e: Exception) {
          Log.e(TAG, "Use case binding failed", e)
        }
      },
      ContextCompat.getMainExecutor(context),
    )
  }

  override fun setRenderOutput(output: HardwareBufferFrameQueue?) {
    this.outputBufferQueue = output
  }

  override fun setErrorConsumer(errorConsumer: Consumer<Exception>) {}

  override suspend fun queuePacket(packet: Packet<ImmutableList<HardwareBufferFrame>>) {
    check(!isReleased.get())
    when (packet) {
      is Packet.EndOfStream -> outputBufferQueue!!.signalEndOfStream()
      is Packet.Payload -> {
        for (i in 1..packet.payload.lastIndex) {
          packet.payload[i].release(/* releaseFence= */ null)
        }
        if (packet.payload.isNotEmpty()) {
          processFrame(packet.payload[0])
        }
      }
    }
  }

  override suspend fun release() {
    if (!isReleased.getAndSet(true)) {
      internalExecutor.shutdown()
      latestCameraBuffer.getAndSet(null)?.close()
    }
  }

  private suspend fun processFrame(inputFrame: HardwareBufferFrame) {
    if (firstFramePresentationTimeUs == C.TIME_UNSET) {
      firstFramePresentationTimeUs = inputFrame.presentationTimeUs
      firstFrameRealtimeMs = SystemClock.elapsedRealtime()
    } else {
      val playoutTimeUs = inputFrame.presentationTimeUs - firstFramePresentationTimeUs
      val playoutTimeMs = playoutTimeUs / 1000
      val expectedRealtimeMs = firstFrameRealtimeMs + playoutTimeMs
      val nowMs = SystemClock.elapsedRealtime()
      if (expectedRealtimeMs > nowMs) {
        delay(expectedRealtimeMs - nowMs)
      }
    }

    var releaseFenceForInputFrame: SyncFenceCompat? = null
    try {
      if (inputFrame.hardwareBuffer == null) {
        throw IllegalArgumentException("Input frame missing HardwareBuffer")
      }
      // Get the output buffer that will be sent downstream.
      val outputFrame = getOutputFrame(inputFrame)
      check(outputFrame.hardwareBuffer != null)

      // Draw the input buffer contents into the output buffer.
      val renderCompletionFence =
        renderToOutputBuffer(
          inputFrame.hardwareBuffer,
          inputFrame.acquireFence,
          inputFrame.format.width,
          inputFrame.format.height,
          outputFrame.hardwareBuffer,
          outputFrame.acquireFence,
        )
      releaseFenceForInputFrame = SyncFenceCompat.duplicate(renderCompletionFence)

      // Send the output buffer downstream.
      val outputFrameWithMetadata =
        outputFrame
          .buildUpon()
          .setPresentationTimeUs(inputFrame.presentationTimeUs)
          .setReleaseTimeNs(inputFrame.releaseTimeNs)
          .setFormat(inputFrame.format)
          .setMetadata(inputFrame.metadata)
          .setAcquireFence(SyncFenceCompat.duplicate(renderCompletionFence))
          .build()
      outputBufferQueue!!.queue(outputFrameWithMetadata)
      renderCompletionFence.close()
    } finally {
      inputFrame.release(releaseFenceForInputFrame)
    }
  }

  private suspend fun getOutputFrame(inputFrame: HardwareBufferFrame): HardwareBufferFrame {
    val width = inputFrame.format.width
    val height = inputFrame.format.height
    val bufferFormat =
      HardwareBufferFrameQueue.FrameFormat.Builder()
        .setWidth(width)
        .setHeight(height)
        .setPixelFormat(
          if (ColorInfo.isTransferHdr(inputFrame.format.colorInfo)) HardwareBuffer.RGBA_1010102
          else HardwareBuffer.RGBA_8888
        )
        .setUsageFlags(
          HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
        .setColorInfo(inputFrame.format.colorInfo ?: SDR_BT709_LIMITED)
        .build()

    // Try and get an output buffer from the queue. If not immediately available, suspend until
    // notified and retry.
    val capacityAvailable = CompletableDeferred<Unit>()
    var outputFrame =
      outputBufferQueue!!.dequeue(bufferFormat)
      /* wakeupListener= */ {
        capacityAvailable.complete(Unit)
      }
    if (outputFrame == null) {
      withTimeout(TIMEOUT_MS) { capacityAvailable.await() }
      outputFrame = outputBufferQueue!!.dequeue(bufferFormat) /* wakeupListener= */ {}
      // Throw the second time there is no buffer available.
      check(outputFrame != null)
    }
    return outputFrame
  }

  private suspend fun renderToOutputBuffer(
    inputBuffer: HardwareBuffer,
    inputFence: SyncFenceCompat?,
    inputWidth: Int,
    inputHeight: Int,
    outputBuffer: HardwareBuffer,
    outputFence: SyncFenceCompat?,
  ): SyncFence {
    // TODO: b/479415308 - Replace HardwareBufferRenderer with another method of copying data to
    // support APIs below 34.
    return HardwareBufferRenderer(outputBuffer).use { renderer ->
      // Ensure the input buffer has been fully written to, and is ready to be read.
      waitOn(inputFence)
      check(!inputBuffer.isClosed)
      val inputBitmap =
        Bitmap.wrapHardwareBuffer(inputBuffer, ColorSpace.get(ColorSpace.Named.SRGB))
          ?: throw IllegalStateException("Failed to wrap input HardwareBuffer in Bitmap")

      val renderNode = RenderNode("PlaceholderEffect")
      renderNode.setPosition(0, 0, inputWidth, inputHeight)

      // Ensure the output buffer has been fully read from and is ready for reuse.
      waitOn(outputFence)
      check(!outputBuffer.isClosed)

      val canvas = renderNode.beginRecording(inputWidth, inputHeight)
      val halfWidth = inputWidth / 2
      canvas.drawBitmap(
        inputBitmap,
        null,
        Rect(0, 0, halfWidth, inputHeight),
        Paint().apply { isFilterBitmap = true },
      )

      val cameraBuffer = latestCameraBuffer.get()
      if (cameraBuffer != null && !cameraBuffer.isClosed) {
        val cameraBitmap =
          Bitmap.wrapHardwareBuffer(cameraBuffer, ColorSpace.get(ColorSpace.Named.SRGB))
        if (cameraBitmap != null) {
          val rect = Rect(halfWidth, 0, inputWidth, inputHeight)
          val rotation = cameraRotationDegrees.get()
          val mirrored = isCameraMirrored.get()

          val matrix = Matrix()
          matrix.postTranslate(-cameraBitmap.width / 2f, -cameraBitmap.height / 2f)
          
          // Front camera is mirrored horizontally.
          val scaleX = if (mirrored) -1f else 1f
          // OpenGL (camera) coordinates are bottom-up, Canvas is top-down. 
          // Flip Y to compensate.
          matrix.postScale(scaleX, -1f)
          
          matrix.postRotate(rotation.toFloat())

          val rotatedWidth =
            if (rotation % 180 != 0) cameraBitmap.height.toFloat() else cameraBitmap.width.toFloat()
          val rotatedHeight =
            if (rotation % 180 != 0) cameraBitmap.width.toFloat() else cameraBitmap.height.toFloat()

          val scale =
            Math.min(rect.width().toFloat() / rotatedWidth, rect.height().toFloat() / rotatedHeight)
          matrix.postScale(scale, -scale)
          matrix.postTranslate(rect.centerX().toFloat(), rect.centerY().toFloat())

          canvas.drawBitmap(cameraBitmap, matrix, Paint().apply { isFilterBitmap = true })
        }
      }
      renderNode.endRecording()

      renderer.setContentRoot(renderNode)

      suspendCancellableCoroutine { continuation ->
        renderer.obtainRenderRequest().draw(internalExecutor) { result ->
          val fence = result.fence
          // Tries to resume; if it fails (because already cancelled), closes the fence.
          runCatching { continuation.resume(fence) { _, _, _ -> fence.close() } }
            .onFailure { fence.close() }
        }
      }
    }
  }

  /** Helper function to suspend, switch to an internal thread and wait on the given [SyncFence]. */
  private suspend fun waitOn(fence: SyncFenceCompat?) {
    fence?.let {
      // Switch to the internal dispatcher for the blocking call.
      val signaled = withContext(internalDispatcher) { fence.await(500) }
      if (!signaled) {
        Log.w(TAG, "Timed out waiting for fence.")
      }
      fence.close()
    }
  }

  companion object {
    private const val TAG = "DefaultHBEffects"
    // It can take multiple seconds for the encoder to be configured and the first frame to be
    // encoded.
    private const val TIMEOUT_MS = 10_000L
  }
}
