package com.ece420_parametric_eq;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup;
import android.util.Log;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.PyObject;

import com.jjoe64.graphview.DefaultLabelFormatter;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import com.ece420_parametric_eq.ParametricEQUtils;
import com.ece420_parametric_eq.ParametricEQUtils.AutoEQResult;
import com.ece420_parametric_eq.models.PEQBand;
import com.ece420_parametric_eq.DataStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class EqActivity extends Activity {
    private static final String TAG = "EqActivity";

    private GraphView graphCombined;
    private GraphView graphFilters;
    private LinearLayout bandContainer;
    private Button btnApplyEq;
    private float mseRaw;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_eq);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mseRaw = getIntent().getFloatExtra("raw_mse", 0f);

        // Ensure Python running
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        // Retrieve paths from Intent
        String frPath      = getIntent().getStringExtra("fr_path");
        String targetAsset = getIntent().getStringExtra("target_csv_path");
        if (frPath == null || targetAsset == null) {
            Toast.makeText(this, "Missing FR or target CSV path", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        File dir       = getExternalFilesDir(null);
        File targetCsv = new File(dir, "target.csv");
        // Copy target CSV asset (overwrite any existing)
        if (targetCsv.exists()) targetCsv.delete();
        try (InputStream is = getAssets().open(targetAsset);
             FileOutputStream fos = new FileOutputStream(targetCsv)) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) > 0) {
                fos.write(buf, 0, r);
            }
        } catch (IOException e) {
            Toast.makeText(this, "Failed to copy target CSV", Toast.LENGTH_SHORT).show();
            return;
        }

        // Bind UI elements
        graphCombined  = findViewById(R.id.graph_combined);
        graphFilters   = findViewById(R.id.graph_filters);
        bandContainer  = findViewById(R.id.band_container);
        btnApplyEq     = findViewById(R.id.btn_apply_eq);

        // Configure both graphs (log-X, ±20 dB Y)
        for (GraphView g : new GraphView[]{ graphCombined, graphFilters }) {
            g.getViewport().setXAxisBoundsManual(true);
            g.getViewport().setMinX((float)Math.log10(20));     // log10(20 Hz)
            g.getViewport().setMaxX((float)Math.log10(20000));  // log10(20 kHz)
            g.getViewport().setYAxisBoundsManual(true);
            g.getViewport().setMinY(-20);
            g.getViewport().setMaxY(20);
            g.getGridLabelRenderer().setNumHorizontalLabels(8);
            g.getGridLabelRenderer().setNumVerticalLabels(10);
            g.getGridLabelRenderer().setHorizontalAxisTitle("Frequency (Hz)");
            g.getGridLabelRenderer().setVerticalAxisTitle("Level (dB)");
            g.getGridLabelRenderer().setLabelFormatter(new DefaultLabelFormatter() {
                @Override
                public String formatLabel(double value, boolean isX) {
                    if (isX) {
                        double hz = Math.pow(10, value);
                        return hz >= 1000
                                ? String.format(Locale.US, "%.0fk", hz/1000)
                                : String.format(Locale.US, "%.0f", hz);
                    } else {
                        return String.format(Locale.US, "%.0f dB", value);
                    }
                }
            });
        }

        btnApplyEq.setOnClickListener(v -> {
            try {
                // call AutoEQ via Python
                File eqOut = new File(dir, "speaker_eq.txt");
                PyObject helper = Python.getInstance().getModule("autoeq_helper");
                helper.callAttr("run_autoeq", frPath, targetCsv.getAbsolutePath(), eqOut.getAbsolutePath());

                // Load raw & target responses
                double[][] rawData = ParametricEQUtils.loadFrequencyResponse(new File(frPath));
                double[][] tgtData = ParametricEQUtils.loadFrequencyResponse(targetCsv);

                // Build log-spaced axis
                int N = 512;
                double[] freqs = ParametricEQUtils.logSpace(20, 20000, N);

                // Interpolate raw & target
                double[] rawDb = ParametricEQUtils.interpLogFreq(rawData[0], rawData[1], freqs);
                double[] tgtDb = ParametricEQUtils.interpLogFreq(tgtData[0], tgtData[1], freqs);

                // Parse AutoEQResult
                AutoEQResult result = ParametricEQUtils.loadAutoEQResult(eqOut);
                List<PEQBand> bands = result.bands;
                double preampDb = result.preampDb;

                // Compute EQ'ed response without preamp
                double[] eqedNoPre = ParametricEQUtils.computeCombinedEQ(freqs, rawDb, bands, 48000);
                // Apply global preamp
                double[] eqedDb = Arrays.copyOf(eqedNoPre, eqedNoPre.length);
                for (int i = 0; i < eqedDb.length; i++) {
                    eqedDb[i] += preampDb;
                }

                // Compute pure EQ curve (filter only)
                double[] eqCurve = new double[N];
                for (int i = 0; i < N; i++) {
                    eqCurve[i] = eqedNoPre[i] - rawDb[i];
                }


//                double mseRaw = computeMSE(freqs, rawDb,   tgtData[0], tgtData[1]);
                double mseEq  = computeMSE(freqs, eqedDb, tgtData[0], tgtData[1]);


                bandContainer.removeAllViews();

                TextView mse1 = new TextView(this);
                mse1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                mse1.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
                mse1.setText(String.format(Locale.US,
                        "MSE Raw vs Target: %.2f dB²", mseRaw));
                LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp1.gravity = Gravity.CENTER_HORIZONTAL;
                lp1.setMargins(0,0,0,8);
                mse1.setLayoutParams(lp1);
                bandContainer.addView(mse1);

                TextView mse2 = new TextView(this);
                mse2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                mse2.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
                mse2.setText(String.format(Locale.US,
                        "MSE EQ’d vs Target: %.2f dB²", mseEq));
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp2.gravity = Gravity.CENTER_HORIZONTAL;
                lp2.setMargins(0,0,0,16);
                mse2.setLayoutParams(lp2);
                bandContainer.addView(mse2);

                int count = Math.min(bands.size(), 10);
                for (int i = 0; i < count; i++) {
                    PEQBand b = bands.get(i);
                    TextView tv = new TextView(this);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    tv.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
                    tv.setText(String.format(Locale.US,
                            "Band %d: %s @ %.0f Hz   Gain: %.1f dB   Q: %.2f",
                            i+1,
                            b.type.name().replace('_',' '),
                            b.fc,
                            b.gainDb,
                            b.Q));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.gravity = Gravity.CENTER_HORIZONTAL;
                    lp.setMargins(0,0,0,8);
                    tv.setLayoutParams(lp);
                    bandContainer.addView(tv);
                }


                float[] xLog  = new float[N];
                float[] rawF  = new float[N];
                float[] tgtF  = new float[N];
                float[] eqedF = new float[N];
                float[] eqcF  = new float[N];
                for (int i = 0; i < N; i++) {
                    xLog[i]  = (float)Math.log10(freqs[i]);
                    rawF[i]  = (float)rawDb[i];
                    tgtF[i]  = (float)tgtDb[i];
                    eqedF[i] = (float)eqedDb[i];
                    eqcF[i]  = (float)eqCurve[i];
                }

                LineGraphSeries<DataPoint> sRaw     = DataStore.toSeries(xLog, rawF);
                LineGraphSeries<DataPoint> sTgt     = DataStore.toSeries(xLog, tgtF);
                LineGraphSeries<DataPoint> sEqed    = DataStore.toSeries(xLog, eqedF);
                LineGraphSeries<DataPoint> sEQCurve = DataStore.toSeries(xLog, eqcF);
                sTgt.setColor(0xFFFF0000);     // red
                sEqed.setColor(0xFF00AA00);    // green
                sEQCurve.setColor(0xFFAA00FF); // magenta

                // Plot to graphs
                graphCombined.removeAllSeries();
                graphCombined.addSeries(sRaw);
                graphCombined.addSeries(sTgt);
                graphCombined.addSeries(sEqed);

                graphFilters.removeAllSeries();
                graphFilters.addSeries(sEQCurve);

            } catch (IOException e) {
                Log.e(TAG, "Error applying EQ", e);
                Toast.makeText(this, "Apply EQ error", Toast.LENGTH_SHORT).show();
            } catch (Exception ex) {
                Log.e(TAG, "AutoEQ or plotting failed", ex);
                Toast.makeText(this, "Error during EQ generation", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Helper from ProcessActivity for interpolation
    private double interpolateTargetDb(double freq, double[] tgtFreqs, double[] tgtDb) {
        if (freq <= tgtFreqs[0]) return tgtDb[0];
        if (freq >= tgtFreqs[tgtFreqs.length - 1]) return tgtDb[tgtDb.length - 1];
        for (int i = 0; i < tgtFreqs.length - 1; i++) {
            if (freq >= tgtFreqs[i] && freq <= tgtFreqs[i + 1]) {
                double t = (freq - tgtFreqs[i]) / (tgtFreqs[i + 1] - tgtFreqs[i]);
                return tgtDb[i] + t * (tgtDb[i + 1] - tgtDb[i]);
            }
        }
        return 0;
    }

    // Helper from ProcessActivity for MSE computation
    private double computeMSE(double[] freqs, double[] values, double[] tgtFreqs, double[] tgtDb) {
        double sumSquaredError = 0.0;
        int count = 0;
        for (int i = 0; i < freqs.length; i++) {
            double f = freqs[i];
            if (f >= 100 && f <= 9000) {
                double target = interpolateTargetDb(f, tgtFreqs, tgtDb);
                double diff   = values[i] - target;
                sumSquaredError += diff * diff;
                count++;
            }
        }
        return count > 0 ? sumSquaredError / count : 0.0;
    }
}
