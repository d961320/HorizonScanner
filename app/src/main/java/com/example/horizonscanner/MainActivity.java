package com.example.horizonscanner;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.*;
import androidx.appcompat.widget.Toolbar;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final int MODE_PHONE = 0;
    private static final int MODE_CAMERA = 1;
    private static final String PREFS = "prefs";
    private static final String PREF_MODE = "mode";
    private static final String PREF_FOLDER = "folder";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private SensorManager sensorManager;
    private Sensor rotationSensor;

    private TextView txtAngle, txtFolder, statusText;
    private Button btnStart, btnStop;
    private PreviewView previewView;
    private ImageView crosshair;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    private int pointingMode = MODE_PHONE;
    private Uri folderUri;
    private Uri lastSavedFileUri;

    private boolean recording = false;
    private int lastAzimuth = -1;

    private final List<String> data = new ArrayList<>();

    private ActivityResultLauncher<Intent> folderPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                    folderUri = r.getData().getData();
                    getContentResolver().takePersistableUriPermission(
                            folderUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                    savePrefs();
                    updateFolderText();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        txtAngle = findViewById(R.id.txtAngle);
        txtFolder = findViewById(R.id.txtFolder);
        statusText = findViewById(R.id.txtStatus);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        previewView = findViewById(R.id.preview_view);
        crosshair = findViewById(R.id.crosshair);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        loadPrefs();
        updateFolderText();
        showStartupDialog();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        btnStart.setOnClickListener(v -> startScan());
        btnStop.setOnClickListener(v -> stopScan());
    }

    /* ---------- MENU ---------- */

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            showPointingModeDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /* ---------- DIALOGER ---------- */

    private void showStartupDialog() {
        String[] options = {
                getString(R.string.point_phone),
                getString(R.string.point_camera)
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.pointing_mode)
                .setCancelable(false)
                .setSingleChoiceItems(options, pointingMode, (d, w) -> {
                    pointingMode = w;
                    updateCameraVisibility();
                })
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    savePrefs();
                    folderPicker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE));
                })
                .show();
    }

    private void showPointingModeDialog() {
        String[] options = {
                getString(R.string.point_phone),
                getString(R.string.point_camera)
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.pointing_mode)
                .setSingleChoiceItems(options, pointingMode, (d, w) -> {
                    pointingMode = w;
                    updateCameraVisibility();
                })
                .setPositiveButton(android.R.string.ok, (d, w) -> savePrefs())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showFileActionsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_saved)
                .setMessage(R.string.file_saved_actions)
                .setPositiveButton(R.string.view_file, (d, w) -> openFile())
                .setNeutralButton(R.string.share_file, (d, w) -> shareFile())
                .setNegativeButton(android.R.string.ok, null)
                .show();
    }

    /* ---------- SENSOR ---------- */

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!recording) return;

        float[] rotationMatrix = new float[9];
        float[] O = new float[3];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, O);

        int azimuth = (int) (Math.toDegrees(O[0]) + 360) % 360;
        float pitchDeg = (float) Math.toDegrees(O[1]);

        int elevation = (pointingMode == MODE_CAMERA)
                ? Math.round(90f - Math.abs(pitchDeg))
                : Math.round(-pitchDeg);

        txtAngle.setText(getString(R.string.angle_format, azimuth, elevation));

        if (azimuth != lastAzimuth) {
            data.add(azimuth + " " + elevation);
            lastAzimuth = azimuth;
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    /* ---------- SCAN ---------- */

    private void startScan() {
        if (folderUri == null) {
            showStartupDialog();
            return;
        }

        data.clear();
        recording = true;
        lastAzimuth = -1;

        btnStart.setVisibility(View.GONE);
        btnStop.setVisibility(View.VISIBLE);

        statusText.setText(R.string.status_scanning);
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
    }

    private void stopScan() {
        recording = false;
        sensorManager.unregisterListener(this);

        btnStart.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.GONE);

        saveFile();
        statusText.setText(R.string.status_done);
    }

    /* ---------- FIL ---------- */

    private void saveFile() {
        try {
            DocumentFile folder = DocumentFile.fromTreeUri(this, folderUri);
            if (folder == null) return;

            String name = "horizon_" + System.currentTimeMillis() + ".txt";
            DocumentFile file = folder.createFile("text/plain", name);
            if (file == null) return;

            lastSavedFileUri = file.getUri();

            try (OutputStream os = getContentResolver().openOutputStream(file.getUri())) {
                for (String line : data) {
                    os.write((line + "\n").getBytes());
                }
            }

            showFileActionsDialog();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void openFile() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(lastSavedFileUri, "text/plain");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
    }

    private void shareFile() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_STREAM, lastSavedFileUri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, getString(R.string.share_file)));
    }

    /* ---------- PREFS ---------- */

    private void savePrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        e.putInt(PREF_MODE, pointingMode);
        if (folderUri != null) e.putString(PREF_FOLDER, folderUri.toString());
        e.apply();
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        pointingMode = p.getInt(PREF_MODE, MODE_PHONE);
        String u = p.getString(PREF_FOLDER, null);
        if (u != null) folderUri = Uri.parse(u);
        updateCameraVisibility();
    }

    private void updateFolderText() {
        txtFolder.setText(folderUri == null
                ? getString(R.string.no_folder)
                : folderUri.toString());
    }

    /* ---------- CAMERA ---------- */

    private void updateCameraVisibility() {
        int visibility = pointingMode == MODE_CAMERA ? View.VISIBLE : View.GONE;
        previewView.setVisibility(visibility);
        crosshair.setVisibility(visibility);
    }

    private void startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                // No errors need to be handled for this Future.
                // This should never be reached.
            }
        }, ContextCompat.getMainExecutor(this));
    }

    void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder()
                .build();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        cameraProvider.bindToLifecycle(this, cameraSelector, preview);
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, 
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}