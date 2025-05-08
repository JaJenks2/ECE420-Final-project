package com.ece420_parametric_eq.models;

public class  AnalysisResult {
    public final float[] freqs;     // X‑axis: frequencies
    public final float[] rawDb;     // raw curve in dB
    public final float[] smoothDb;  // smoothed/normalized curve
    public AnalysisResult(float[] freqs, float[] rawDb, float[] smoothDb) {
        this.freqs    = freqs;
        this.rawDb    = rawDb;
        this.smoothDb = smoothDb;
    }
}
