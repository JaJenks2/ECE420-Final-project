#include "dsp_native.h"
#include <jni.h>
#include <vector>
#include <cmath>
#include <algorithm>
#include "kiss_fft/kiss_fft.h"
#include "kiss_fft/kiss_fftr.h"
#include <cassert>

// ── Helper: next power of two ────────────────────────────────────────────────
static int nextPow2(int v) {
    int p = 1;
    while (p < v) p <<= 1;
    return p;
}

// ── Farina inverse filter ─────────────────────────────────────────────────
// Generate exponential sweep, invert, fade, normalize
static std::vector<float> computeInverseFilter(
        int f1, int f2, float duration, int fs
) {
    int N = int(duration * fs);
    std::vector<double> sweep(N);
    double alpha = std::log(double(f2)/f1) / duration;
    double K     = 2*M_PI*f1 * (duration/ std::log(double(f2)/f1));
    // generate sweep
    for (int i = 0; i < N; i++) {
        double t = i / double(fs);
        sweep[i] = std::sin( K * (std::exp(alpha*t) - 1.0) );
    }
    // fade
    int fade = int(0.005 * fs);
    if (2*fade < N) {
        for (int i = 0; i < fade; i++) {
            double w = double(i)/fade;
            sweep[i]   *= w;
            sweep[N-1-i] *= w;
        }
    }
// normalize sweep
    double mx = 0.0;
    for (double v : sweep) {
        double av = std::abs(v);
        if (av > mx) mx = av;
    }
    if (mx > 1e-12) {
        for (auto &v : sweep)
            v /= mx;
    }
    // reverse + exponential
    std::vector<float> invf(N);
    for (int i = 0; i < N; i++) {
        double rev = sweep[N-1-i];
        double factor = std::exp(alpha * ((N-1-i)/double(fs)));
        invf[i] = float(rev * factor);
    }
    // fade inverse
    if (2*fade < N) {
        for (int i = 0; i < fade; i++) {
            double w = double(i)/fade;
            invf[i]   *= w;
            invf[N-1-i] *= w;
        }
    }
// normalize invf
    mx = 0.0;
    for (float v : invf) {
        double av = std::abs(v);
        if (av > mx) mx = av;
    }
    if (mx > 1e-12) {
        for (auto &v : invf)
            v /= mx;
    }
    return invf;
}

// ── Segmentation: single segment ─────────────────────────────────
static std::vector<std::vector<float>> segmentRecording(
        const std::vector<float>& audio
) {
    // implement segmenting with full multi‐peak detection later, since not needed now lol
    return { audio };
}

// ── Deconvolution via FFT convolution ────────────────────────────────────
static std::vector<float> deconvolveSweep(
        const std::vector<float>& segment,
        const std::vector<float>& invf,
        int fs, float impulseWindow
) {
    int n1   = int(segment.size());
    int n2   = int(invf.size());
    int outN = n1 + n2 - 1;
    int Nfft = nextPow2(outN);

    // zero‐pad inputs
    std::vector<kiss_fft_scalar> xa(Nfft,0), ib(Nfft,0);
    for (int i = 0; i < n1; ++i) xa[i] = segment[i];
    for (int i = 0; i < n2; ++i) ib[i] = invf[i];

    // Allocate forward & inverse FFT configs
    kiss_fftr_cfg cfg_fwd = kiss_fftr_alloc(Nfft, 0, nullptr, nullptr);
    kiss_fftr_cfg cfg_inv = kiss_fftr_alloc(Nfft, 1, nullptr, nullptr);

    // Forward real FFT
    std::vector<kiss_fft_cpx> Xa(Nfft/2+1), Ib(Nfft/2+1), Y(Nfft/2+1);
    kiss_fftr(cfg_fwd, xa.data(), Xa.data());
    kiss_fftr(cfg_fwd, ib.data(), Ib.data());

    // Multiply spectra (convolution in time)
    for (int i = 0; i < int(Y.size()); i++) {
        auto &a = Xa[i], &b = Ib[i], &c = Y[i];
        c.r = a.r*b.r - a.i*b.i;
        c.i = a.r*b.i + a.i*b.r;
    }

    // Inverse real FFT to get full convolved signal
    std::vector<kiss_fft_scalar> y(Nfft);
    kiss_fftri(cfg_inv, Y.data(), y.data());

    // Free FFT configs
    free(cfg_fwd);
    free(cfg_inv);

    // Extract impulse‐response window around the peak
    int win    = int(impulseWindow * fs);
    double peak = 0; int pidx = 0;
    for (int i = 0; i < outN; i++) {
        double v = std::abs(y[i]);
        if (v > peak) { peak = v; pidx = i; }
    }
    int start = std::max(0, pidx - win/2);
    int end   = std::min(outN, start + win);
    int M     = end - start;

    std::vector<float> ir(M);
    // Hann window & copy
    for (int i = 0; i < M; i++) {
        double w = 0.5 * (1 - std::cos(2*M_PI*i/(M-1)));
        ir[i]    = float(y[start + i] * w);
    }

    // Normalize IR
    double norm = 0.0;
    for (float v : ir) {
        double av = std::abs(v);
        if (av > norm) norm = av;
    }
    if (norm > 1e-12) {
        for (auto &v : ir) v /= norm;
    }

    return ir;
}

// ── Compute frequency response (RFFT to mag to dB) ────────────────────────────
static std::vector<float> computeFrequencyResponse(
        const std::vector<float>& ir
) {
    int N = ir.size();
    int Nfft = nextPow2(N);
    std::vector<kiss_fft_scalar> buf(Nfft,0);
    for (int i=0; i<N; i++) buf[i] = ir[i];
    kiss_fftr_cfg cfg = kiss_fftr_alloc(Nfft, 0, nullptr, nullptr);
    std::vector<kiss_fft_cpx> out(Nfft/2+1);
    kiss_fftr(cfg, buf.data(), out.data());
    free(cfg);
    std::vector<float> db(Nfft/2+1);
    for (int i=0; i<db.size(); i++) {
        double mag = std::hypot(out[i].r, out[i].i);
        db[i] = 20.0f * log10f(float(mag + 1e-12));
    }
    return db;
}

// ── Smooth (didnt end up doing) ────────────────────────────────────────────────
static std::vector<float> smoothResponse(const std::vector<float>& resp) {
    return resp; // doo later if needed
}

// ── Normalize at 1 kHz ────────────────────────────────────────────────────
static std::vector<float> normalizeAt1kHz(
        const std::vector<float>& freqs,
        const std::vector<float>& resp
) {
    // find index closest to 1000 Hz
    assert(freqs.size() == resp.size());
    int idx=0; double best=1e12;
    for (int i=0; i<freqs.size(); i++) {
        double d = std::abs(freqs[i] - 1000.0f);
        if (d<best) { best=d; idx=i; }
    }
    float offset = resp[idx];
    std::vector<float> out(resp.size());
    for (int i=0; i<resp.size(); i++) out[i] = resp[i] - offset; // subtract offset from all values
    return out;
}

// ── Globals ─────────────────────────────────────────────────────────────────
static int   g_fs;
static float g_impulseWindow;
static int   g_sgWindow, g_sgPoly;
static float g_markerSilence = 0.5f;   // seconds between marker & sweep
static float g_sweepDuration = 4.0f;   // length of each sweep

// ── JNI exports ──────────────────────────────────────────────────────────────
extern "C" JNIEXPORT void JNICALL
Java_com_ece420_1parametric_1eq_DSPProcessor_initConfig(
        JNIEnv*, jclass,
        jint   fs,
        jfloat iw,
        jfloat mSilence,
        jfloat swDur,
        jint   sgW,
        jint   sgP
) {
    g_fs              = fs;
    g_impulseWindow   = iw;
    g_markerSilence   = mSilence;
    g_sweepDuration   = swDur;
    g_sgWindow        = sgW;
    g_sgPoly          = sgP;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_ece420_1parametric_1eq_DSPProcessor_processBuffer(
        JNIEnv* env, jclass, jfloatArray audioData
) {
// load audio
    jsize len = env->GetArrayLength(audioData);
    jfloat* buf = env->GetFloatArrayElements(audioData, nullptr);
    std::vector<float> audio(buf, buf + len);
    env->ReleaseFloatArrayElements(audioData, buf, 0);

// compute inverse filter
    auto invf = computeInverseFilter(10,21000,g_sweepDuration,g_fs);

// segment
    auto segs = segmentRecording(audio);

// deconv & average (single segment for now)
    auto ir = deconvolveSweep(segs[0], invf, g_fs, g_impulseWindow);

// freq response
    auto freqDb = computeFrequencyResponse(ir);

// smooth
    auto smoothDb = smoothResponse(freqDb);

// normalize
    auto normDb = normalizeAt1kHz(freqDb, smoothDb);

// build freq axis (0…fs/2)
    int M = freqDb.size();
    std::vector<float> freqs(M);
    for (int i=0; i<M; i++) {
        freqs[i] = (g_fs/2.0f) * i / (M-1);
    }

// to jfloatArray
    auto toArr = [&](const std::vector<float>& v){
        jfloatArray a = env->NewFloatArray(v.size());
        env->SetFloatArrayRegion(a, 0, v.size(), v.data());
        return a;
    };
    jfloatArray jF = toArr(freqs),
            jR = toArr(freqDb),
            jS = toArr(smoothDb);

// return AnalysisResult(freqs, rawDb, smoothDb)
    jclass cls = env->FindClass("com/ece420_parametric_eq/models/AnalysisResult");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([F[F[F)V");
    return env->NewObject(cls, ctor, jF, jR, jS);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ece420_1parametric_1eq_DSPProcessor_normalizeAt1kHz(
        JNIEnv* env, jclass, jfloatArray freqsArr, jfloatArray dbArr
) {
// unpack
    jsize N = env->GetArrayLength(freqsArr);
    std::vector<float> freqs(N), db(N);
    env->GetFloatArrayRegion(freqsArr, 0, N, freqs.data());
    env->GetFloatArrayRegion(dbArr, 0, N, db.data());
    auto out = normalizeAt1kHz(freqs, db);
    jfloatArray a = env->NewFloatArray(N);
    env->SetFloatArrayRegion(a, 0, N, out.data());
    return a;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ece420_1parametric_1eq_DSPProcessor_applyParametricEQ(
        JNIEnv*, jclass, jfloatArray freqs, jfloatArray rawDb,
        jfloatArray eqFreq,  jfloatArray eqGain,  jfloatArray eqQ
) {

    return rawDb;
}