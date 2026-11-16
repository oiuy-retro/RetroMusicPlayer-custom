#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <vector>
#include "ebur128.h"

#define LOG_TAG "RetroLoudness"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_code_name_monkey_retromusic_loudness_LoudnessNative_nativeInit(
        JNIEnv *, jclass, jint channels, jint sampleRate) {
    if (channels <= 0 || sampleRate <= 0) return 0;
    ebur128_state *st = ebur128_init(
            (unsigned) channels,
            (unsigned long) sampleRate,
            EBUR128_MODE_I | EBUR128_MODE_TRUE_PEAK | EBUR128_MODE_HISTOGRAM);
    return reinterpret_cast<jlong>(st);
}

extern "C" JNIEXPORT void JNICALL
Java_code_name_monkey_retromusic_loudness_LoudnessNative_nativeAddFrames(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples, jint frames) {
    auto *st = reinterpret_cast<ebur128_state *>(handle);
    if (!st || frames <= 0) return;
    jfloat *buf = env->GetFloatArrayElements(samples, nullptr);
    if (!buf) return;
    ebur128_add_frames_float(st, buf, (size_t) frames);
    env->ReleaseFloatArrayElements(samples, buf, JNI_ABORT);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_code_name_monkey_retromusic_loudness_LoudnessNative_nativeIntegratedLoudness(
        JNIEnv *, jclass, jlong handle) {
    auto *st = reinterpret_cast<ebur128_state *>(handle);
    if (!st) return NAN;
    double loudness = NAN;
    if (ebur128_loudness_global(st, &loudness) != EBUR128_SUCCESS) return NAN;
    return loudness;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_code_name_monkey_retromusic_loudness_LoudnessNative_nativeTruePeak(
        JNIEnv *, jclass, jlong handle) {
    auto *st = reinterpret_cast<ebur128_state *>(handle);
    if (!st) return NAN;
    double peak = 0.0;
    double maxPeak = 0.0;
    for (unsigned ch = 0; ch < st->channels; ++ch) {
        if (ebur128_true_peak(st, ch, &peak) == EBUR128_SUCCESS) {
            if (peak > maxPeak) maxPeak = peak;
        }
    }
    if (maxPeak <= 0.0) return NAN;
    return 20.0 * std::log10(maxPeak);
}

extern "C" JNIEXPORT void JNICALL
Java_code_name_monkey_retromusic_loudness_LoudnessNative_nativeDestroy(
        JNIEnv *, jclass, jlong handle) {
    auto *st = reinterpret_cast<ebur128_state *>(handle);
    if (st) ebur128_destroy(&st);
}
