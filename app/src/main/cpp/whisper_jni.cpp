#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL Java_com_offline_stt_WhisperEngine_nInit(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL Java_com_offline_stt_WhisperEngine_nFree(JNIEnv*, jobject, jlong h) {
    if (h) whisper_free((whisper_context*) h);
}

// Returns UTF-8 bytes (decoded in Kotlin, avoids JNI crashes on split multibyte chars).
JNIEXPORT jbyteArray JNICALL Java_com_offline_stt_WhisperEngine_nRun(JNIEnv* env, jobject, jlong h, jfloatArray audio, jint threads, jint audio_ctx) {
    auto* ctx = (whisper_context*) h;
    jsize n = env->GetArrayLength(audio);
    jfloat* d = env->GetFloatArrayElements(audio, nullptr);
    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.language = "en";
    wp.translate = false;
    wp.n_threads = threads;
    wp.no_context = true;
    wp.no_timestamps = true;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.greedy.best_of = 1;
    if (audio_ctx > 0) {            // live dictation: one short utterance
        wp.audio_ctx = audio_ctx;
        wp.single_segment = true;
        wp.temperature_inc = 0.0f;  // no slow re-decode fallbacks
    }
    int rc = whisper_full(ctx, wp, d, n);
    env->ReleaseFloatArrayElements(audio, d, JNI_ABORT);
    std::string out;
    if (rc == 0) {
        int ns = whisper_full_n_segments(ctx);
        for (int i = 0; i < ns; i++) out += whisper_full_get_segment_text(ctx, i);
    }
    jbyteArray r = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(r, 0, (jsize) out.size(), (const jbyte*) out.data());
    return r;
}

}
