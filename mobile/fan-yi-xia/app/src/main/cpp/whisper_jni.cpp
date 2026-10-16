#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <memory>
#include <string>
#include <thread>
#include <stdexcept>

namespace {
struct Samples {
    JNIEnv *env;
    jfloatArray array;
    jfloat *data;
    ~Samples() { if (data) env->ReleaseFloatArrayElements(array, data, JNI_ABORT); }
};
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_lystic_fanyixia_WhisperNative_transcribe(
        JNIEnv *env, jobject, jstring model_path, jfloatArray pcm, jint count) {
    try {
        if (count < 1600 || count > 16000 * 60 || count > env->GetArrayLength(pcm)) {
            throw std::runtime_error("Recording must contain 0.1 to 60 seconds of audio.");
        }
        const char *raw_path = env->GetStringUTFChars(model_path, nullptr);
        if (!raw_path) return nullptr;
        auto context_params = whisper_context_default_params();
        context_params.use_gpu = false;
        std::unique_ptr<whisper_context, decltype(&whisper_free)> context(
            whisper_init_from_file_with_params(raw_path, context_params), whisper_free);
        env->ReleaseStringUTFChars(model_path, raw_path);
        if (!context) throw std::runtime_error("Cannot load Whisper base model. Prepare models again.");
        Samples samples{env, pcm, env->GetFloatArrayElements(pcm, nullptr)};
        if (!samples.data) return nullptr;
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = static_cast<int>(std::max(1u, std::min(4u, std::thread::hardware_concurrency())));
        params.language = "zh";
        params.translate = false;
        params.no_context = true;
        params.no_timestamps = true;
        params.print_progress = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        params.print_special = false;
        if (whisper_full(context.get(), params, samples.data, count) != 0) {
            throw std::runtime_error("Offline transcription failed. Try a shorter recording.");
        }
        std::string text;
        const int segments = whisper_full_n_segments(context.get());
        for (int i = 0; i < segments; ++i) {
            text += whisper_full_get_segment_text(context.get(), i);
        }
        auto result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(text.size()),
                                          reinterpret_cast<const jbyte *>(text.data()));
        return result;
    } catch (const std::exception &error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}
