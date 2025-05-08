package com.ece420_parametric_eq.models;

import java.util.List;

public class PEQBand {
    public enum Type { PEAKING, LOW_SHELF, HIGH_SHELF }
    public final Type   type;
    public final double fc, Q, gainDb;

    public PEQBand(Type type, double fc, double Q, double gainDb) {
        this.type   = type;
        this.fc     = fc;
        this.Q      = Q;
        this.gainDb = gainDb;
    }
}

