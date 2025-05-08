#ifndef DSP_NATIVE_H
#define DSP_NATIVE_H

#include <jni.h>
#include <vector>

#ifdef __cplusplus
extern "C" {
#endif

// Initialize DSP parameters
JNIEXPORT void JNICALL
Java_com_ece420_1parametric_1eq_DSPProcessor_initConfig(
        JNIEnv*, jclass,
        jint   fs,
        jfloat impulseWindow,
        jfloat markerSilence,
        jfloat sweepDuration,
        jint   sgWindow,
        jint   sgPoly);

// Core pipeline: segment, deconv, FFT->dB, smooth, normalize
JNIEXPORT jobject JNICALL
Java_com_ece420_1parametric_1eq_DSPProcessor_processBuffer(
        JNIEnv*, jclass, jfloatArray audioData);

// Normalize the smoothed response at 1kHz (standalone)
JNIEXPORT jfloatArray JNICALL
Java_com_ece420_1parametric_1eq_DSPProcessor_normalizeAt1kHz(
        JNIEnv*, jclass, jfloatArray freqs, jfloatArray db);

// (Future) apply parametric EQ
JNIEXPORT jfloatArray JNICALL
Java_com_ece420_1parametric_1eq_DSPProcessor_applyParametricEQ(
        JNIEnv*, jclass,
        jfloatArray freqs, jfloatArray rawDb,
        jfloatArray eqFreq, jfloatArray eqGain, jfloatArray eqQ);

#ifdef __cplusplus
}
#endif

#endif // DSP_NATIVE_H