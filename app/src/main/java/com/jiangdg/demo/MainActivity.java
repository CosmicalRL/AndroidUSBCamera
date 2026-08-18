package com.jiangdg.demo;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Main capture-card control surface.
 *
 * libausbc owns the low-level USB/UVC handshake and camera lifecycle through
 * ReplayCameraFragment. The fragment exposes the encoded H.264 stream, which
 * ReplayBufferManager keeps in RAM for instant replay exports.
 */
public class MainActivity extends AppCompatActivity {
    private static final int MIN_BITRATE_KBPS = 1000;
    private static final int MAX_BITRATE_KBPS = 12000;

    private ReplayCameraFragment cameraFragment;
    private ReplayBufferManager replayBuffer;
    private TextView bitrateValue;
    private TextView clipButton;
    private TextView statusText;

    private int bitrateKbps = 8000;
    private long recordingStartedMs = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo);

        FrameLayout root = findViewById(R.id.fragment_container);
        root.setBackgroundColor(Color.BLACK);

        replayBuffer = new ReplayBufferManager();
        replayBuffer.setBufferSeconds(30);

        cameraFragment = new ReplayCameraFragment();
        cameraFragment.attachReplayBuffer(replayBuffer);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, cameraFragment)
                .commit();

        root.post(() -> buildControlOverlay(root));
    }

    private void buildControlOverlay(FrameLayout root) {
        FrameLayout topPill = new FrameLayout(this);
        topPill.setBackground(roundDrawable(0xCC000000, 0xFFFFFFFF, 1, 28));
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(
                dp(58), dp(230), Gravity.TOP | Gravity.RIGHT);
        pillParams.setMargins(0, dp(18), dp(12), 0);
        root.addView(topPill, pillParams);

        String[] icons = {"+", "☷", "🎛", "⚙"};
        for (int i = 0; i < icons.length; i++) {
            TextView action = new TextView(this);
            action.setText(icons[i]);
            action.setTextColor(Color.WHITE);
            action.setTextSize(i == 3 ? 22 : 24);
            action.setGravity(Gravity.CENTER);
            action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    dp(54), dp(54), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(4 + i * 56);
            topPill.addView(action, lp);
            final int index = i;
            action.setOnClickListener(v -> handleTopAction(index));
        }

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(18), dp(14), dp(18), dp(18));
        bottom.setBackgroundColor(0xF5000000);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.40f),
                Gravity.BOTTOM);
        root.addView(bottom, bottomParams);

        statusText = new TextView(this);
        statusText.setText("UVC REPLAY BUFFER • WAITING FOR CAPTURE CARD");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(12);
        statusText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bottom.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));

        LinearLayout bitrateRow = new LinearLayout(this);
        bitrateRow.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(bitrateRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        TextView bitrateLabel = new TextView(this);
        bitrateLabel.setText("BITRATE");
        bitrateLabel.setTextColor(Color.WHITE);
        bitrateLabel.setTextSize(13);
        bitrateLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bitrateRow.addView(bitrateLabel, new LinearLayout.LayoutParams(dp(65), dp(48)));

        SeekBar slider = new SeekBar(this);
        slider.setMax(MAX_BITRATE_KBPS - MIN_BITRATE_KBPS);
        slider.setProgress(bitrateKbps - MIN_BITRATE_KBPS);
        bitrateRow.addView(slider, new LinearLayout.LayoutParams(0, dp(48), 1f));

        bitrateValue = new TextView(this);
        bitrateValue.setText(bitrateKbps + " Kbps");
        bitrateValue.setTextColor(Color.WHITE);
        bitrateValue.setTextSize(13);
        bitrateValue.setGravity(Gravity.CENTER);
        bitrateRow.addView(bitrateValue, new LinearLayout.LayoutParams(dp(85), dp(48)));

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                bitrateKbps = MIN_BITRATE_KBPS + progress;
                bitrateValue.setText(bitrateKbps + " Kbps");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        bottom.addView(buttons, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));

        Button start = makeButton("START RECORDING");
        Button stop = makeButton("STOP RECORDING");
        Button clip = makeButton("RECORD LAST 30S");
        clipButton = clip;

        buttons.addView(start, new LinearLayout.LayoutParams(0, dp(58), 1f));
        buttons.addView(stop, new LinearLayout.LayoutParams(0, dp(58), 1f));
        buttons.addView(clip, new LinearLayout.LayoutParams(0, dp(58), 1f));

        start.setOnClickListener(v -> startRecording());
        stop.setOnClickListener(v -> stopRecording());
        clip.setOnClickListener(v -> exportReplay(replayBuffer.getBufferSeconds()));
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(roundDrawable(0xFF000000, 0xFFFFFFFF, 2, 12));
        return button;
    }

    private void startRecording() {
        if (recordingStartedMs != 0L) {
            Toast.makeText(this, "Recording is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        recordingStartedMs = System.currentTimeMillis();
        statusText.setText("● RECORDING • " + bitrateKbps + " Kbps");
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
    }

    private void stopRecording() {
        if (recordingStartedMs == 0L) {
            Toast.makeText(this, "Nothing is recording", Toast.LENGTH_SHORT).show();
            return;
        }
        long elapsed = Math.max(1L, (System.currentTimeMillis() - recordingStartedMs) / 1000L);
        recordingStartedMs = 0L;
        int seconds = (int) Math.min(elapsed, replayBuffer.getBufferSeconds());
        exportReplay(seconds);
        statusText.setText("UVC REPLAY BUFFER • READY");
    }

    private void exportReplay(int seconds) {
        if (replayBuffer.getPacketCount() == 0) {
            Toast.makeText(this, "Waiting for UVC video frames", Toast.LENGTH_SHORT).show();
            return;
        }
        File movies = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (movies == null) {
            Toast.makeText(this, "Storage unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File output = new File(movies, "Replay_" + stamp + ".mp4");
        int width = cameraFragment.getReplayWidth();
        int height = cameraFragment.getReplayHeight();
        replayBuffer.exportLast(seconds, output, width, height, 60, (success, pathOrError) -> {
            if (success) {
                Toast.makeText(this, "Clip saved: " + pathOrError, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Clip failed: " + pathOrError, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void handleTopAction(int index) {
        switch (index) {
            case 0:
                Toast.makeText(this, "UVC capture card is the active source", Toast.LENGTH_SHORT).show();
                break;
            case 1:
                Toast.makeText(this, "Layers are reserved for future overlays", Toast.LENGTH_SHORT).show();
                break;
            case 2:
                Toast.makeText(this, "Preset scene: Capture Card", Toast.LENGTH_SHORT).show();
                break;
            case 3:
                showReplaySettings();
                break;
            default:
                break;
        }
    }

    private void showReplaySettings() {
        String[] options = {"30 seconds", "60 seconds", "90 seconds", "120 seconds"};
        int checked;
        switch (replayBuffer.getBufferSeconds()) {
            case 60: checked = 1; break;
            case 90: checked = 2; break;
            case 120: checked = 3; break;
            default: checked = 0;
        }
        new AlertDialog.Builder(this)
                .setTitle("Replay buffer")
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    int[] seconds = {30, 60, 90, 120};
                    replayBuffer.setBufferSeconds(seconds[which]);
                    clipButton.setText("RECORD LAST " + seconds[which] + "S");
                    dialog.dismiss();
                    Toast.makeText(this, "Replay buffer: " + seconds[which] + "s", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private android.graphics.drawable.GradientDrawable roundDrawable(int fill, int stroke,
                                                                       int strokeWidth, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(strokeWidth), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (replayBuffer != null) {
            replayBuffer.release();
        }
        super.onDestroy();
    }
}
