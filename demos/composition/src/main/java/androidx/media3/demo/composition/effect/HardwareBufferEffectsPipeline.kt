package androidx.media3.demo.composition.effect

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareBufferRenderer
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import androidx.annotation.RequiresApi
import androidx.media3.common.ColorInfo
import androidx.media3.common.ColorInfo.SDR_BT709_LIMITED
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.HardwareBufferFrame
import androidx.media3.effect.HardwareBufferFrameQueue
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.effect.PacketProcessor
import androidx.media3.effect.RenderingPacketConsumer
import androidx.media3.effect.SyncFenceCompat
import androidx.webgpu.GPUColor
import androidx.webgpu.GPUCommandEncoderDescriptor
import androidx.webgpu.GPUDeviceDescriptor
import androidx.webgpu.GPUPipelineLayout
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPURenderPipeline
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureViewDescriptor
import androidx.webgpu.LoadOp
import androidx.webgpu.StoreOp
import androidx.webgpu.helper.WebGpu
import androidx.webgpu.helper.createWebGpu
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** TODO */
// TODO: b/479415308 - Replace HardwareBufferRenderer with another method of copying data to support
// APIs below 34.
/**
 * A [PacketProcessor] that renders the input [HardwareBufferFrame] into a new output buffer using
 * [HardwareBufferRenderer].
 */
@RequiresApi(34)
@UnstableApi
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class HardwareBufferEffectsPipeline :
    RenderingPacketConsumer<ImmutableList<HardwareBufferFrame>, HardwareBufferFrameQueue> {

    init {
        System.loadLibrary("composition_jni")
    }

    /** Executor used for all blocking [SyncFence.await] calls. */
    private val internalExecutor = Executors.newSingleThreadExecutor()
    private val internalDispatcher = internalExecutor.asCoroutineDispatcher()
    private val isReleased = AtomicBoolean(false)

    // TODO: b/479134794 - This being nullable and mutable adds complexity, simplify this.
    private var outputBufferQueue: HardwareBufferFrameQueue? = null

    private var webGpu: WebGpu? = null
    private var shaderModule: GPUShaderModule? = null
    private var pipelineLayout: GPUPipelineLayout? = null
    private var pipeline: GPURenderPipeline? = null

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
            releaseResources()
            webGpu?.close()
        }
    }

    private fun releaseResources() {
        pipeline?.close()
        pipeline = null
        pipelineLayout?.close()
        pipelineLayout = null
        shaderModule?.close()
        shaderModule = null
    }

    private suspend fun processFrame(inputFrame: HardwareBufferFrame) {
        var releaseFenceForInputFrame: SyncFenceCompat? = null
        try {
            if (inputFrame.hardwareBuffer == null) {
                throw IllegalArgumentException("Input frame missing HardwareBuffer")
            }
            // Get the output buffer that will be sent downstream.
            val outputFrame = getOutputFrame(inputFrame)
            check(outputFrame.hardwareBuffer != null)

            var renderCompleteFence: SyncFenceCompat?
            if ((inputFrame.presentationTimeUs / 2_000_000) % 2 == 0L) {
                // Draw the input buffer contents into the output buffer.
                val renderCompletionFence =
                    renderToOutputBuffer(
                        inputFrame.hardwareBuffer!!,
                        inputFrame.acquireFence,
                        inputFrame.format.width,
                        inputFrame.format.height,
                        outputFrame.hardwareBuffer!!,
                        outputFrame.acquireFence,
                    )
                releaseFenceForInputFrame = SyncFenceCompat.duplicate(renderCompletionFence)
                renderCompleteFence = SyncFenceCompat.duplicate(renderCompletionFence)
                renderCompletionFence.close()
            } else {
                val webGpuFence = withContext(Dispatchers.Main) {
                    // Avoid is a race condition between instance.processEvents()
                    // and wgpuSharedTextureMemoryEndAccess.
                    // Force our webgpu-native code to run on the main thread, same as
                    // dawn-kotlin
                    // https://github.com/androidx/androidx/blob/0315fbf69997dd38e93cb82fd62ca3bc3943c10d/webgpu/webgpu/src/main/java/androidx/webgpu/helper/WebGpu.kt#L80-L93
                    return@withContext modifyHardwareBufferWithWebGpu(
                        outputFrame.hardwareBuffer!!,
                        inputFrame.presentationTimeUs % 2_000_000
                    )
                }
                renderCompleteFence = SyncFenceCompat.adoptFenceFileDescriptor(webGpuFence)
            }

            // Send the output buffer downstream.
            val outputFrameWithMetadata =
                outputFrame
                    .buildUpon()
                    .setPresentationTimeUs(inputFrame.presentationTimeUs)
                    .setReleaseTimeNs(inputFrame.releaseTimeNs)
                    .setFormat(inputFrame.format)
                    .setMetadata(inputFrame.metadata)
                    .setAcquireFence(renderCompleteFence)
                    .build()
            outputBufferQueue!!.queue(outputFrameWithMetadata)
        } finally {
            inputFrame.release(releaseFenceForInputFrame)
        }
    }

    private suspend fun modifyHardwareBufferWithWebGpu(
        hardwareBuffer: HardwareBuffer,
        presentationTimeUs: Long
    ): Int {
        if (webGpu == null) {
            val executor = Executor { it.run() }
            webGpu = createWebGpu(
                deviceDescriptor = GPUDeviceDescriptor(
                    deviceLostCallbackExecutor = executor,
                    uncapturedErrorCallbackExecutor = executor,
                    deviceLostCallback = null,
                    uncapturedErrorCallback = null,
                    requiredFeatures = intArrayOf(
                        0x0005001E, // SharedTextureMemoryAHardwareBuffer
                        0x00050027  // WGPUFeatureName_SharedFenceSyncFD
                    )
                )
            )
        }
        val device = webGpu!!.device

        // TODO: wait on a fence that signals when we can start writing to hardwareBuffer
        val objects = nativeLockHardwareBuffer(device.handle, hardwareBuffer)
        val texture = objects[0] as GPUTexture
        val memory = objects[1]

        var fenceFd: Int
        try {
            val encoder = device.createCommandEncoder(GPUCommandEncoderDescriptor())

            val red = presentationTimeUs / 2_000_000.0
            val blue = 1.0 - red
            val colorAttachment = GPURenderPassColorAttachment(
                clearValue = GPUColor(red, 0.0, blue, 0.0), // Ignored for LoadOp.Load
                view = texture.createView(GPUTextureViewDescriptor()),
                loadOp = LoadOp.Clear, // Preserves the input frame drawn by HardwareBufferRenderer
                storeOp = StoreOp.Store
            )

            val passDesc = GPURenderPassDescriptor(
                colorAttachments = arrayOf(colorAttachment)
            )

            val pass = encoder.beginRenderPass(passDesc)
            pass.end()

            val commandBuffer = encoder.finish()
            device.queue.submit(arrayOf(commandBuffer))
            fenceFd = nativeUnlockHardwareBuffer(texture, memory)

            // Clean up resources used in this frame
            commandBuffer.close()
            encoder.close()
            colorAttachment.view!!.close()
            pass.close()
        } finally {
        }
        return fenceFd
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
                            or HardwareBuffer.USAGE_CPU_WRITE_OFTEN
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
            canvas.drawBitmap(inputBitmap, 0f, 0f, null)
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

    private external fun nativeLockHardwareBuffer(
        deviceHandle: Long,
        hardwareBuffer: HardwareBuffer,
    ): Array<Any>

    private external fun nativeUnlockHardwareBuffer(
        texture: GPUTexture?,
        memory: Any
    ): Int

    companion object {
        private const val TAG = "DefaultHBEffects"

        // It can take multiple seconds for the encoder to be configured and the first frame to be
        // encoded.
        private const val TIMEOUT_MS = 10_000L
    }
}
