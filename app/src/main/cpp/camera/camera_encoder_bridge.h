#pragma once

#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCaptureRequest.h>
#include <media/NdkImageReader.h>
#include <android/native_window.h>
#include <functional>
#include <memory>
#include <atomic>
#include <mutex>
#include <string>

#include "camera_data.h"
#include "camera_manager.h"

namespace nativesensor {

/// Callback for YUV frame data ready for encoding
/// Parameters: data pointer, data size, width, height, timestamp_ns
using FrameDataCallback = std::function<void(const uint8_t* data, int32_t size,
                                              int32_t width, int32_t height,
                                              int64_t timestampNs)>;

/// Camera stream that captures frames via AImageReader for encoding/streaming.
/// Operates independently from the preview CameraStream - uses its own camera session.
class CameraEncoderBridge {
public:
    explicit CameraEncoderBridge(CameraManager& manager);
    ~CameraEncoderBridge();

    CameraEncoderBridge(const CameraEncoderBridge&) = delete;
    CameraEncoderBridge& operator=(const CameraEncoderBridge&) = delete;

    /// Start capturing frames from a camera for encoding
    /// @param cameraId Camera to capture from
    /// @param width Desired capture width
    /// @param height Desired capture height
    /// @param callback Callback invoked with each frame's YUV data
    /// @return true if capture started successfully
    bool startCapture(const std::string& cameraId, int32_t width, int32_t height,
                      FrameDataCallback callback);

    /// Stop capturing and release resources
    void stopCapture();

    /// Check if currently capturing
    [[nodiscard]]
    bool isCapturing() const { return capturing_.load(std::memory_order_acquire); }

private:
    // AImageReader callback
    static void onImageAvailable(void* context, AImageReader* reader);

    // Camera device callbacks
    static void onDeviceDisconnected(void* context, ACameraDevice* device);
    static void onDeviceError(void* context, ACameraDevice* device, int error);

    // Capture session callbacks
    static void onSessionClosed(void* context, ACameraCaptureSession* session);
    static void onSessionReady(void* context, ACameraCaptureSession* session);
    static void onSessionActive(void* context, ACameraCaptureSession* session);

    void cleanup();

    CameraManager& manager_;
    mutable std::mutex mutex_;
    std::atomic<bool> capturing_{false};
    std::string currentCameraId_;

    // NDK handles
    ACameraDevice* cameraDevice_ = nullptr;
    ACameraCaptureSession* captureSession_ = nullptr;
    ACaptureSessionOutputContainer* outputContainer_ = nullptr;
    ACaptureSessionOutput* sessionOutput_ = nullptr;
    ACameraOutputTarget* outputTarget_ = nullptr;
    ACaptureRequest* captureRequest_ = nullptr;
    AImageReader* imageReader_ = nullptr;
    ANativeWindow* imageReaderWindow_ = nullptr;

    // Frame callback
    FrameDataCallback frameCallback_;

    // Callback structs (must persist for camera session lifetime)
    ACameraDevice_StateCallbacks deviceCallbacks_{};
    ACameraCaptureSession_stateCallbacks sessionCallbacks_{};
    AImageReader_ImageListener imageListener_{};
};

}  // namespace nativesensor
