package com.jiangdg.demo;

import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private SurfaceView captureSurface;
    private SurfaceHolder surfaceHolder;
    private SeekBar bitrateSlider;
    private TextView txtBitrateValue;
    private Button btnClip;
    private ImageButton btnAddSource;
    private ImageButton btnLayers;
    private ImageButton btnSettings;
    
    private int activeBitrate = 4000000;
    private boolean isCapturing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.activity_demo);
        
        captureSurface = findViewById(R.id.captureSurface);
        bitrateSlider = findViewById(R.id.bitrateSlider);
        txtBitrateValue = findViewById(R.id.txtBitrateValue);
        btnClip = findViewById(R.id.btnClip);
        
        btnAddSource = findViewById(R.id.btnAddSource);
        btnLayers = findViewById(R.id.btnLayers);
        btnSettings = findViewById(R.id.btnSettings);
        
        if (captureSurface != null) {
            surfaceHolder = captureSurface.getHolder();
            surfaceHolder.addCallback(this);
        }

        if (bitrateSlider != null) {
            bitrateSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    activeBitrate = progress * 1000;
                    if (txtBitrateValue != null) {
                        txtBitrateValue.setText(progress + " Kbps");
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        isCapturing = true;
        Toast.makeText(this, "Ready", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        isCapturing = false;
    }
}
