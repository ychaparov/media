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
    JNIEnv* env, jobject thiz, jlong deviceHandle, jobject hardwareBuffer, jint fenceFd) {
  
  WGPUDevice device = reinterpret_cast<WGPUDevice>(deviceHandle);
  AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
  if (!hb) {
    LOGE("Failed to get AHardwareBuffer from jobject");
    if (fenceFd >= 0) close(fenceFd);
    return nullptr;
  }

  WGPUSharedFence sharedFence = nullptr;
  if (fenceFd >= 0) {
    int dupFd = dup(fenceFd);
    WGPUSharedFenceSyncFDDescriptor syncFdDesc = {};
    syncFdDesc.chain.sType = WGPUSType_SharedFenceSyncFDDescriptor;
    syncFdDesc.handle = dupFd;

    WGPUSharedFenceDescriptor fenceDesc = {};
    fenceDesc.nextInChain = &syncFdDesc.chain;

    sharedFence = wgpuDeviceImportSharedFence(device, &fenceDesc);
    if (!sharedFence) {
        LOGE("Failed to import SharedFence");
        close(dupFd);
    }
    // We are responsible for closing the original FD passed from Java.
    close(fenceFd);
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
      if (sharedFence) wgpuSharedFenceRelease(sharedFence);
      return nullptr;
  }

  WGPUTexture texture = wgpuSharedTextureMemoryCreateTexture(memory, nullptr);
  if (!texture) {
      LOGE("Failed to create texture from SharedTextureMemory");
      if (sharedFence) wgpuSharedFenceRelease(sharedFence);
      wgpuSharedTextureMemoryRelease(memory);
      return nullptr;
  }

  WGPUSharedTextureMemoryBeginAccessDescriptor beginDesc = {};
  WGPUSharedTextureMemoryVkImageLayoutBeginState beginLayout{};
  beginLayout.chain.sType = WGPUSType_SharedTextureMemoryVkImageLayoutBeginState;
  beginLayout.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  beginLayout.newLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  beginDesc.nextInChain = &beginLayout.chain;

  uint64_t signaledValue = 1; // Binary fences expect 1 as the signaled value.
  if (sharedFence) {
      beginDesc.fenceCount = 1;
      beginDesc.fences = &sharedFence;
      beginDesc.signaledValues = &signaledValue;
  }
  
  if (!wgpuSharedTextureMemoryBeginAccess(memory, texture, &beginDesc)) {
      LOGE("Failed to begin access to SharedTextureMemory");
      if (sharedFence) wgpuSharedFenceRelease(sharedFence);
      wgpuTextureRelease(texture);
      wgpuSharedTextureMemoryRelease(memory);
      return nullptr;
  }

  if (env->ExceptionCheck()) {
    wgpuTextureRelease(texture);
    wgpuSharedTextureMemoryRelease(memory);
    if (sharedFence) wgpuSharedFenceRelease(sharedFence);
    return nullptr;
  }

  jobject textureObj = nullptr;
  jobject memoryObj = nullptr;
  jobject fenceObj = nullptr;
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

      if (sharedFence) {
          fenceObj = env->CallStaticObjectMethod(longClass, valueOf, reinterpret_cast<jlong>(sharedFence));
          if (env->ExceptionCheck()) goto error_after_begin;
      }

      result = env->NewObjectArray(3, env->FindClass("java/lang/Object"), nullptr);
      if (env->ExceptionCheck()) {
          goto error_after_begin;
      }
      env->SetObjectArrayElement(result, 0, textureObj);
      env->SetObjectArrayElement(result, 1, memoryObj);
      env->SetObjectArrayElement(result, 2, fenceObj);

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
      if (sharedFence) wgpuSharedFenceRelease(sharedFence);
      wgpuTextureRelease(texture);
      wgpuSharedTextureMemoryRelease(memory);
      return nullptr;
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_androidx_media3_demo_composition_effect_HardwareBufferEffectsPipeline_nativeUnlockHardwareBuffer(
    JNIEnv* env, jobject thiz, jobject textureObj, jobject memoryLongObj, jobject fenceLongObj) {
  
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

  int outFenceFd = -1;
  if (texture) {
      WGPUSharedTextureMemoryEndAccessState endState = {};
      WGPUSharedTextureMemoryVkImageLayoutEndState endLayout{};
      endLayout.chain.sType = WGPUSType_SharedTextureMemoryVkImageLayoutEndState;
      endLayout.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      endLayout.newLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      endState.nextInChain = &endLayout.chain;

      if (wgpuSharedTextureMemoryEndAccess(memory, texture, &endState)) {
          if (endState.fenceCount > 0) {
              WGPUSharedFenceSyncFDExportInfo syncFdExportInfo = {};
              syncFdExportInfo.chain.sType = WGPUSType_SharedFenceSyncFDExportInfo;
              
              WGPUSharedFenceExportInfo exportInfo = {};
              exportInfo.nextInChain = &syncFdExportInfo.chain;
              
              wgpuSharedFenceExportInfo(endState.fences[0], &exportInfo);
              if (syncFdExportInfo.handle >= 0) {
                  outFenceFd = dup(syncFdExportInfo.handle);
              }
          }
          wgpuSharedTextureMemoryEndAccessStateFreeMembers(endState);
      } else {
          LOGE("Failed to end access to SharedTextureMemory");
      }
  }

  // Release the memory here as it was created in nativeLockHardwareBuffer and is not managed by Java.
  wgpuSharedTextureMemoryRelease(memory);

  // Release the fence if one was provided.
  if (fenceLongObj) {
      WGPUSharedFence sharedFence = reinterpret_cast<WGPUSharedFence>(env->CallLongMethod(fenceLongObj, longValue));
      if (sharedFence) {
          wgpuSharedFenceRelease(sharedFence);
      }
  }
  return outFenceFd;
}
