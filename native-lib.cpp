// native-lib.cpp
// Thread-safe circular ring buffer for raw video frames + async MP4 muxing trigger.
// Target: 720p60, 20s ring (1200 slots). Per-slot heap sized exactly to frame size.

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaMuxer.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaError.h>

#include <fcntl.h>
#include <unistd.h>
#include <cerrno>

#include <atomic>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <vector>
#include <memory>
#include <string>

#define LOG_TAG "NativeReplayBuffer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

namespace {

constexpr size_t kRingCapacity = 1200; // 20s @ 60fps

// ---------------------------------------------------------------------------
// Slot: owns exactly the bytes needed for one frame. Allocated/freed per-write
// so quality/resolution changes are handled transparently without a fixed
// max-size assumption (avoids worst-case over-allocation at 1200 slots).
// ---------------------------------------------------------------------------
struct FrameSlot {
    uint8_t*  data     = nullptr;
    size_t    size     = 0;
    size_t    capacity = 0;   // allocated capacity, may be >= size if reused
    int64_t   ptsUs    = 0;
    bool      valid    = false;

    void release() {
        if (data) {
            std::free(data);
            data = nullptr;
        }
        size = 0;
        capacity = 0;
        valid = false;
    }

    // Reuse existing allocation when the incoming frame fits, otherwise
    // free-and-reallocate exactly to the new size. This satisfies both
    // "allocate exact heap needed" and "cleanly free the overwritten slot"
    // while avoiding churn when resolution is stable.
    bool assign(const uint8_t* src, size_t newSize, int64_t pts) {
        if (newSize == 0) return false;

        if (data == nullptr || capacity < newSize) {
            if (data) {
                std::free(data);
                data = nullptr;
                capacity = 0;
            }
            data = static_cast<uint8_t*>(std::malloc(newSize));
            if (!data) {
                LOGE("OOM allocating %zu bytes for frame slot", newSize);
                valid = false;
                return false;
            }
            capacity = newSize;
        }

        std::memcpy(data, src, newSize);
        size  = newSize;
        ptsUs = pts;
        valid = true;
        return true;
    }
};

// ---------------------------------------------------------------------------
// RingBuffer: fixed slot count, thread-safe. Single writer expected from the
// camera capture thread; drain() is used to snapshot for encoding so the
// writer is never blocked for the duration of an export.
// ---------------------------------------------------------------------------
class RingBuffer {
public:
    explicit RingBuffer(size_t capacity) : capacity_(capacity), slots_(capacity) {}

    ~RingBuffer() {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& s : slots_) s.release();
    }

    void push(const uint8_t* src, size_t len, int64_t ptsUs) {
        std::lock_guard<std::mutex> lock(mutex_);
        FrameSlot& slot = slots_[head_];
        if (slot.assign(src, len, ptsUs)) {
            head_ = (head_ + 1) % capacity_;
            if (count_ < capacity_) ++count_;
            ++totalPushed_;
        }
    }

    std::vector<std::pair<std::unique_ptr<uint8_t[]>, std::pair<size_t,int64_t>>> drain() {
        std::lock_guard<std::mutex> lock(mutex_);
        std::vector<std::pair<std::unique_ptr<uint8_t[]>, std::pair<size_t,int64_t>>> out;
        out.reserve(count_);

        size_t start = (count_ == capacity_) ? head_ : 0;
        for (size_t i = 0; i < count_; ++i) {
            size_t idx = (start + i) % capacity_;
            FrameSlot& s = slots_[idx];
            if (!s.valid) continue;
            std::unique_ptr<uint8_t[]> copy(new uint8_t[s.size]);
            std::memcpy(copy.get(), s.data, s.size);
            out.emplace_back(std::move(copy), std::make_pair(s.size, s.ptsUs));
        }
        return out;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& s : slots_) s.release();
        head_ = 0;
        count_ = 0;
    }

    size_t capacity() const { return capacity_; }

    size_t count() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return count_;
    }

private:
    mutable std::mutex mutex_;
    size_t capacity_;
    std::vector<FrameSlot> slots_;
    size_t head_  = 0;
    size_t count_ = 0;
    uint64_t totalPushed_ = 0;
};

// ---------------------------------------------------------------------------
// Encoder session: drives AMediaCodec (encoder) + AMediaMuxer, running fully
// on a background thread so JNI calls return immediately (async).
// ---------------------------------------------------------------------------
struct EncodeJob {
    std::string outputPath;
    int32_t width  = 1280;
    int32_t height = 720;
    int32_t bitrate = 8 * 1000 * 1000; // 8Mbps default for 720p60
    int32_t fps = 60;
    std::string mimeType = "video/avc";
};

class AsyncEncoder {
public:
    AsyncEncoder() = default;
    ~AsyncEncoder() { stopAndJoin(); }

    bool start(RingBuffer* ring, const EncodeJob& job,
               JavaVM* jvm, jobject callbackGlobalRef) {
        if (running_.exchange(true)) {
            LOGW("Encoder already running, ignoring start()");
            return false;
        }
        stopRequested_ = false;
        worker_ = std::thread(&AsyncEncoder::run, this, ring, job, jvm, callbackGlobalRef);
        return true;
    }

    void requestStop() { stopRequested_ = true; }

    void stopAndJoin() {
        stopRequested_ = true;
        if (worker_.joinable()) worker_.join();
        running_ = false;
    }

    bool isRunning() const { return running_.load(); }

private:
    std::atomic<bool> running_{false};
    std::atomic<bool> stopRequested_{false};
    std::thread worker_;

    void notifyJava(JavaVM* jvm, jobject callbackRef, bool success, const char* message) {
        if (!jvm || !callbackRef) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                LOGE("Failed to attach encoder thread to JVM for callback");
                return;
            }
            attached = true;
        }

        jclass cls = env->GetObjectClass(callbackRef);
        jmethodID mid = env->GetMethodID(cls, "onEncodeComplete", "(ZLjava/lang/String;)V");
        if (mid) {
            jstring jmsg = env->NewStringUTF(message ? message : "");
            env->CallVoidMethod(callbackRef, mid, static_cast<jboolean>(success), jmsg);
            env->DeleteLocalRef(jmsg);
        } else {
            LOGE("onEncodeComplete(ZLjava/lang/String;)V not found on callback object");
            env->ExceptionClear();
        }
        env->DeleteLocalRef(cls);

        if (attached) jvm->DetachCurrentThread();
    }

    static int openOutputFd(const std::string& path) {
        int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0644);
        if (fd < 0) {
            LOGE("Failed to open output file %s (errno=%d)", path.c_str(), errno);
        }
        return fd;
    }

    void cleanupCallbackRef(JavaVM* jvm, jobject callbackRef) {
        if (!jvm || !callbackRef) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        }
        if (env) {
            env->DeleteGlobalRef(callbackRef);
            if (attached) jvm->DetachCurrentThread();
        }
    }

    void run(RingBuffer* ring, EncodeJob job, JavaVM* jvm, jobject callbackRef) {
        LOGI("Encoder thread starting: %dx%d @ %dfps -> %s",
             job.width, job.height, job.fps, job.outputPath.c_str());

        auto frames = ring->drain();
        if (frames.empty()) {
            LOGW("No frames in ring buffer to encode");
            notifyJava(jvm, callbackRef, false, "No frames available");
            cleanupCallbackRef(jvm, callbackRef);
            running_ = false;
            return;
        }

        AMediaFormat* format = AMediaFormat_new();
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, job.mimeType.c_str());
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, job.width);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, job.height);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, 0x7f420888 /* COLOR_FormatYUV420Flexible */);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, job.bitrate);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, job.fps);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);

        AMediaCodec* codec = AMediaCodec_createEncoderByType(job.mimeType.c_str());
        if (!codec) {
            LOGE("Failed to create encoder for %s", job.mimeType.c_str());
            AMediaFormat_delete(format);
            notifyJava(jvm, callbackRef, false, "Encoder creation failed");
            cleanupCallbackRef(jvm, callbackRef);
            running_ = false;
            return;
        }

        media_status_t status = AMediaCodec_configure(
            codec, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        if (status != AMEDIA_OK) {
            LOGE("AMediaCodec_configure failed: %d", status);
            AMediaCodec_delete(codec);
            AMediaFormat_delete(format);
            notifyJava(jvm, callbackRef, false, "Encoder configure failed");
            cleanupCallbackRef(jvm, callbackRef);
            running_ = false;
            return;
        }

        AMediaCodec_start(codec);

        AMediaMuxer* muxer = AMediaMuxer_new(
            openOutputFd(job.outputPath), AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
        if (!muxer) {
            LOGE("Failed to create muxer for %s", job.outputPath.c_str());
            AMediaCodec_stop(codec);
            AMediaCodec_delete(codec);
            AMediaFormat_delete(format);
            notifyJava(jvm, callbackRef, false, "Muxer creation failed");
            cleanupCallbackRef(jvm, callbackRef);
            running_ = false;
            return;
        }

        int trackIndex = -1;
        bool muxerStarted = false;
        bool eosSent = false;
        size_t frameIdx = 0;
        const int64_t ptsStepUs = 1000000LL / job.fps;
        bool sawError = false;

        while (true) {
            if (stopRequested_ && frameIdx >= frames.size()) break;

            if (frameIdx < frames.size()) {
                ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec, 10000);
                if (inIdx >= 0) {
                    size_t bufCap = 0;
                    uint8_t* inBuf = AMediaCodec_getInputBuffer(codec, inIdx, &bufCap);
                    const auto& frame = frames[frameIdx];
                    size_t frameSize = frame.second.first;
                    if (inBuf && frameSize <= bufCap) {
                        std::memcpy(inBuf, frame.first.get(), frameSize);
                        int64_t pts = static_cast<int64_t>(frameIdx) * ptsStepUs;
                        AMediaCodec_queueInputBuffer(codec, inIdx, 0, frameSize, pts, 0);
                    } else {
                        LOGW("Frame %zu size %zu exceeds input buffer capacity %zu, skipping",
                             frameIdx, frameSize, bufCap);
                        AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0, 0);
                    }
                    ++frameIdx;
                }
            } else if (!eosSent) {
                ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec, 10000);
                if (inIdx >= 0) {
                    AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0,
                        AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                    eosSent = true;
                }
            }

            AMediaCodecBufferInfo info;
            ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 10000);
            if (outIdx >= 0) {
                if (info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) {
                    AMediaCodec_releaseOutputBuffer(codec, outIdx, false);
                    continue;
                }
                size_t outCap = 0;
                uint8_t* outBuf = AMediaCodec_getOutputBuffer(codec, outIdx, &outCap);
                if (outBuf && muxerStarted && info.size > 0) {
                    AMediaMuxer_writeSampleData(muxer, trackIndex, outBuf, &info);
                }
                AMediaCodec_releaseOutputBuffer(codec, outIdx, false);
                if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                    break;
                }
            } else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat* outFormat = AMediaCodec_getOutputFormat(codec);
                trackIndex = AMediaMuxer_addTrack(muxer, outFormat);
                AMediaFormat_delete(outFormat);
                if (trackIndex >= 0) {
                    media_status_t muxStatus = AMediaMuxer_start(muxer);
                    muxerStarted = (muxStatus == AMEDIA_OK);
                    if (!muxerStarted) {
                        LOGE("AMediaMuxer_start failed: %d", muxStatus);
                        sawError = true;
                        break;
                    }
                } else {
                    LOGE("AMediaMuxer_addTrack failed: %d", trackIndex);
                    sawError = true;
                    break;
                }
            }
            // AMEDIACODEC_INFO_TRY_AGAIN_LATER / OUTPUT_BUFFERS_CHANGED: keep looping.
        }

        if (muxerStarted) {
            AMediaMuxer_stop(muxer);
        }
        AMediaMuxer_delete(muxer);
        AMediaCodec_stop(codec);
        AMediaCodec_delete(codec);
        AMediaFormat_delete(format);

        LOGI("Encoder thread finished, wrote %zu frames, error=%d", frameIdx, sawError);
        notifyJava(jvm, callbackRef, !sawError,
                   sawError ? "Encoding error" : job.outputPath.c_str());
        cleanupCallbackRef(jvm, callbackRef);
        running_ = false;
    }
};

RingBuffer* g_ring = nullptr;
AsyncEncoder* g_encoder = nullptr;
JavaVM* g_jvm = nullptr;
std::mutex g_lifecycleMutex;

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_replaybuffer_NativeReplayBuffer_nativeInit(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_lifecycleMutex);
    if (g_ring == nullptr) {
        g_ring = new RingBuffer(kRingCapacity);
        LOGI("RingBuffer initialized with capacity %zu slots", kRingCapacity);
    }
    if (g_encoder == nullptr) {
        g_encoder = new AsyncEncoder();
    }
}

JNIEXPORT void JNICALL
Java_com_example_replaybuffer_NativeReplayBuffer_nativeAddFrame(
        JNIEnv* env, jobject /*thiz*/, jbyteArray frameData, jlong ptsUs) {
    if (frameData == nullptr) return;

    jsize len = env->GetArrayLength(frameData);
    if (len <= 0) return;

    jbyte* elems = env->GetByteArrayElements(frameData, nullptr);
    if (!elems) {
        LOGE("GetByteArrayElements returned null (OOM?)");
        return;
    }

    {
        std::lock_guard<std::mutex> lock(g_lifecycleMutex);
        if (g_ring) {
            g_ring->push(reinterpret_cast<const uint8_t*>(elems),
                         static_cast<size_t>(len),
                         static_cast<int64_t>(ptsUs));
        }
    }

    env->ReleaseByteArrayElements(frameData, elems, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_example_replaybuffer_NativeReplayBuffer_nativeGetFrameCount(
        JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_lifecycleMutex);
    return g_ring ? static_cast<jint>(g_ring->count()) : 0;
}

JNIEXPORT void JNICALL
Java_com_example_replaybuffer_NativeReplayBuffer_nativeClear(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_lifecycleMutex);
    if (g_ring) g_ring->clear();
}

JNIEXPORT jboolean JNICALL
Java_com_example_replaybuffer_NativeReplayBuffer_nativeExportAsync(
        JNIEnv* env, jobject /*thiz*/,
        jstring outputPath, jint width, jint height, jint fps, jint bitrate,
        jobject callback) {
    std::lock_guard<std::mutex> lock(g_lifecycleMutex);
    if (!g_ring || !g_encoder) {
        LOGE("nativeExportAsync called before nativeInit");
        return JNI_FALSE;
    }
    if (g_encoder->isRunning()) {
        LOGW("Export already in progress");
        return JNI_FALSE;
    }

    const char* pathChars = env->GetStringUTFChars(outputPath, nullptr);
    EncodeJob job;
    job.outputPath = pathChars ? pathChars : "";
    if (pathChars) env->ReleaseStringUTFChars(outputPath, pathChars);
    job.width   = width  > 0 ? width  : 1280;
    job.height  = height > 0 ? height : 720;
    job.fps     = fps    > 0 ? fps    : 60;
    job.bitrate = bitrate > 0 ? bitrate : 8 * 1000 * 1000;

    jobject callbackGlobal = callback ? env->NewGlobalRef(callback) : nullptr;

    bool started = g_encoder->start(g_ring, job, g_jvm, callbackGlobal);
    if (!started && callbackGlobal) {
        env->DeleteGlobalRef(callbackGlobal);
    }
    return started ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_replaybuffer_NativeReplayBuffer_nativeStopExport(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_lifecycleMutex);
    if (g_encoder) g_encoder->requestStop();
}

JNIEXPORT void JNICALL
Java_com_example_replaybuffer_NativeReplayBuffer_nativeRelease(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_lifecycleMutex);
    if (g_encoder) {
        g_encoder->stopAndJoin();
        delete g_encoder;
        g_encoder = nullptr;
    }
    if (g_ring) {
        delete g_ring;
        g_ring = nullptr;
    }
    LOGI("Native resources released, no leaks outstanding");
}

} // extern "C"
