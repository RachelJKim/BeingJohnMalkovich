#include "camera_encoder_bridge.h"

#include <android/log.h>
#include <media/NdkImage.h>

namespace {
constexpr const char* kLogTag = "NativeSensor.Encoder";
// YUV_420_888 format
constexpr int32_t kImageFormat = AIMAGE_FORMAT_YUV_420_888;
// Maximum images in the reader queue
constexpr int32_t kMaxImages = 4;
}

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kLogTag, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, kLogTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kLogTag, __VA_ARGS__)

namespace nativesensor {

CameraEncoderBridge::CameraEncoderBridge(CameraManager& manager)
    : manager_(manager) {
    LOGI("CameraEncoderBridge created");
}

CameraEncoderBridge::~CameraEncoderBridge() {
    stopCapture();
    LOGI("CameraEncoderBridge destroyed");
}

bool CameraEncoderBridge::startCapture(const std::string& cameraId,
                                        int32_t width, int32_t height,
                                        FrameDataCallback callback) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (capturing_.load(std::memory_order_acquire)) {
        if (currentCameraId_ == cameraId) {
            LOGI("Already capturing camera %s, skipping restart", cameraId.c_str());
            return true;
        }
        LOGI("Switching encoder from camera %s to %s", currentCameraId_.c_str(), cameraId.c_str());
        cleanup();
    }

    if (!manager_.isValid()) {
        LOGE("Cannot start capture: camera manager invalid");
        return false;
    }

    LOGI("Starting frame capture: %s (%dx%d)", cameraId.c_str(), width, height);

    frameCallback_ = std::move(callback);
    currentCameraId_ = cameraId;

    // Create AImageReader for YUV capture
    media_status_t mediaStatus = AImageReader_new(width, height, kImageFormat, kMaxImages, &imageReader_);
    if (mediaStatus != AMEDIA_OK || !imageReader_) {
        LOGE("Failed to create AImageReader: %d", mediaStatus);
        cleanup();
        return false;
    }

    // Set image available listener
    imageListener_.context = this;
    imageListener_.onImageAvailable = onImageAvailable;
    mediaStatus = AImageReader_setImageListener(imageReader_, &imageListener_);
    if (mediaStatus != AMEDIA_OK) {
        LOGE("Failed to set image listener: %d", mediaStatus);
        cleanup();
        return false;
    }

    // Get the ANativeWindow from AImageReader
    mediaStatus = AImageReader_getWindow(imageReader_, &imageReaderWindow_);
    if (mediaStatus != AMEDIA_OK || !imageReaderWindow_) {
        LOGE("Failed to get window from AImageReader: %d", mediaStatus);
        cleanup();
        return false;
    }

    // Setup device callbacks
    deviceCallbacks_.context = this;
    deviceCallbacks_.onDisconnected = onDeviceDisconnected;
    deviceCallbacks_.onError = onDeviceError;

    // Open camera device
    camera_status_t status = ACameraManager_openCamera(
        manager_.getNativeManager(),
        cameraId.c_str(),
        &deviceCallbacks_,
        &cameraDevice_);

    if (status != ACAMERA_OK || !cameraDevice_) {
        LOGE("Failed to open camera %s for encoding: %d", cameraId.c_str(), status);
        cleanup();
        return false;
    }

    LOGI("Camera device opened for encoding: %s", cameraId.c_str());

    // Create output target from image reader window
    status = ACameraOutputTarget_create(imageReaderWindow_, &outputTarget_);
    if (status != ACAMERA_OK) {
        LOGE("Failed to create output target: %d", status);
        cleanup();
        return false;
    }

    // Create capture request
    status = ACameraDevice_createCaptureRequest(cameraDevice_, TEMPLATE_RECORD, &captureRequest_);
    if (status != ACAMERA_OK) {
        LOGE("Failed to create capture request: %d", status);
        cleanup();
        return false;
    }

    // Add target to request
    status = ACaptureRequest_addTarget(captureRequest_, outputTarget_);
    if (status != ACAMERA_OK) {
        LOGE("Failed to add target to request: %d", status);
        cleanup();
        return false;
    }

    // Create session output container
    status = ACaptureSessionOutputContainer_create(&outputContainer_);
    if (status != ACAMERA_OK) {
        LOGE("Failed to create output container: %d", status);
        cleanup();
        return false;
    }

    // Create session output
    status = ACaptureSessionOutput_create(imageReaderWindow_, &sessionOutput_);
    if (status != ACAMERA_OK) {
        LOGE("Failed to create session output: %d", status);
        cleanup();
        return false;
    }

    // Add output to container
    status = ACaptureSessionOutputContainer_add(outputContainer_, sessionOutput_);
    if (status != ACAMERA_OK) {
        LOGE("Failed to add output to container: %d", status);
        cleanup();
        return false;
    }

    // Setup session callbacks
    sessionCallbacks_.context = this;
    sessionCallbacks_.onClosed = onSessionClosed;
    sessionCallbacks_.onReady = onSessionReady;
    sessionCallbacks_.onActive = onSessionActive;

    // Create capture session
    status = ACameraDevice_createCaptureSession(
        cameraDevice_,
        outputContainer_,
        &sessionCallbacks_,
        &captureSession_);

    if (status != ACAMERA_OK || !captureSession_) {
        LOGE("Failed to create capture session for encoding: %d", status);
        cleanup();
        return false;
    }

    // Start repeating capture request
    status = ACameraCaptureSession_setRepeatingRequest(
        captureSession_,
        nullptr,  // No capture callbacks needed, we use image reader
        1,
        &captureRequest_,
        nullptr);

    if (status != ACAMERA_OK) {
        LOGE("Failed to set repeating request: %d", status);
        cleanup();
        return false;
    }

    capturing_.store(true, std::memory_order_release);
    LOGI("Frame capture started: %s (%dx%d)", cameraId.c_str(), width, height);
    return true;
}

void CameraEncoderBridge::stopCapture() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!capturing_.load(std::memory_order_acquire)) {
        return;
    }
    LOGI("Stopping frame capture");
    cleanup();
}

void CameraEncoderBridge::cleanup() {
    capturing_.store(false, std::memory_order_release);

    if (captureSession_) {
        ACameraCaptureSession_stopRepeating(captureSession_);
        ACameraCaptureSession_close(captureSession_);
        captureSession_ = nullptr;
    }

    if (cameraDevice_) {
        ACameraDevice_close(cameraDevice_);
        cameraDevice_ = nullptr;
    }

    if (captureRequest_) {
        ACaptureRequest_free(captureRequest_);
        captureRequest_ = nullptr;
    }

    if (outputTarget_) {
        ACameraOutputTarget_free(outputTarget_);
        outputTarget_ = nullptr;
    }

    if (sessionOutput_) {
        ACaptureSessionOutput_free(sessionOutput_);
        sessionOutput_ = nullptr;
    }

    if (outputContainer_) {
        ACaptureSessionOutputContainer_free(outputContainer_);
        outputContainer_ = nullptr;
    }

    // Note: imageReaderWindow_ is owned by imageReader_, don't release separately
    imageReaderWindow_ = nullptr;

    if (imageReader_) {
        AImageReader_delete(imageReader_);
        imageReader_ = nullptr;
    }

    currentCameraId_.clear();
    frameCallback_ = nullptr;

    LOGI("Encoder resources cleaned up");
}

void CameraEncoderBridge::onImageAvailable(void* context, AImageReader* reader) {
    auto* self = static_cast<CameraEncoderBridge*>(context);

    AImage* image = nullptr;
    media_status_t status = AImageReader_acquireLatestImage(reader, &image);
    if (status != AMEDIA_OK || !image) {
        return;
    }

    if (!self->frameCallback_) {
        AImage_delete(image);
        return;
    }

    // Get image properties
    int32_t width = 0, height = 0;
    AImage_getWidth(image, &width);
    AImage_getHeight(image, &height);

    int64_t timestampNs = 0;
    AImage_getTimestamp(image, &timestampNs);

    // Get Y plane data (for YUV_420_888, Y plane is the largest and most useful)
    uint8_t* yData = nullptr;
    int yLen = 0;
    AImage_getPlaneData(image, 0, &yData, &yLen);

    uint8_t* uData = nullptr;
    int uLen = 0;
    AImage_getPlaneData(image, 1, &uData, &uLen);

    uint8_t* vData = nullptr;
    int vLen = 0;
    AImage_getPlaneData(image, 2, &vData, &vLen);

    if (yData && uData && vData) {
        // Get row strides and pixel strides
        int32_t yRowStride = 0, uvRowStride = 0, uvPixelStride = 0;
        AImage_getPlaneRowStride(image, 0, &yRowStride);
        AImage_getPlaneRowStride(image, 1, &uvRowStride);
        AImage_getPlanePixelStride(image, 1, &uvPixelStride);

        // Calculate I420 buffer size: Y + U + V = w*h + w*h/4 + w*h/4 = w*h*3/2
        int32_t i420Size = width * height * 3 / 2;

        // For tightly packed I420 data, pack the planes
        // Allocate on stack for small frames, heap for large
        std::vector<uint8_t> i420Buffer(static_cast<size_t>(i420Size));

        // Copy Y plane
        if (yRowStride == width) {
            memcpy(i420Buffer.data(), yData, static_cast<size_t>(width * height));
        } else {
            for (int32_t row = 0; row < height; ++row) {
                memcpy(i420Buffer.data() + row * width,
                       yData + row * yRowStride,
                       static_cast<size_t>(width));
            }
        }

        // Copy U and V planes (deinterleave if needed)
        int32_t uvWidth = width / 2;
        int32_t uvHeight = height / 2;
        uint8_t* uDst = i420Buffer.data() + width * height;
        uint8_t* vDst = uDst + uvWidth * uvHeight;

        if (uvPixelStride == 1) {
            // Already planar, just copy
            if (uvRowStride == uvWidth) {
                memcpy(uDst, uData, static_cast<size_t>(uvWidth * uvHeight));
                memcpy(vDst, vData, static_cast<size_t>(uvWidth * uvHeight));
            } else {
                for (int32_t row = 0; row < uvHeight; ++row) {
                    memcpy(uDst + row * uvWidth, uData + row * uvRowStride,
                           static_cast<size_t>(uvWidth));
                    memcpy(vDst + row * uvWidth, vData + row * uvRowStride,
                           static_cast<size_t>(uvWidth));
                }
            }
        } else {
            // Interleaved UV (NV12/NV21 style) - deinterleave
            for (int32_t row = 0; row < uvHeight; ++row) {
                for (int32_t col = 0; col < uvWidth; ++col) {
                    uDst[row * uvWidth + col] = uData[row * uvRowStride + col * uvPixelStride];
                    vDst[row * uvWidth + col] = vData[row * uvRowStride + col * uvPixelStride];
                }
            }
        }

        self->frameCallback_(i420Buffer.data(), i420Size, width, height, timestampNs);
    }

    AImage_delete(image);
}

void CameraEncoderBridge::onDeviceDisconnected(void* context, ACameraDevice* /*device*/) {
    auto* self = static_cast<CameraEncoderBridge*>(context);
    LOGI("Encoder camera device disconnected");
    self->capturing_.store(false, std::memory_order_release);
}

void CameraEncoderBridge::onDeviceError(void* context, ACameraDevice* /*device*/, int error) {
    auto* self = static_cast<CameraEncoderBridge*>(context);
    LOGE("Encoder camera device error: %d", error);
    self->capturing_.store(false, std::memory_order_release);
}

void CameraEncoderBridge::onSessionClosed(void* /*context*/, ACameraCaptureSession* /*session*/) {
    LOGI("Encoder capture session closed");
}

void CameraEncoderBridge::onSessionReady(void* /*context*/, ACameraCaptureSession* /*session*/) {
    LOGI("Encoder capture session ready");
}

void CameraEncoderBridge::onSessionActive(void* /*context*/, ACameraCaptureSession* /*session*/) {
    LOGI("Encoder capture session active");
}

}  // namespace nativesensor
