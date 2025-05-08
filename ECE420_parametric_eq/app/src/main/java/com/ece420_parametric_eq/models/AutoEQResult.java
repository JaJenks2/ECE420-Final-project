package com.ece420_parametric_eq.models;

import java.util.List;

public class AutoEQResult {
    public final double preampDb;
    public final List<PEQBand> bands;

    public AutoEQResult(double preampDb, List<PEQBand> bands) {
        this.preampDb = preampDb;
        this.bands    = bands;
    }
}
