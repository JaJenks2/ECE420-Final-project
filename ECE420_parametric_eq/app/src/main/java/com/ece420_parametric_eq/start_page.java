package com.ece420_parametric_eq;


//basic app things
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ToggleButton;
import android.widget.TextView;

//for drawing on canvas
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Bitmap;

//for audio recording
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;

//for handling timer
import android.os.AsyncTask;
import android.os.Handler;

//for orientation
import android.view.WindowManager;
import android.content.pm.ActivityInfo;

//for permission
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

//for graphing
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.jjoe64.graphview.series.PointsGraphSeries;

//file wiritng stuf f
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class start_page extends Activity {
    private static final String TAG = "start_page";
    private Button buttonNext;
    private ToggleButton buttonStartStop;
    public LineGraphSeries<DataPoint> rawGraphData;
    public PointsGraphSeries<DataPoint> rawGraphSteps;
    private boolean ready = false;
    private Handler handler = new Handler(); // used for timer for chekcing for ready
    private Runnable readyChecker;

    // === AUDIO RECORDING CONSTANTS ===
    private static final int SAMPLE_RATE = 48000; // matching naative sampelr ate
    private static final int RECORD_DURATION_MS = 5000; // how long it will record but not needed
    private static final int AUDIO_SOURCE = android.media.MediaRecorder.AudioSource.MIC;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private String outputFilePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.start_page);
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        buttonStartStop = findViewById(R.id.button_start_stop);
        buttonNext = findViewById(R.id.next_button);

        // Request permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 200);
        }

        buttonStartStop.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                Log.d(TAG, "Toggle ON → Start recording");
                startRecording();
                startReadyCheck();
                buttonNext.setVisibility(View.VISIBLE);
                ready = false;
            } else {
                Log.d(TAG, "Toggle OFF → Stop and save recording");
                stopRecording();
            }
        });


        buttonNext.setOnClickListener(v -> {
            Log.d(TAG, "Next button clicked");
            Intent i = new Intent(this, ProcessActivity.class);
            i.putExtra("wav_path", outputFilePath);
            startActivity(i);
        });
    }

    private void startReadyCheck() {
        readyChecker = new Runnable() {
            @Override
            public void run() {
                if (ready) {
//                    buttonStart.setVisibility(View.VISIBLE);
                    buttonNext.setVisibility(View.VISIBLE);
                    Log.d(TAG, "ready for next page");
                    handler.removeCallbacks(readyChecker);
                } else {
                    handler.postDelayed(this, 100);
                }
            }
        };
        handler.post(readyChecker);
    }

    private void writeWavFile(byte[] audioData, String filePath) throws IOException {
        FileOutputStream out = new FileOutputStream(filePath);
        int totalDataLen = audioData.length + 36;
        int byteRate = SAMPLE_RATE * 2;

        //this is setting up wav file format
        out.write(new byte[]{
                'R', 'I', 'F', 'F',
                (byte) (totalDataLen & 0xff), (byte) ((totalDataLen >> 8) & 0xff), (byte) ((totalDataLen >> 16) & 0xff), (byte) ((totalDataLen >> 24) & 0xff),
                'W', 'A', 'V', 'E',
                'f', 'm', 't', ' ',
                16, 0, 0, 0, 1, 0, 1, 0,
                (byte) (SAMPLE_RATE & 0xff), (byte) ((SAMPLE_RATE >> 8) & 0xff), (byte) ((SAMPLE_RATE >> 16) & 0xff), (byte) ((SAMPLE_RATE >> 24) & 0xff),
                (byte) (byteRate & 0xff), (byte) ((byteRate >> 8) & 0xff), (byte) ((byteRate >> 16) & 0xff), (byte) ((byteRate >> 24) & 0xff),
                2, 0, 16, 0,
                'd', 'a', 't', 'a',
                (byte) (audioData.length & 0xff), (byte) ((audioData.length >> 8) & 0xff), (byte) ((audioData.length >> 16) & 0xff), (byte) ((audioData.length >> 24) & 0xff)
        });

        out.write(audioData);
        out.close();
    }

    private void startRecording() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted. Recording aborted.");
            return;
        }

        audioRecord = new AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
        byte[] buffer = new byte[bufferSize];
        isRecording = true;
        outputFilePath = getExternalFilesDir(null).getAbsolutePath() + "/recorded_audio.wav";

        new Thread(() -> {
            ByteArrayOutputStream rawAudio = new ByteArrayOutputStream();
            try {
                audioRecord.startRecording();

                while (isRecording) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        rawAudio.write(buffer, 0, read);
                    }
                }

                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;

                writeWavFile(rawAudio.toByteArray(), outputFilePath);
                Log.d(TAG, "Recording saved to: " + outputFilePath);
                ready = true;


            } catch (Exception e) {
                Log.e(TAG, "Recording failed", e);
            }
        }).start();
    }

    private void stopRecording() {
        isRecording = false;
    }


    @Override
    protected void onPause() {
        super.onPause();
        stopRecordingSafely();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopRecordingSafely();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecordingSafely();
    }

    private void stopRecordingSafely() {
        if (audioRecord != null) {
            try {
                if (isRecording) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "error AudioRecord stop", e);
            }

            audioRecord.release();
            audioRecord = null;
            isRecording = false;
            Log.d(TAG, "AudioRecord released");
        }
    }

}