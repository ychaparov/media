#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <webgpu/webgpu.h>
#include <unistd.h>
#include <cstring>
#include <mutex>
#include <vector>

#define LOG_TAG "composition_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#ifndef VK_IMAGE_LAYOUT_UNDEFINED
#define VK_IMAGE_LAYOUT_UNDEFINED 0
#endif

extern "C" JNIEXPORT jobjectArray JNICALL
Java_androidx_media3_demo_composition_effect_HardwareBufferEffectsPipeline_nativeLockHardwareBuffer(
    JNIEnv* env, jobject thiz, jlong deviceHandle, jobject hardwareBuffer) {
  
  WGPUDevice device = reinterpret_cast<WGPUDevice>(deviceHandle);
  AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
  if (!hb) {
    LOGE("Failed to get AHardwareBuffer from jobject");
    return nullptr;
  }

  WGPUSharedTextureMemoryAHardwareBufferDescriptor stmAHardwareBufferDesc = {};
  stmAHardwareBufferDesc.chain.sType = WGPUSType_SharedTextureMemoryAHardwareBufferDescriptor;
  stmAHardwareBufferDesc.handle = hb;

  WGPUSharedTextureMemoryDescriptor stmDesc = {};
  stmDesc.nextInChain = &stmAHardwareBufferDesc.chain;
  stmDesc.label = { "Imported HardwareBuffer", WGPU_STRLEN };

  WGPUSharedTextureMemory memory = wgpuDeviceImportSharedTextureMemory(device, &stmDesc);
  if (!memory) {
      LOGE("Failed to import SharedTextureMemory");
      return nullptr;
  }

  WGPUTexture texture = wgpuSharedTextureMemoryCreateTexture(memory, nullptr);
  if (!texture) {
      LOGE("Failed to create texture from SharedTextureMemory");
      wgpuSharedTextureMemoryRelease(memory);
      return nullptr;
  }

  WGPUSharedTextureMemoryBeginAccessDescriptor beginDesc = {};
  WGPUSharedTextureMemoryVkImageLayoutBeginState beginLayout{};
  beginLayout.chain.sType = WGPUSType_SharedTextureMemoryVkImageLayoutBeginState;
  beginLayout.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  beginLayout.newLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  beginDesc.nextInChain = &beginLayout.chain;

  if (!wgpuSharedTextureMemoryBeginAccess(memory, texture, &beginDesc)) {
      LOGE("Failed to begin access to SharedTextureMemory");
      wgpuTextureRelease(texture);
      wgpuSharedTextureMemoryRelease(memory);
      return nullptr;
  }

  if (env->ExceptionCheck()) {
    wgpuTextureRelease(texture);
    wgpuSharedTextureMemoryRelease(memory);
    return nullptr;
  }

  jobject textureObj = nullptr;
  jobject memoryObj = nullptr;
  jobjectArray result = nullptr;

  jclass textureClass = env->FindClass("androidx/webgpu/GPUTexture");
  if (env->ExceptionCheck()) {
      goto error_after_begin;
  }
  {
      jmethodID textureInit = env->GetMethodID(textureClass, "<init>", "(J)V");
      if (env->ExceptionCheck()) {
          goto error_after_begin;
      }
      textureObj = env->NewObject(textureClass, textureInit, reinterpret_cast<jlong>(texture));
      if (env->ExceptionCheck()) {
          goto error_after_begin;
      }

      jclass longClass = env->FindClass("java/lang/Long");
      if (env->ExceptionCheck()) {
          goto error_after_begin;
      }
      jmethodID valueOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
      if (env->ExceptionCheck()) {
          goto error_after_begin;
      }
      memoryObj = env->CallStaticObjectMethod(longClass, valueOf, reinterpret_cast<jlong>(memory));
      if (env->ExceptionCheck()) {
          goto error_after_begin;
      }

      result = env->NewObjectArray(2, env->FindClass("java/lang/Object"), nullptr);
      if (env->ExceptionCheck()) {
          goto error_after_begin;
      }
      env->SetObjectArrayElement(result, 0, textureObj);
      env->SetObjectArrayElement(result, 1, memoryObj);

      return result;
  }

error_after_begin:
  {
      WGPUSharedTextureMemoryEndAccessState endState = {};
      WGPUSharedTextureMemoryVkImageLayoutEndState endLayout{};
      endLayout.chain.sType = WGPUSType_SharedTextureMemoryVkImageLayoutEndState;
      endLayout.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      endLayout.newLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      endState.nextInChain = &endLayout.chain;
      wgpuSharedTextureMemoryEndAccess(memory, texture, &endState);
      wgpuTextureRelease(texture);
      wgpuSharedTextureMemoryRelease(memory);
      return nullptr;
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_androidx_media3_demo_composition_effect_HardwareBufferEffectsPipeline_nativeUnlockHardwareBuffer(
    JNIEnv* env, jobject thiz, jobject textureObj, jobject memoryLongObj) {
  
  if (!memoryLongObj) return -1;

  jclass longClass = env->FindClass("java/lang/Long");
  jmethodID longValue = env->GetMethodID(longClass, "longValue", "()J");
  if (env->ExceptionCheck()) return -1;
  WGPUSharedTextureMemory memory = reinterpret_cast<WGPUSharedTextureMemory>(env->CallLongMethod(memoryLongObj, longValue));

  // If textureObj is provided, we use its handle for EndAccess.
  WGPUTexture texture = nullptr;
  if (textureObj) {
      jclass textureClass = env->GetObjectClass(textureObj);
      jmethodID getHandle = env->GetMethodID(textureClass, "getHandle", "()J");
      if (!env->ExceptionCheck()) {
          texture = reinterpret_cast<WGPUTexture>(env->CallLongMethod(textureObj, getHandle));
      }
  }

  int fenceFd = -1;
  if (texture) {
      WGPUSharedTextureMemoryEndAccessState endState = {};
      WGPUSharedTextureMemoryVkImageLayoutEndState endLayout{};
      endLayout.chain.sType = WGPUSType_SharedTextureMemoryVkImageLayoutEndState;
      endLayout.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      endLayout.newLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      endState.nextInChain = &endLayout.chain;

      if (wgpuSharedTextureMemoryEndAccess(memory, texture, &endState)) {
          if (endState.fenceCount > 0) {
              WGPUSharedFenceExportInfo exportInfo = {};
              WGPUSharedFenceSyncFDExportInfo syncFdExportInfo = {};
              syncFdExportInfo.chain.sType = WGPUSType_SharedFenceSyncFDExportInfo;
              exportInfo.nextInChain = &syncFdExportInfo.chain;

              // We only support one fence for now, or we could merge them if needed.
              wgpuSharedFenceExportInfo(endState.fences[0], &exportInfo);
              fenceFd = dup(syncFdExportInfo.handle);
          }
          wgpuSharedTextureMemoryEndAccessStateFreeMembers(endState);
      } else {
          LOGE("Failed to end access to SharedTextureMemory");
      }
      wgpuTextureRelease(texture);
  }

  // Release the memory here as it was created in nativeLockHardwareBuffer and is not managed by Java.
  wgpuSharedTextureMemoryRelease(memory);

  return fenceFd;
}
