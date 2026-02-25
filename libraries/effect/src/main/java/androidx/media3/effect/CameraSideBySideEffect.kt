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
import android.opengl.GLES20
import android.opengl.Matrix as GlMatrix
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi

/**
 * A [GlEffect] that shows the input video on the left half and the camera preview on the right half.
 */
@UnstableApi
class CameraSideBySideEffect(private val context: Context) : GlEffect {
  private var shaderProgram: CameraSideBySideShaderProgram? = null
  private var lifecycleOwner: LifecycleOwner? = null

  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
    val shaderProgram = CameraSideBySideShaderProgram(context, useHdr)
    this.shaderProgram = shaderProgram
    lifecycleOwner?.let { shaderProgram.setLifecycleOwner(it) }
    return shaderProgram
  }

  /**
   * Sets the [LifecycleOwner] to bind the camera to.
   */
  fun setLifecycleOwner(lifecycleOwner: LifecycleOwner) {
    this.lifecycleOwner = lifecycleOwner
    shaderProgram?.setLifecycleOwner(lifecycleOwner)
  }
}

@UnstableApi
private class CameraSideBySideShaderProgram(
  context: Context,
  useHdr: Boolean
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

  private val cameraOverlay = CameraOverlay(context)
  private val glProgram: GlProgram
  private val cameraMatrix = FloatArray(16)

  init {
    val vertexShader = """
      attribute vec4 aFramePosition;
      varying vec2 vTexSamplingCoord;
      void main() {
        gl_Position = aFramePosition;
        vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
      }
    """.trimIndent()

    val fragmentShader = """
      precision mediump float;
      uniform sampler2D uVideoTexSampler;
      uniform sampler2D uCameraTexSampler;
      uniform mat4 uCameraMatrix;
      varying vec2 vTexSamplingCoord;
      void main() {
        if (vTexSamplingCoord.x < 0.5) {
          gl_FragColor = texture2D(uVideoTexSampler, vec2(vTexSamplingCoord.x * 2.0, vTexSamplingCoord.y));
        } else {
          vec4 cameraTexCoord = uCameraMatrix * vec4((vTexSamplingCoord.x - 0.5) * 2.0, vTexSamplingCoord.y, 0.0, 1.0);
          gl_FragColor = texture2D(uCameraTexSampler, cameraTexCoord.xy);
        }
      }
    """.trimIndent()

    try {
      glProgram = GlProgram(vertexShader, fragmentShader)
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }

    glProgram.setBufferAttribute(
      "aFramePosition",
      GlUtil.getNormalizedCoordinateBounds(),
      GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
    )
  }

  override fun configure(inputWidth: Int, inputHeight: Int): Size {
    return Size(inputWidth, inputHeight)
  }

  override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
    try {
      glProgram.use()
      glProgram.setSamplerTexIdUniform("uVideoTexSampler", inputTexId, /* texUnitIndex= */ 0)
      glProgram.setSamplerTexIdUniform(
        "uCameraTexSampler",
        cameraOverlay.getTextureId(presentationTimeUs),
        /* texUnitIndex= */ 1
      )
      
      updateCameraMatrix()
      glProgram.setFloatsUniform("uCameraMatrix", cameraMatrix)
      
      glProgram.bindAttributesAndUniforms()
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
      GlUtil.checkGlError()
    } catch (e: Exception) {
      throw VideoFrameProcessingException(e, presentationTimeUs)
    }
  }

  private fun updateCameraMatrix() {
    GlMatrix.setIdentityM(cameraMatrix, 0)
    
    val rotation = cameraOverlay.cameraRotationDegrees.get()
    val mirrored = cameraOverlay.isCameraMirrored.get()
    
    // Matrix for texture coordinates (s, t)
    // Move to center of texture (0.5, 0.5)
    GlMatrix.translateM(cameraMatrix, 0, 0.5f, 0.5f, 0f)
    
    // Mirroring
    val scaleX = if (mirrored) -1f else 1f
    // Android Bitmap (Canvas) is top-down, GL is bottom-up. 
    // CameraX to ImageReader might also be different.
    GlMatrix.scaleM(cameraMatrix, 0, scaleX, -1f, 1f)
    
    // Rotation (CameraX rotation is clockwise, GL rotation is counter-clockwise)
    GlMatrix.rotateM(cameraMatrix, 0, -rotation.toFloat(), 0f, 0f, 1f)
    
    // Move back
    GlMatrix.translateM(cameraMatrix, 0, -0.5f, -0.5f, 0f)
  }

  fun setLifecycleOwner(lifecycleOwner: LifecycleOwner) {
    cameraOverlay.setLifecycleOwner(lifecycleOwner)
  }

  override fun release() {
    super.release()
    try {
      cameraOverlay.release()
      glProgram.delete()
    } catch (e: Exception) {
      // Ignore or log release errors
    }
  }
}
