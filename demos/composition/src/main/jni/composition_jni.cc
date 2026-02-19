#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <webgpu/webgpu.h>
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
      return nullptr;
  }

  jclass textureClass = env->FindClass("androidx/webgpu/GPUTexture");
  if (env->ExceptionCheck()) return nullptr;
  jmethodID textureInit = env->GetMethodID(textureClass, "<init>", "(J)V");
  if (env->ExceptionCheck()) return nullptr;
  jobject textureObj = env->NewObject(textureClass, textureInit, reinterpret_cast<jlong>(texture));
  if (env->ExceptionCheck()) return nullptr;

  jclass longClass = env->FindClass("java/lang/Long");
  if (env->ExceptionCheck()) return nullptr;
  jmethodID valueOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
  if (env->ExceptionCheck()) return nullptr;
  jobject memoryObj = env->CallStaticObjectMethod(longClass, valueOf, reinterpret_cast<jlong>(memory));
  if (env->ExceptionCheck()) return nullptr;

  jobjectArray result = env->NewObjectArray(2, env->FindClass("java/lang/Object"), nullptr);
  env->SetObjectArrayElement(result, 0, textureObj);
  env->SetObjectArrayElement(result, 1, memoryObj);
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_androidx_media3_demo_composition_effect_HardwareBufferEffectsPipeline_nativeUnlockHardwareBuffer(
    JNIEnv* env, jobject thiz, jobject textureObj, jobject memoryLongObj) {
  
  if (!textureObj || !memoryLongObj) return;

  jclass textureClass = env->GetObjectClass(textureObj);
  jmethodID getHandle = env->GetMethodID(textureClass, "getHandle", "()J");
  if (env->ExceptionCheck()) return;
  WGPUTexture texture = reinterpret_cast<WGPUTexture>(env->CallLongMethod(textureObj, getHandle));

  jclass longClass = env->FindClass("java/lang/Long");
  jmethodID longValue = env->GetMethodID(longClass, "longValue", "()J");
  if (env->ExceptionCheck()) return;
  WGPUSharedTextureMemory memory = reinterpret_cast<WGPUSharedTextureMemory>(env->CallLongMethod(memoryLongObj, longValue));

  WGPUSharedTextureMemoryEndAccessState endState = {};
  WGPUSharedTextureMemoryVkImageLayoutEndState endLayout{};
  endLayout.chain.sType = WGPUSType_SharedTextureMemoryVkImageLayoutEndState;
  endState.nextInChain = &endLayout.chain;
  
  if (!wgpuSharedTextureMemoryEndAccess(memory, texture, &endState)) {
      LOGE("Failed to end access to SharedTextureMemory");
  }

  // Release the texture and memory here as they were created in nativeLockHardwareBuffer.
  wgpuTextureRelease(texture);
  wgpuSharedTextureMemoryRelease(memory);
}
