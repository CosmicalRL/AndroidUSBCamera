package com.jiangdg.demo;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.jiangdg.ausbc.callback.ICaptureCallBack;
import com.jiangdg.ausbc.encode.H264EncodeProcessor;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Lightweight OBS/PRISM-style UVC recording and instant-replay studio. */
public class MainActivity extends AppCompatActivity {
    private static final int MIN_BITRATE_KBPS = 2000;
    private static final int MAX_BITRATE_KBPS = 20000;

    private static final Quality[] QUALITIES = {
            new Quality("720p60", 1280, 720, 60, 8000),
            new Quality("1080p50", 1920, 1080, 50, 12000),
            new Quality("720p30", 1280, 720, 30, 5000)
    };

    private ReplayCameraFragment cameraFragment;
    private ReplayBufferManager replayBuffer;
    private TextView statusText;
    private TextView qualityText;
    private TextView replayText;
    private Button recordButton;
    private int qualityIndex = 0;
    private int bitrateKbps = 8000;
    private boolean recording;

    private static final class Quality {
        final String name; final int width; final int height; final int fps; final int bitrate;
        Quality(String name, int width, int height, int fps, int bitrate) {
            this.name = name; this.width = width; this.height = height; this.fps = fps; this.bitrate = bitrate;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo);

        H264EncodeProcessor.configureDefaults(60, 8_000_000);
        replayBuffer = new ReplayBufferManager();
        replayBuffer.setBufferSeconds(30);

        cameraFragment = new ReplayCameraFragment();
        cameraFragment.attachReplayBuffer(replayBuffer);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, cameraFragment)
                .commit();

        FrameLayout root = findViewById(R.id.fragment_container);
        root.post(() -> buildStudioOverlay(root));
    }

    private void buildStudioOverlay(FrameLayout root) {
        hideLegacyControls(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(6), dp(8), dp(6));
        top.setBackgroundColor(0xE6101115);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, dp(54), Gravity.TOP);
        root.addView(top, topLp);

        TextView title = label("UVC STUDIO", 13, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        qualityText = chip("720p60");
        top.addView(qualityText, new LinearLayout.LayoutParams(-2, dp(34)));
        TextView settings = chip("⚙");
        settings.setOnClickListener(v -> showSettings());
        top.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(34)));

        statusText = label("● READY • REPLAY BUFFER 30S", 11, true);
        statusText.setPadding(dp(12), 0, dp(12), 0);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-2, dp(34), Gravity.TOP | Gravity.START);
        statusLp.topMargin = dp(64);
        root.addView(statusText, statusLp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(10), dp(8), dp(10), dp(10));
        bottom.setBackgroundColor(0xF2111216);
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1, dp(156), Gravity.BOTTOM);
        root.addView(bottom, bottomLp);

        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(sourceRow, new LinearLayout.LayoutParams(-1, dp(40)));
        TextView scene = panel("SCENE 1  •  UVC CAPTURE");
        sourceRow.addView(scene, new LinearLayout.LayoutParams(0, -1, 1f));
        Button web = makeButton("+ WEB");
        web.setOnClickListener(v -> addBrowserSource(root));
        sourceRow.addView(web, new LinearLayout.LayoutParams(dp(82), dp(38)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(6), 0, 0);
        bottom.addView(controls, new LinearLayout.LayoutParams(-1, dp(58)));

        Button replay = makeButton("↻ SAVE REPLAY");
        replay.setOnClickListener(v -> exportReplay(replayBuffer.getBufferSeconds()));
        controls.addView(replay, new LinearLayout.LayoutParams(0, dp(52), 1f));

        recordButton = makeButton("● RECORD");
        recordButton.setOnClickListener(v -> toggleRecording());
        controls.addView(recordButton, new LinearLayout.LayoutParams(0, dp(52), 1f));

        replayText = makeButton("30S");
        replayText.setOnClickListener(v -> showReplaySettings());
        controls.addView(replayText, new LinearLayout.LayoutParams(dp(72), dp(52)));
    }

    private void hideLegacyControls(FrameLayout root) {
        int[] ids = {R.id.controlPanelLayout, R.id.toolbarBg, R.id.toolbarGroup, R.id.frameRateTv,
                R.id.recTimerLayout, R.id.brightnessSb};
        for (int id : ids) {
            View v = root.findViewById(id);
            if (v != null) v.setVisibility(View.GONE);
        }
    }

    private void addBrowserSource(ViewGroup root) {
        ViewGroup layer = root.findViewById(R.id.browserSourceLayer);
        if (layer != null) BrowserSourceOverlay.showUrlDialog(this, layer);
    }

    private void toggleRecording() {
        if (recording) {
            cameraFragment.stopRecording();
            recording = false;
            recordButton.setText("● RECORD");
            statusText.setText("● READY • REPLAY BUFFER " + replayBuffer.getBufferSeconds() + "S");
            return;
        }
        File movies = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (movies == null) {
            Toast.makeText(this, "Storage unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File output = new File(movies, "UVC_" + stamp + ".mp4");
        cameraFragment.startRecording(new ICaptureCallBack() {
            @Override public void onBegin() {
                recording = true;
                recordButton.setText("■ STOP");
                statusText.setText("● RECORDING • " + qualityText.getText());
            }
            @Override public void onError(String error) {
                recording = false;
                recordButton.setText("● RECORD");
                Toast.makeText(MainActivity.this, error == null ? "Recording failed" : error, Toast.LENGTH_LONG).show();
            }
            @Override public void onComplete(String path) {
                recording = false;
                recordButton.setText("● RECORD");
                statusText.setText("● READY • REPLAY BUFFER " + replayBuffer.getBufferSeconds() + "S");
                Toast.makeText(MainActivity.this, "Saved recording", Toast.LENGTH_LONG).show();
            }
        }, output.getAbsolutePath());
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));

        TextView quality = label("QUALITY", 11, true);
        box.addView(quality, new LinearLayout.LayoutParams(-1, dp(30)));
        for (int i = 0; i < QUALITIES.length; i++) {
            final int index = i;
            Button b = makeButton(QUALITIES[i].name + "  •  " + QUALITIES[i].bitrate + " Kbps");
            b.setOnClickListener(v -> {
                applyQuality(index);
                ((AlertDialog) v.getTag()).dismiss();
            });
            box.addView(b, new LinearLayout.LayoutParams(-1, dp(44)));
            b.setTag(null);
        }

        TextView bitrate = label("CUSTOM BITRATE: " + bitrateKbps + " Kbps", 11, true);
        box.addView(bitrate, new LinearLayout.LayoutParams(-1, dp(30)));
        SeekBar slider = new SeekBar(this);
        slider.setMax(MAX_BITRATE_KBPS - MIN_BITRATE_KBPS);
        slider.setProgress(bitrateKbps - MIN_BITRATE_KBPS);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                bitrateKbps = MIN_BITRATE_KBPS + p;
                bitrate.setText("CUSTOM BITRATE: " + bitrateKbps + " Kbps");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(slider, new LinearLayout.LayoutParams(-1, dp(40)));

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("UVC Studio Settings").setView(box).setNegativeButton("Close", null).create();
        dialog.show();
        for (int i = 0; i < box.getChildCount(); i++) {
            View child = box.getChildAt(i);
            if (child instanceof Button) child.setTag(dialog);
        }
    }

    private void applyQuality(int index) {
        qualityIndex = index;
        Quality q = QUALITIES[index];
        bitrateKbps = q.bitrate;
        H264EncodeProcessor.configureDefaults(q.fps, q.bitrate * 1000);
        cameraFragment.configureQuality(q.width, q.height, q.fps, q.bitrate);
        qualityText.setText(q.name);
        statusText.setText("● APPLYING • " + q.name + " • " + q.bitrate + " Kbps");
        Toast.makeText(this, q.name + " selected", Toast.LENGTH_SHORT).show();
    }

    private void showReplaySettings() {
        String[] options = {"30 seconds", "60 seconds", "90 seconds", "120 seconds"};
        int checked = replayBuffer.getBufferSeconds() == 60 ? 1 : replayBuffer.getBufferSeconds() == 90 ? 2 : replayBuffer.getBufferSeconds() == 120 ? 3 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Replay buffer")
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    int[] seconds = {30, 60, 90, 120};
                    replayBuffer.setBufferSeconds(seconds[which]);
                    replayText.setText(seconds[which] + "S");
                    statusText.setText("● READY • REPLAY BUFFER " + seconds[which] + "S");
                    dialog.dismiss();
                }).show();
    }

    private void exportReplay(int seconds) {
        if (replayBuffer.getPacketCount() == 0) {
            Toast.makeText(this, "Waiting for UVC frames", Toast.LENGTH_SHORT).show();
            return;
        }
        File movies = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (movies == null) return;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File output = new File(movies, "Replay_" + stamp + ".mp4");
        replayBuffer.exportLast(seconds, output, cameraFragment.getReplayWidth(), cameraFragment.getReplayHeight(), QUALITIES[qualityIndex].fps,
                (success, pathOrError) -> Toast.makeText(this, success ? "Replay saved" : "Replay failed: " + pathOrError, Toast.LENGTH_LONG).show());
    }

    private TextView label(String text, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextColor(Color.WHITE); v.setTextSize(size);
        v.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private TextView chip(String text) {
        TextView v = label(text, 11, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), 0, dp(10), 0);
        v.setBackground(roundDrawable(0xFF272A31, 0xFF3A3D45, 1, 10));
        return v;
    }

    private TextView panel(String text) {
        TextView v = label(text, 11, true);
        v.setPadding(dp(12), 0, dp(12), 0);
        v.setBackground(roundDrawable(0xFF1A1C21, 0xFF2C2F36, 1, 9));
        return v;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text); b.setTextColor(Color.WHITE); b.setTextSize(11); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false); b.setBackground(roundDrawable(0xFF25282F, 0xFF3A3D45, 1, 10));
        return b;
    }

    private android.graphics.drawable.GradientDrawable roundDrawable(int fill, int stroke, int width, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radiusDp)); d.setStroke(dp(width), stroke); return d;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        if (replayBuffer != null) replayBuffer.release();
        super.onDestroy();
    }
}
