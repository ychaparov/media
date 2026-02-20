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
import androidx.webgpu.GPURenderPipeline
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.LoadOp
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUPipelineLayoutDescriptor
import androidx.webgpu.GPUPrimitiveState
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUVertexState
import androidx.webgpu.PrimitiveTopology
import androidx.webgpu.StoreOp
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureViewDescriptor
import androidx.webgpu.helper.WebGpu
import androidx.webgpu.helper.createWebGpu
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.use

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

            // Modify the output buffer using WebGPU.
            val webGpuOutputFence = SyncFenceCompat.duplicate(renderCompletionFence).use { webGpuFence ->
                modifyHardwareBufferWithWebGpu(outputFrame.hardwareBuffer!!, webGpuFence)
            }

            // Send the output buffer downstream.
            val outputFrameWithMetadata =
                outputFrame
                    .buildUpon()
                    .setPresentationTimeUs(inputFrame.presentationTimeUs)
                    .setReleaseTimeNs(inputFrame.releaseTimeNs)
                    .setFormat(inputFrame.format)
                    .setMetadata(inputFrame.metadata)
                    .setAcquireFence(webGpuOutputFence ?: SyncFenceCompat.duplicate(renderCompletionFence))
                    .build()
            outputBufferQueue!!.queue(outputFrameWithMetadata)
            renderCompletionFence.close()
        } finally {
            inputFrame.release(releaseFenceForInputFrame)
        }
    }

    private suspend fun modifyHardwareBufferWithWebGpu(
        hardwareBuffer: HardwareBuffer,
        fence: SyncFenceCompat
    ): SyncFenceCompat? {
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
                        0x00050027  // SharedFenceSyncFD
                    )
                )
            )
        }
        val device = webGpu!!.device

        // Lock the hardware buffer and get the texture/memory objects.
        val objects = nativeLockHardwareBuffer(device.handle, hardwareBuffer, fence.detachFd())
        val texture = objects[0] as GPUTexture
        val memory = objects[1]
        val sharedFence = objects[2]

        return texture.use {
            try {
                var currentPipeline = pipeline
                if (currentPipeline == null) {
                    releaseResources()
                    val newShaderModule = device.createShaderModule(
                        GPUShaderModuleDescriptor(
                            shaderSourceWGSL = GPUShaderSourceWGSL(
                                code = """
                                @vertex fn vsMain(@builtin(vertex_index) vertexIndex : u32) -> @builtin(position) vec4<f32> {
                                    var pos = array<vec2<f32>, 3>(
                                        vec2<f32>(-1.0, -1.0),
                                        vec2<f32>( 3.0, -1.0),
                                        vec2<f32>(-1.0,  3.0)
                                    );
                                    return vec4<f32>(pos[vertexIndex], 0.0, 1.0);
                                }
                                @fragment fn fsMain() -> @location(0) vec4<f32> {
                                    return vec4<f32>(0.0, 1.0, 0.0, 1.0);
                                }
                            """.trimIndent()
                            )
                        )
                    )
                    shaderModule = newShaderModule
                    val newPipelineLayout = device.createPipelineLayout(GPUPipelineLayoutDescriptor())
                    pipelineLayout = newPipelineLayout
                    currentPipeline = device.createRenderPipeline(
                        GPURenderPipelineDescriptor(
                            layout = newPipelineLayout,
                            vertex = GPUVertexState(module = newShaderModule, entryPoint = "vsMain"),
                            fragment = GPUFragmentState(
                                module = newShaderModule,
                                entryPoint = "fsMain",
                                targets = arrayOf(GPUColorTargetState(format = texture.format))
                            ),
                            primitive = GPUPrimitiveState(topology = PrimitiveTopology.TriangleList)
                        )
                    )
                    pipeline = currentPipeline
                }

                val encoder = device.createCommandEncoder(GPUCommandEncoderDescriptor())

                val colorAttachment = GPURenderPassColorAttachment(
                    clearValue = GPUColor(0.0, 0.0, 0.0, 0.0), // Ignored for LoadOp.Load
                    view = texture.createView(GPUTextureViewDescriptor()),
                    loadOp = LoadOp.Load, // Preserves the input frame drawn by HardwareBufferRenderer
                    storeOp = StoreOp.Store
                )

                val passDesc = GPURenderPassDescriptor(
                    colorAttachments = arrayOf(colorAttachment)
                )

                val pass = encoder.beginRenderPass(passDesc)
                pass.setPipeline(currentPipeline!!)
                // Draw only on the left half of the buffer.
                pass.setViewport(
                    0f,
                    0f,
                    hardwareBuffer.width / 2f,
                    hardwareBuffer.height.toFloat(),
                    0f,
                    1f
                )
                pass.setScissorRect(0, 0, hardwareBuffer.width / 2, hardwareBuffer.height)
                pass.draw(3)
                pass.end()

                val commandBuffer = encoder.finish()
                device.queue.submit(arrayOf(commandBuffer))

                // Clean up resources used in this frame
                commandBuffer.close()
                encoder.close()
                colorAttachment.view!!.close()
                pass.close()
                null // Placeholder, fence is returned from Unlock
            } finally {
                // Unlock and release native resources (memory and fence created in Lock).
                // texture is closed by .use block, but we pass it to EndAccess first.
                val outFenceFd = nativeUnlockHardwareBuffer(texture, memory, sharedFence)
                if (outFenceFd >= 0) {
                    return@use SyncFenceCompat.adoptFenceFileDescriptor(outFenceFd)
                }
            }
            null
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
        fenceFd: Int
    ): Array<Any>

    private external fun nativeUnlockHardwareBuffer(
        texture: GPUTexture?,
        memory: Any,
        fence: Any?
    ): Int

    companion object {
        private const val TAG = "DefaultHBEffects"
        // It can take multiple seconds for the encoder to be configured and the first frame to be
        // encoded.
        private const val TIMEOUT_MS = 10_000L
    }
}
