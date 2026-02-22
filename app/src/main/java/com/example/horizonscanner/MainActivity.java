package com.example.horizonscanner;

import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.OutputStream;
import java.util.Locale;
import java.util.TreeMap;

public class MainActivity extends AppCompatActivity
        implements SensorEventListener {

    private static final String PREFS = "prefs";
    private static final String PREF_FOLDER = "folder";
    private static final String PREF_MODE = "mode";

    private static final int MODE_PHONE = 0;
    private static final int MODE_CAMERA = 1;

    private SensorManager sensorManager;
    private Sensor rotationSensor;

    private boolean recording = false;
    private float lastAzimuth = -1;

    private final TreeMap<Integer, Integer> data = new TreeMap<>();

    private TextView statusText, liveAngleText, folderText;
    private Button btnStart, btnStop;
    private PreviewView cameraPreview;

    private Uri folderUri;
    private int pointingMode = MODE_PHONE;

    private final ActivityResultLauncher<Intent> folderPicker =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            folderUri = result.getData().getData();
                            getContentResolver().takePersistableUriPermission(
                                    folderUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            );
                            savePrefs();
                            updateFolderText();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        liveAngleText = findViewById(R.id.liveAngleText);
        folderText = findViewById(R.id.folderText);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        cameraPreview = findViewById(R.id.cameraPreview);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                : null;

        loadPrefs();
        updateFolderText();
        updateCamera();

        btnStart.setOnClickListener(v -> startScan());
        btnStop.setOnClickListener(v -> stopScan());
    }

    // ---------- MENU ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            showSettingsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSettingsDialog() {
        String[] options = {
                getString(R.string.point_phone),
                getString(R.string.point_camera)
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.pointing_mode)
                .setSingleChoiceItems(options, pointingMode, (d, which) -> {
                    pointingMode = which;
                    savePrefs();
                    updateCamera();
                    d.dismiss();
                })
                .setPositiveButton(R.string.menu_settings, (d, w) ->
                        folderPicker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)))
                .show();
    }

    // ---------- SCAN ----------

    private void startScan() {
        if (folderUri == null || rotationSensor == null) {
            statusText.setText(R.string.status_no_folder);
            return;
        }

        data.clear();
        recording = true;
        lastAzimuth = -1;

        btnStart.setVisibility(View.GONE);
        btnStop.setVisibility(View.VISIBLE);

        statusText.setText(R.string.status_scanning);

        sensorManager.registerListener(
                this,
                rotationSensor,
                SensorManager.SENSOR_DELAY_UI
        );
    }

    private void stopScan() {
        recording = false;
        sensorManager.unregisterListener(this);

        btnStop.setVisibility(View.GONE);
        btnStart.setVisibility(View.VISIBLE);

        statusText.setText(R.string.status_stopped);

        if (!data.isEmpty()) saveCsv();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!recording) return;

        float[] R = new float[9];
        float[] O = new float[3];

        SensorManager.getRotationMatrixFromVector(R, event.values);
        SensorManager.getOrientation(R, O);

        float azimuth = (float) Math.toDegrees(O[0]);
        if (azimuth < 0) azimuth += 360;

        int elevation = Math.round(-(float) Math.toDegrees(O[1]));
        liveAngleText.setText(elevation + "°");

        int az = Math.round(azimuth);
        if (!data.containsKey(az)) data.put(az, elevation);

        if (lastAzimuth > 300 && azimuth < 60) stopScan();
        lastAzimuth = azimuth;
    }

    // ---------- SAVE ----------

    private void saveCsv() {
        try {
            DocumentFile folder = DocumentFile.fromTreeUri(this, folderUri);
            String name = "horizon_" + System.currentTimeMillis() + ".txt";
            DocumentFile file = folder.createFile("text/plain", name);

            OutputStream out = getContentResolver().openOutputStream(file.getUri());
            out.write((getString(R.string.csv_header) + "\n").getBytes());

            for (Integer az : data.keySet()) {
                out.write(String.format(
                        Locale.US,
                        "%d %d\n",
                        az,
                        data.get(az)
                ).getBytes());
            }

            out.close();
            statusText.setText(getString(R.string.status_done, name));

        } catch (Exception e) {
            statusText.setText(R.string.status_error);
        }
    }

    // ---------- CAMERA ----------

    private void updateCamera() {
        if (pointingMode == MODE_CAMERA) {
            cameraPreview.setVisibility(View.VISIBLE);
            startCamera();
        } else {
            cameraPreview.setVisibility(View.GONE);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                provider.unbindAll();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());

                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview
                );

            } catch (Exception ignored) {}
        }, ContextCompat.getMainExecutor(this));
    }

    // ---------- PREFS ----------

    private void savePrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        if (folderUri != null) e.putString(PREF_FOLDER, folderUri.toString());
        e.putInt(PREF_MODE, pointingMode);
        e.apply();
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String u = p.getString(PREF_FOLDER, null);
        folderUri = u != null ? Uri.parse(u) : null;
        pointingMode = p.getInt(PREF_MODE, MODE_PHONE);
    }

    private void updateFolderText() {
        folderText.setText(folderUri == null
                ? getString(R.string.folder_not_set)
                : folderUri.getPath());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}