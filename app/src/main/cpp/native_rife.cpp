#include "rife_engine.h"
#include <jni.h>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
void fail(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("com/caneretabito/motionpin/ai/NativeRifeException");
    if (type) env->ThrowNew(type, message);
}
struct NativeSession {
    explicit NativeSession(const std::string& model_dir) : engine(model_dir) {}
    RifeEngine engine;
    std::vector<std::unique_ptr<uint8_t[]>> frame_buffers;
};
NativeSession* session(jlong handle) {
    if (!handle) throw std::invalid_argument("RIFE engine is closed");
    return reinterpret_cast<NativeSession*>(handle);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_caneretabito_motionpin_ai_NativeRife_nativeCreate(JNIEnv* env, jobject, jstring path) {
    try {
        struct UtfChars {
            JNIEnv* env;
            jstring path;
            const char* value;
            ~UtfChars() { if (value) env->ReleaseStringUTFChars(path, value); }
        } utf{env, path, env->GetStringUTFChars(path, nullptr)};
        if (!utf.value) return 0; // JVM exception (e.g. OOM) is already pending.
        const std::string model_dir(utf.value);
        return reinterpret_cast<jlong>(new NativeSession(model_dir));
    }
    catch (const std::bad_alloc&) { fail(env, "Insufficient memory for RIFE model; original quality retained"); }
    catch (const std::exception& error) { fail(env, error.what()); }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_caneretabito_motionpin_ai_NativeRife_nativeDeviceName(JNIEnv* env, jobject, jlong handle) {
    try { return env->NewStringUTF(session(handle)->engine.device_name().c_str()); }
    catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_caneretabito_motionpin_ai_NativeRife_nativeInterpolate(JNIEnv* env, jobject, jlong handle,
    jobject first, jobject second, jobject output, jint width, jint height, jfloat timestep) {
    try {
        const jlong bytes = static_cast<jlong>(width) * height * 3;
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384 || bytes <= 0 ||
            env->GetDirectBufferCapacity(first) < bytes || env->GetDirectBufferCapacity(second) < bytes ||
            env->GetDirectBufferCapacity(output) < bytes) throw std::invalid_argument("Direct RGB8 buffers are too small");
        auto* a = static_cast<uint8_t*>(env->GetDirectBufferAddress(first));
        auto* b = static_cast<uint8_t*>(env->GetDirectBufferAddress(second));
        auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(output));
        if (out == a || out == b) throw std::invalid_argument("RIFE output must not alias an input");
        session(handle)->engine.interpolate(a, b, out, width, height, timestep);
    } catch (const std::bad_alloc&) { fail(env, "Insufficient memory for original-resolution AI; no resize was applied"); }
      catch (const std::exception& error) { fail(env, error.what()); }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_caneretabito_motionpin_ai_NativeRife_nativeAllocateFrameBuffer(JNIEnv* env, jobject, jlong handle, jint bytes) {
    try {
        NativeSession* owner = session(handle);
        if (bytes <= 0 || bytes > 16384 * 16384 * 3) throw std::invalid_argument("Invalid RGB frame allocation size");
        if (owner->frame_buffers.size() >= 3) throw std::invalid_argument("Only three RGB frame buffers are allowed per engine");
        auto memory = std::make_unique<uint8_t[]>(bytes);
        jobject buffer = env->NewDirectByteBuffer(memory.get(), bytes);
        if (!buffer) return nullptr; // JVM exception is pending, local memory freed.
        owner->frame_buffers.push_back(std::move(memory));
        return buffer;
    } catch (const std::bad_alloc&) { fail(env, "Insufficient memory for original-resolution frame buffers"); }
      catch (const std::exception& error) { fail(env, error.what()); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_caneretabito_motionpin_ai_NativeRife_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<NativeSession*>(handle);
}
