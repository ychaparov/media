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
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList

/**
 * A [GlEffect] that overlays the camera preview on top of the video frame.
 */
@UnstableApi
class CameraOverlayEffect(
  private val context: Context,
  private val overlaySettings: OverlaySettings = StaticOverlaySettings.Builder().build()
) : GlEffect {
  private var cameraOverlay: CameraOverlay? = null

  override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
    val overlay =
      object : CameraOverlay(context) {
        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
          return overlaySettings
        }
      }
    this.cameraOverlay = overlay
    return OverlayShaderProgram(context, useHdr, ImmutableList.of(overlay))
  }
}
