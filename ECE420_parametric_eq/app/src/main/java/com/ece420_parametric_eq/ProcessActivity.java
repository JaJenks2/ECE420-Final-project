package com.ece420_parametric_eq;

import static android.content.ContentValues.TAG;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ToggleButton;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.TextView;
import android.widget.AdapterView;
import com.ece420_parametric_eq.models.AnalysisResult;
import com.jjoe64.graphview.DefaultLabelFormatter;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import android.graphics.Color;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

public class ProcessActivity extends Activity {
    private Spinner spinnerProfile;
    private GraphView graphRaw;
    private Button btnProcess, btnNext, btnBack;
    private ToggleButton btnNormalize;
    private TextView titleText;

    // Measured arrays
    private float[] measuredFreqs, measuredRawDb, measuredSmthDb;
    // for CSV file
    private float[] csvFreqs, csvDb;

    private String[] profileNames = { "Harman", "B&K", "THX" };
    private String[] profileFiles = { "harman.csv", "bk.csv", "thx.csv" };
    private int selectedProfileIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_process_2);

        spinnerProfile = findViewById(R.id.spinner_profile);
        graphRaw       = findViewById(R.id.graph_raw);
        btnProcess     = findViewById(R.id.btn_process);
        btnBack        = findViewById(R.id.btn_back);
        btnNext        = findViewById(R.id.btn_next);
        btnNormalize   = findViewById(R.id.toggle_normalize);
        titleText      = findViewById(R.id.title_text);

        spinnerProfile.setAdapter(
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, profileNames)
        );
        spinnerProfile.setSelection(selectedProfileIndex);
        titleText.setText(profileNames[selectedProfileIndex]);

        spinnerProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int pos, long id) {
                selectedProfileIndex = pos;
                titleText.setText(profileNames[pos]);
                loadTargetFromCSV(profileFiles[pos]);
                if (measuredFreqs != null) {
                    plotGraphs(btnNormalize.isChecked());
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // log-X, ±20 dB Y
        graphRaw.getViewport().setXAxisBoundsManual(true);
        graphRaw.getViewport().setMinX((float)Math.log10(20));
        graphRaw.getViewport().setMaxX((float)Math.log10(20000));
        graphRaw.getViewport().setYAxisBoundsManual(true);
        graphRaw.getViewport().setMinY(-20);
        graphRaw.getViewport().setMaxY( 20);
        graphRaw.getGridLabelRenderer().setNumHorizontalLabels(8);
        graphRaw.getGridLabelRenderer().setNumVerticalLabels(10);
        graphRaw.getGridLabelRenderer().setHorizontalAxisTitle("Frequency (Hz)");
        graphRaw.getGridLabelRenderer().setVerticalAxisTitle("Level (dB)");
        graphRaw.getGridLabelRenderer().setLabelFormatter(new DefaultLabelFormatter() {
            @Override public String formatLabel(double value, boolean isX) {
                if (isX) {
                    double hz = Math.pow(10, value);
                    return hz >= 1000
                            ? String.format("%.0fk", hz/1000)
                            : String.format("%.0f",  hz);
                }
                return String.format("%.0f dB", value);
            }
        });

        btnProcess.setOnClickListener(v -> doProcessing());
        btnBack.setOnClickListener(v -> finish());
        btnNormalize.setOnCheckedChangeListener((button, isChecked) -> plotGraphs(isChecked));

        btnNext.setOnClickListener(v -> {
            // Compute raw MSE *before* launching EQ screen
            float rawMse = computeMSE(
                    measuredFreqs,
                    measuredSmthDb,   // or measuredRawDb if you prefer
                    csvFreqs,
                    csvDb
            );

            // intent and attach extras
            File dir = getExternalFilesDir(null);
            String frPath = new File(dir, "FR.txt").getAbsolutePath();
            Intent intent = new Intent(ProcessActivity.this, EqActivity.class);
            intent.putExtra("fr_path", frPath);
            intent.putExtra("target_csv_path", profileFiles[selectedProfileIndex]);
            intent.putExtra("raw_mse", rawMse);

            // Start EQActivity
            startActivity(intent);
        });
    }

    private void doProcessing() {
        String wavPath = getIntent().getStringExtra("wav_path");
        if (wavPath == null) {
            Toast.makeText(this, "No WAV path found!", Toast.LENGTH_SHORT).show();
            return;
        }

        float[] audioSamples = DataStore.readWavAsFloatArray(wavPath);
        if (audioSamples == null || audioSamples.length == 0) {
            Toast.makeText(this, "Failed to read WAV file", Toast.LENGTH_SHORT).show();
            return;
        }


        DSPProcessor.initConfig(
                48000,   // fs
                0.30f,   // impulse-window
                0.5f,    // marker silence
                4.0f,    // sweep duration
                31,      // SavGol window
                3        // SavGol poly order
        );

        AnalysisResult res = DSPProcessor.processBuffer(audioSamples);
        if (res == null) {
            Toast.makeText(this, "DSP failed", Toast.LENGTH_SHORT).show();
            return;
        }
        measuredFreqs  = res.freqs;
        measuredRawDb  = res.rawDb;
        measuredSmthDb = res.smoothDb;

        saveFrequencyResponseToTxt("FR.txt", measuredFreqs, measuredRawDb);
        plotGraphs(false);
    }

    private void saveFrequencyResponseToTxt(String filename, float[] freqs, float[] dbs) {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            Toast.makeText(this, "Cannot access storage", Toast.LENGTH_SHORT).show();
            return;
        }
        File out = new File(dir, filename);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out))) {
            for (int i = 1; i < freqs.length; i++) {
                w.write(String.format(Locale.US, "%.2f\t%.2f\n", freqs[i], dbs[i]));
            }
            Toast.makeText(this, "Saved FR to: " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Log.e(TAG, "Error saving FR", e);
            Toast.makeText(this, "Failed to save FR", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadTargetFromCSV(String assetName) {
        try (InputStream is = getAssets().open(assetName);
             BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            String line = r.readLine(); // skip header
            ArrayList<Float> fList = new ArrayList<>();
            ArrayList<Float> dList = new ArrayList<>();
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                fList.add(Float.parseFloat(p[0].trim()));
                dList.add(Float.parseFloat(p[1].trim()));
            }
            csvFreqs = new float[fList.size()];
            csvDb    = new float[dList.size()];
            for (int i = 0; i < fList.size(); i++) {
                csvFreqs[i] = fList.get(i);
                csvDb[i]    = dList.get(i);
            }
            Toast.makeText(this, "CSV loaded: " + csvFreqs.length + " points", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Failed to load CSV", e);
            Toast.makeText(this, "Failed to load CSV", Toast.LENGTH_LONG).show();
        }
    }

    private void plotGraphs(boolean normalize) {
        if (measuredFreqs == null) return;
        float[] plotDb = normalize
                ? DSPProcessor.normalizeAt1kHz(measuredFreqs, measuredSmthDb)
                : measuredSmthDb;

        // compute & display MSE in ProcessActivity
        float mse = computeMSE(measuredFreqs, plotDb, csvFreqs, csvDb);
        TextView tv = findViewById(R.id.tv_mse);
        if (tv != null) tv.setText(String.format(Locale.US, "MSE: %.2f", mse));

        // build series
        float[] xLogMeasured = new float[measuredFreqs.length];
        for (int i = 0; i < measuredFreqs.length; i++) {
            xLogMeasured[i] = (float)Math.log10(measuredFreqs[i]);
        }
        float[] xLogTarget = new float[csvFreqs.length];
        for (int i = 0; i < csvFreqs.length; i++) {
            xLogTarget[i] = (float)Math.log10(csvFreqs[i]);
        }
        LineGraphSeries<DataPoint> sMeas = DataStore.toSeries(xLogMeasured, plotDb);
        LineGraphSeries<DataPoint> sTgt  = DataStore.toSeries(xLogTarget, csvDb);
        sTgt.setColor(Color.RED);

        graphRaw.removeAllSeries();
        graphRaw.addSeries(sMeas);
        graphRaw.addSeries(sTgt);
    }

    private float interpolateTargetDb(float freq, float[] tgtFreqs, float[] tgtDb) {
        if (freq <= tgtFreqs[0]) return tgtDb[0];
        if (freq >= tgtFreqs[tgtFreqs.length-1]) return tgtDb[tgtDb.length-1];
        for (int i = 0; i < tgtFreqs.length-1; i++) {
            if (freq >= tgtFreqs[i] && freq <= tgtFreqs[i+1]) {
                float t = (freq - tgtFreqs[i])/(tgtFreqs[i+1]-tgtFreqs[i]);
                return tgtDb[i] + t*(tgtDb[i+1]-tgtDb[i]);
            }
        }
        return tgtDb[0];
    }

    private float computeMSE(float[] freqs, float[] values, float[] tgtFreqs, float[] tgtDb) {
        float sum = 0, count = 0;
        for (int i = 0; i < freqs.length; i++) {
            float f = freqs[i];
            if (f >= 100 && f <= 9000) {
                float target = interpolateTargetDb(f, tgtFreqs, tgtDb);
                float d = values[i] - target;
                sum += d*d;
                count++;
            }
        }
        return count>0 ? sum/count : 0f;
    }
}
