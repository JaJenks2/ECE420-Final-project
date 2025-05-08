package com.ece420_parametric_eq;

import java.io.*;
import java.util.*;
import java.util.Locale;
import java.util.Arrays;
import org.apache.commons.math3.complex.Complex;
import com.ece420_parametric_eq.models.PEQBand;

/**
 * Utility methods for parsing AutoEQ output and computing parametric EQ filters in Java.
 */
public class ParametricEQUtils {

    /**
     * Result holder for AutoEQ output: global preamp offset plus list of filter bands.
     */
    public static class AutoEQResult {
        public final double preampDb;
        public final List<PEQBand> bands;

        public AutoEQResult(double preampDb, List<PEQBand> bands) {
            this.preampDb = preampDb;
            this.bands = bands;
        }
    }

    /**
     * Parses an AutoEQ output file (speaker_eq.txt) to extract the global preamp and all filter bands.
     * @param eqFile AutoEQ output
     * @return AutoEQResult containing preampDb and List<PEQBand>
     */
    public static AutoEQResult loadAutoEQResult(File eqFile) throws IOException {
        double preampDb = 0.0;
        List<PEQBand> bands = new ArrayList<>();
        try (BufferedReader rd = new BufferedReader(new FileReader(eqFile))) {
            String line;
            while ((line = rd.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String upper = line.toUpperCase(Locale.ROOT);

                // Preamp
                if (upper.startsWith("PREAMP:")) {
                    String val = line.replaceAll("(?i)preamp:|dB", "").trim();
                    try {
                        preampDb = Double.parseDouble(val);
                    } catch (NumberFormatException ignored) {}
                    continue;
                }

                // Only ON filters
                if (!upper.startsWith("FILTER") || upper.contains("OFF")) {
                    continue;
                }

                // Determine type by searching for LSC or HSC anywhere
                PEQBand.Type type;
                if (upper.contains("LSC")) {
                    type = PEQBand.Type.LOW_SHELF;
                } else if (upper.contains("HSC")) {
                    type = PEQBand.Type.HIGH_SHELF;
                } else {
                    type = PEQBand.Type.PEAKING;
                }

                // Pull out Fc, Gain, Q
                String[] parts = line.split("\\s+");
                double fc = Double.NaN, gain = Double.NaN, Q = Double.NaN;
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].equalsIgnoreCase("Fc") && i+1 < parts.length) {
                        fc = Double.parseDouble(parts[i+1]);
                    } else if (parts[i].equalsIgnoreCase("Gain") && i+1 < parts.length) {
                        gain = Double.parseDouble(parts[i+1]);
                    } else if (parts[i].equalsIgnoreCase("Q") && i+1 < parts.length) {
                        Q = Double.parseDouble(parts[i+1]);
                    }
                }

                // Only add if all three parsed ok
                if (!Double.isNaN(fc) && !Double.isNaN(gain) && !Double.isNaN(Q)) {
                    bands.add(new PEQBand(type, fc, Q, gain));
                }
            }
        }
        return new AutoEQResult(preampDb, bands);
    }


    /**
     * Convenience method to return only the band list from AutoEQ output.
     */
    public static List<PEQBand> loadAutoEQBands(File eqFile) throws IOException {
        return loadAutoEQResult(eqFile).bands;
    }

    /**
     * Reads a simple two-column text file (freq, dB) into arrays.
     * @return [0]=frequencies[], [1]=dB values[]
     */
    public static double[][] loadFrequencyResponse(File txtFile) throws IOException {
        List<Double> freqs = new ArrayList<>(), dbs = new ArrayList<>();
        try (BufferedReader rd = new BufferedReader(new FileReader(txtFile))) {
            String line;
            while ((line = rd.readLine()) != null) {
                String[] p = line.trim().split("[,\\s]+");
                if (p.length < 2) continue;
                try {
                    freqs.add(Double.parseDouble(p[0]));
                    dbs.add(Double.parseDouble(p[1]));
                } catch (NumberFormatException ignored) {}
            }
        }
        double[] fArr = freqs.stream().mapToDouble(d -> d).toArray();
        double[] dArr = dbs.stream().mapToDouble(d -> d).toArray();
        return new double[][] { fArr, dArr };
    }

    /**
     * Compute the frequency-response (dB) of a peaking filter.
     */
    private static double[] peakingResponse(double[] freqs, double fc, double Q, double gainDb, double fs) {
        int N = freqs.length;
        double[] resp = new double[N];
        double G = Math.pow(10, gainDb / 20.0);
        double B = fc / Q;
        for (int i = 0; i < N; i++) {
            double f = freqs[i];
            double w = 2 * Math.PI * f / fs;
            double wc = 2 * Math.PI * fc / fs;
            Complex zInv = new Complex(Math.cos(w), -Math.sin(w));
            Complex zInv2 = zInv.multiply(zInv);
            double sqrtG = Math.sqrt(G);
            double tanB2 = Math.tan((B / 2) * (Math.PI / fs));
            double cosWc = Math.cos(wc);
            Complex num = new Complex(sqrtG + G * tanB2, 0)
                    .subtract(zInv.multiply(2 * sqrtG * cosWc))
                    .add(zInv2.multiply(sqrtG - G * tanB2));
            Complex den = new Complex(sqrtG + tanB2, 0)
                    .subtract(zInv.multiply(2 * sqrtG * cosWc))
                    .add(zInv2.multiply(sqrtG - tanB2));
            resp[i] = 20 * Math.log10(num.divide(den).abs() + 1e-12);
        }
        return resp;
    }

    /**
     * Compute the frequency-response (dB) of a low-shelf filter.
     */
    private static double[] lowShelfResponse(double[] freqs, double fc, double Q, double gainDb, double fs) {
        int N = freqs.length;
        double[] resp = new double[N];
        double G = Math.pow(10, gainDb / 20.0);
        double Gs = Math.sqrt(G);
        double omega_c = Math.tan((2 * Math.PI * fc / fs) / 2);
        double omega2 = omega_c * omega_c;
        double omegaG = Math.sqrt(2) * omega_c * Math.pow(G, 0.25);
        for (int i = 0; i < N; i++) {
            double w = 2 * Math.PI * freqs[i] / fs;
            Complex zInv = new Complex(Math.cos(w), -Math.sin(w));
            Complex zInv2 = zInv.multiply(zInv);
            Complex num = new Complex(Gs * omega2 + omegaG + 1, 0)
                    .add(zInv.multiply(2 * (Gs * omega2 - 1)))
                    .add(zInv2.multiply(Gs * omega2 - omegaG + 1));
            Complex den = new Complex(Gs + omegaG + omega2, 0)
                    .add(zInv.multiply(2 * (omega2 - Gs)))
                    .add(zInv2.multiply(Gs - omegaG + omega2));
            resp[i] = 20 * Math.log10(num.divide(den).multiply(Gs).abs() + 1e-12);
        }
        return resp;
    }

    /**
     * Compute the frequency-response (dB) of a high-shelf filter.
     */
    private static double[] highShelfResponse(double[] freqs, double fc, double Q, double gainDb, double fs) {
        int N = freqs.length;
        double[] resp = new double[N];
        double G = Math.pow(10, gainDb / 20.0);
        double Gs = Math.sqrt(G);
        double omega_c = Math.tan((2 * Math.PI * fc / fs) / 2);
        double omega2 = omega_c * omega_c;
        double omegaG = Math.sqrt(2) * omega_c * Math.pow(G, 0.25);
        for (int i = 0; i < N; i++) {
            double w = 2 * Math.PI * freqs[i] / fs;
            Complex zInv = new Complex(Math.cos(w), -Math.sin(w));
            Complex zInv2 = zInv.multiply(zInv);
            Complex num = new Complex(Gs + omegaG + omega2, 0)
                    .subtract(zInv.multiply(2 * (Gs - omega2)))
                    .add(zInv2.multiply(Gs - omegaG + omega2));
            Complex den = new Complex(Gs * omega2 + omegaG + 1, 0)
                    .add(zInv.multiply(2 * (Gs * omega2 - 1)))
                    .add(zInv2.multiply(Gs * omega2 - omegaG + 1));
            resp[i] = 20 * Math.log10(num.divide(den).multiply(Gs).abs() + 1e-12);
        }
        return resp;
    }

    /**
     * Cascade all bands to compute the final EQ'd response.
     */
    public static double[] computeCombinedEQ(double[] freqs, double[] rawDb, List<PEQBand> bands, double fs) {
        int N = freqs.length;
        double[] netLin = new double[N];
        Arrays.fill(netLin, 1.0);
        for (PEQBand band : bands) {
            double[] bandDb;
            switch (band.type) {
                case LOW_SHELF:  bandDb = lowShelfResponse(freqs, band.fc, band.Q, band.gainDb, fs); break;
                case HIGH_SHELF: bandDb = highShelfResponse(freqs, band.fc, band.Q, band.gainDb, fs); break;
                default: bandDb = peakingResponse(freqs, band.fc, band.Q, band.gainDb, fs);
            }
            for (int i = 0; i < N; i++) {
                netLin[i] *= Math.pow(10, bandDb[i] / 20.0);
            }
        }
        double[] eqedDb = new double[N];
        for (int i = 0; i < N; i++) {
            double eqCurve = 20 * Math.log10(netLin[i] + 1e-12);
            eqedDb[i] = rawDb[i] + eqCurve;
        }
        return eqedDb;
    }

    /**
     * Generate a log-spaced frequency axis.
     */
    public static double[] logSpace(double startHz, double endHz, int N) {
        double[] out = new double[N];
        double logStart = Math.log10(startHz);
        double logEnd = Math.log10(endHz);
        for (int i = 0; i < N; i++) {
            double frac = (double) i / (N - 1);
            out[i] = Math.pow(10, logStart + frac * (logEnd - logStart));
        }
        return out;
    }

    /**
     * Interpolate ys(xs) onto xDst, doing all interpolation in log10-frequency.
     */
    /**
     * Exactly mirror scipy.interpolate.interp1d(np.log10(xs), ys) at xDst.
     */
    public static double[] interpLogFreq(double[] xs, double[] ys, double[] xDst) {
        int N = xDst.length;
        int M = xs.length;
        double[] out   = new double[N];
        double[] logXs = new double[M];

        // build log10(xs)
        for (int i = 0; i < M; i++) {
            logXs[i] = Math.log10(xs[i]);
        }

        // for each destination freq, binary‐search + interpolate in log‐domain
        for (int i = 0; i < N; i++) {
            double f     = xDst[i];
            double logF  = Math.log10(f);
            int    idx   = Arrays.binarySearch(logXs, logF);
            if (idx >= 0) {
                // exact match
                out[i] = ys[idx];
            } else {
                int ins = -idx - 1;
                if (ins == 0) {
                    out[i] = ys[0];
                } else if (ins >= M) {
                    out[i] = ys[M-1];
                } else {
                    double x0 = logXs[ins - 1], x1 = logXs[ins];
                    double y0 = ys[ins - 1],  y1 = ys[ins];
                    double t  = (logF - x0) / (x1 - x0);
                    out[i]  = y0 + t * (y1 - y0);
                }
            }
        }
        return out;
    }


}
