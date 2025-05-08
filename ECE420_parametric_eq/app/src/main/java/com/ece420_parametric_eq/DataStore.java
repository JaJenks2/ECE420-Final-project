package com.ece420_parametric_eq;

import android.content.Context;
import com.ece420_parametric_eq.models.AnalysisResult;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import java.io.InputStream;
import java.util.Scanner;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;

public class DataStore {

    /** Read WAV file into normalized float[] PCM */
    public static float[] readWavAsFloatArray(String path) {
        try (FileInputStream fis = new FileInputStream(path);
             BufferedInputStream bis = new BufferedInputStream(fis);
             DataInputStream dis = new DataInputStream(bis)) {

            // Read WAV header (44 bytes)
            byte[] header = new byte[44];
            dis.readFully(header);

            // Parse format fields (all little endian)
            int channels      = (header[22] & 0xff) | ((header[23] & 0xff) << 8);
            int bitsPerSample = (header[34] & 0xff) | ((header[35] & 0xff) << 8);
            int dataSize      = (header[40] & 0xff)
                    | ((header[41] & 0xff) << 8)
                    | ((header[42] & 0xff) << 16)
                    | ((header[43] & 0xff) << 24);

            int bytesPerSample = bitsPerSample / 8;
            int frameSize      = bytesPerSample * channels;
            int numSamples     = dataSize / frameSize;

            float[] samples = new float[numSamples];

            // Read each sample, convert to float in [–1,1]
            for (int i = 0; i < numSamples; i++) {
                int raw = 0;
                // assume 16-bit PCM
                if (bytesPerSample == 2) {
                    int low  = dis.readUnsignedByte();
                    int high = dis.readByte();           // signed
                    raw = (high << 8) | low;
                } else if (bytesPerSample == 1) {
                    // 8 bit WAV is unsigned
                    raw = dis.readUnsignedByte() - 128;
                } else {
                    // other bit depths not supported
                    throw new IOException("Unsupported sample size: " + bitsPerSample);
                }

                // normalize
                float maxVal = (float)(Math.pow(2, bitsPerSample - 1));
                samples[i] = raw / maxVal;

                // skip remaining channels if stereo
                if (channels > 1) {
                    dis.skipBytes((channels - 1) * bytesPerSample);
                }
            }

            return samples;

        } catch (IOException e) {
            e.printStackTrace();
            // on failure, return empty array
            return new float[0];
        }
    }

    /** Helper to turn float arrays into a LineGraphSeries<DataPoint> */
    public static LineGraphSeries<DataPoint> toSeries(float[] xs, float[] ys) {
        DataPoint[] pts = new DataPoint[xs.length];
        for (int i = 0; i < xs.length; i++) {
            pts[i] = new DataPoint(xs[i], ys[i]);
        }
        return new LineGraphSeries<>(pts);
    }

    // callbacks
    public interface OnFreqs { void onData(float[] freqs); }
    public interface OnDbs   { void onData(float[] dbs); }
}