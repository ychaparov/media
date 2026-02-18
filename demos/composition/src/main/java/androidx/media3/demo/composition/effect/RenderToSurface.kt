package androidx.media3.demo.composition.effect

import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import androidx.media3.common.Format
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.HardwareBufferFrame
import androidx.media3.effect.PacketConsumer
import androidx.media3.effect.SurfaceHolderHardwareBufferFrameQueue
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.util.concurrent.Executor

/** TODO */
@RequiresApi(34)
@UnstableApi
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
class RenderToSurface
private constructor(
    private val effectsPipeline: HardwareBufferEffectsPipeline,
    private val frameQueue: SurfaceHolderHardwareBufferFrameQueue,
) : PacketConsumer<ImmutableList<HardwareBufferFrame>> {

    /** [PacketConsumer.Factory] for creating [ProcessAndRenderToSurfaceConsumer] instances. */
    class Factory : PacketConsumer.Factory<ImmutableList<HardwareBufferFrame>> {
        private var surfaceHolder: SurfaceHolder? = null
        private var surfaceHolderExecutor: Executor? = null
        private var listener: SurfaceHolderHardwareBufferFrameQueue.Listener? = null
        private var listenerExecutor: Executor? = null

        override fun create(): PacketConsumer<ImmutableList<HardwareBufferFrame>> {
            val frameQueue =
                SurfaceHolderHardwareBufferFrameQueue(
                    surfaceHolder!!,
                    surfaceHolderExecutor!!,
                    listener ?: object : SurfaceHolderHardwareBufferFrameQueue.Listener {
                        override fun onFrameAboutToBeRendered(
                            presentationTimeUs: Long,
                            releaseTimeNs: Long,
                            format: Format
                        ) {

                        }

                        override fun onError(videoFrameProcessingException: VideoFrameProcessingException) {

                        }

                        override fun onEnded() {

                        }

                    },
                    listenerExecutor ?: directExecutor(),
                )
            val effectsPipeline = HardwareBufferEffectsPipeline()

            effectsPipeline.setRenderOutput(frameQueue)

            val processAndRenderConsumer = RenderToSurface(effectsPipeline, frameQueue)

            return processAndRenderConsumer
        }

        fun setOutput(output: SurfaceHolder?, executor: Executor?) {
            this.surfaceHolder = output
            this.surfaceHolderExecutor = executor
        }

        fun setListener(
            listener: SurfaceHolderHardwareBufferFrameQueue.Listener?,
            executor: Executor?,
        ) {
            this.listener = listener
            this.listenerExecutor = executor
        }
    }

    override suspend fun queuePacket(
        packet: PacketConsumer.Packet<ImmutableList<HardwareBufferFrame>>
    ) {
        effectsPipeline.queuePacket(packet)
    }

    override suspend fun release() {
        effectsPipeline.release()
        frameQueue.release()
    }
}
