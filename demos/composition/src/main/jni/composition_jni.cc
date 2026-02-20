#include <jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <webgpu/webgpu_cpp.h>
#include <cstring>
#include <mutex>
#include <vector>

#define LOG_TAG "composition_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#ifndef VK_IMAGE_LAYOUT_UNDEFINED
#define VK_IMAGE_LAYOUT_UNDEFINED 0
#endif

static wgpu::Instance instance;
static wgpu::Device device;
static std::mutex g_mutex;

bool InitializeWebGPU() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (device) return true;

    // Enable TimedWaitAny to allow blocking WaitAny calls.
    wgpu::InstanceFeatureName instanceFeatures[] = {wgpu::InstanceFeatureName::TimedWaitAny};
    wgpu::InstanceDescriptor instanceDesc = {};
    instanceDesc.requiredFeatureCount = 1;
    instanceDesc.requiredFeatures = instanceFeatures;

    instance = wgpu::CreateInstance(&instanceDesc);
    if (!instance) {
        LOGE("Failed to create WebGPU instance");
        return false;
    }

    wgpu::Adapter adapter;
    wgpu::Future adapterFuture = instance.RequestAdapter(
        nullptr,
        wgpu::CallbackMode::WaitAnyOnly,
        [](wgpu::RequestAdapterStatus status, wgpu::Adapter adapter, wgpu::StringView message, wgpu::Adapter* userdata) {
            if (status == wgpu::RequestAdapterStatus::Success) {
                *userdata = std::move(adapter);
            } else {
                LOGE("Failed to request adapter: %.*s", static_cast<int>(message.length), message.data);
            }
        },
        &adapter);

    instance.WaitAny(adapterFuture, UINT64_MAX);
    if (!adapter) return false;

    std::vector<wgpu::FeatureName> features;
    if (adapter.HasFeature(wgpu::FeatureName::SharedTextureMemoryAHardwareBuffer)) {
        features.push_back(wgpu::FeatureName::SharedTextureMemoryAHardwareBuffer);
    } else {
        LOGE("Adapter does not support SharedTextureMemoryAHardwareBuffer");
        return false;
    }
    
    // Also request synchronization features if available
    if (adapter.HasFeature(wgpu::FeatureName::SharedFenceSyncFD)) {
        features.push_back(wgpu::FeatureName::SharedFenceSyncFD);
    }

    wgpu::DeviceDescriptor deviceDesc = {};
    deviceDesc.requiredFeatureCount = features.size();
    deviceDesc.requiredFeatures = features.data();
    deviceDesc.SetUncapturedErrorCallback(
        [](const wgpu::Device& device, wgpu::ErrorType type, wgpu::StringView message) {
            LOGE("WebGPU Uncaptured Error: %.*s", static_cast<int>(message.length), message.data);
        });

    wgpu::Future deviceFuture = adapter.RequestDevice(
        &deviceDesc,
        wgpu::CallbackMode::WaitAnyOnly,
        [](wgpu::RequestDeviceStatus status, wgpu::Device device, wgpu::StringView message, wgpu::Device* userdata) {
            if (status == wgpu::RequestDeviceStatus::Success) {
                *userdata = std::move(device);
            } else {
                LOGE("Failed to request device: %.*s", static_cast<int>(message.length), message.data);
            }
        },
        &device);

    instance.WaitAny(deviceFuture, UINT64_MAX);
    if (!device) return false;

    return true;
}

extern "C" JNIEXPORT void JNICALL
Java_androidx_media3_demo_composition_effect_HardwareBufferEffectsPipeline_nativeModifyHardwareBuffer(
    JNIEnv* env, jobject thiz, jobject hardwareBuffer) {
  
  if (!InitializeWebGPU()) {
      return;
  }

  AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
  if (!hb) {
    LOGE("Failed to get AHardwareBuffer from jobject");
    return;
  }

  AHardwareBuffer_Desc hbDesc;
  AHardwareBuffer_describe(hb, &hbDesc);

  wgpu::SharedTextureMemoryAHardwareBufferDescriptor stmAHardwareBufferDesc;
  stmAHardwareBufferDesc.handle = hb;

  wgpu::SharedTextureMemoryDescriptor stmDesc;
  stmDesc.nextInChain = &stmAHardwareBufferDesc;
  stmDesc.label = "Imported HardwareBuffer";

  wgpu::SharedTextureMemory memory = device.ImportSharedTextureMemory(&stmDesc);
  if (!memory) {
      LOGE("Failed to import SharedTextureMemory");
      return;
  }

  wgpu::Texture texture = memory.CreateTexture();
  if (!texture) {
      LOGE("Failed to create texture from SharedTextureMemory");
      return;
  }

  wgpu::CommandEncoder encoder = device.CreateCommandEncoder();
  
  wgpu::RenderPassColorAttachment colorAttachment;
  colorAttachment.view = texture.CreateView();
  colorAttachment.loadOp = wgpu::LoadOp::Clear;
  colorAttachment.storeOp = wgpu::StoreOp::Store;
  colorAttachment.clearValue = {0.0, 1.0, 0.0, 1.0}; // Green clear

  wgpu::RenderPassDescriptor passDesc;
  passDesc.colorAttachmentCount = 1;
  passDesc.colorAttachments = &colorAttachment;

  wgpu::RenderPassEncoder pass = encoder.BeginRenderPass(&passDesc);
  pass.End();

  wgpu::CommandBuffer commandBuffer = encoder.Finish();

  wgpu::SharedTextureMemoryBeginAccessDescriptor beginDesc = {};
  wgpu::SharedTextureMemoryVkImageLayoutBeginState beginLayout{};
  beginLayout.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  beginLayout.newLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  beginDesc.nextInChain = &beginLayout;
  
  if (!memory.BeginAccess(texture, &beginDesc)) {
      LOGE("Failed to begin access to SharedTextureMemory");
      return;
  }

  device.GetQueue().Submit(1, &commandBuffer);

  wgpu::Future future = device.GetQueue().OnSubmittedWorkDone(
      wgpu::CallbackMode::WaitAnyOnly,
      [](wgpu::QueueWorkDoneStatus status, wgpu::StringView message) {
          if (status != wgpu::QueueWorkDoneStatus::Success) {
              LOGE("OnSubmittedWorkDone failed: %.*s", static_cast<int>(message.length), message.data);
          }
      });

  wgpu::SharedTextureMemoryEndAccessState endState = {};
  wgpu::SharedTextureMemoryVkImageLayoutEndState endLayout{};
  endState.nextInChain = &endLayout;
  if (!memory.EndAccess(texture, &endState)) {
      LOGE("Failed to end access to SharedTextureMemory");
  }

  instance.WaitAny(future, UINT64_MAX);
}
