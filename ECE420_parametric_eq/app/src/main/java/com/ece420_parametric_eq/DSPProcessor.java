package com.ece420_parametric_eq;

import com.ece420_parametric_eq.models.AnalysisResult;

public class DSPProcessor {
    static { System.loadLibrary("ece420_parametric_eq"); }

    public static native void initConfig(
            int   fs,
            float impulseWindow,
            float markerSilence,
            float sweepDuration,
            int   sgWindow,
            int   sgPoly
    );

    /** runs the whole pipeline: segment to deconv to FFT to dB to smooth to normalize */
    public static native AnalysisResult processBuffer(float[] audioData);

    /** normalize call */
    public static native float[] normalizeAt1kHz(float[] freqs, float[] db);

    /**
     * Applies peaking & shelf filters to raw_dB, returns final EQ curve.
     * eqFreq/eqGain/eqQ are parallel arrays the same length as freqs.
     */
    public static native float[] applyParametricEQ(
            float[] freqs, float[] rawDb,
            float[] eqFreq,  float[] eqGain,  float[] eqQ
    );
}