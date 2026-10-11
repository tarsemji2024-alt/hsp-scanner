package com.rsn.scanner;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * HSP Scanner - पूरी तरह native (कोई WebView / index.html नहीं)।
 *
 * - Google ML Kit Code Scanner (auto-zoom चालू), लगातार स्कैन
 * - सिर्फ़ "R" से शुरू होने वाले RSN स्वीकार, डुप्लिकेट नहीं जुड़ते
 * - हर RSN के आगे डिलीट + सभी डिलीट
 * - XLSX एक्सपोर्ट (Save As विंडो) और XLSX/CSV इंपोर्ट
 * - टॉर्च बटन + कम रोशनी में अपने आप लाइट
 */
@SuppressWarnings("deprecation")
public class MainActivity extends Activity {

    private static final int CREATE_FILE_REQUEST = 2001;
    private static final int OPEN_FILE_REQUEST = 2002;
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String PREFS = "rsn_prefs";
    private static final String KEY_LIST = "rsn_list_v1";

    private static final long NEXT_SCAN_DELAY_MS = 1700;
    private static final float DARK_LUX = 20f;
    private static final float BRIGHT_LUX = 35f;

    // ---- UI ----
    private TextView msgView;
    private TextView emptyView;
    private Button scanButton;
    private Button torchButton;
    private RsnAdapter adapter;

    // ---- डेटा ----
    private final List<String> list = new ArrayList<>();
    private SharedPreferences prefs;
    private byte[] pendingExport;

    // ---- स्कैन ----
    private boolean scanMode = false;      // लगातार स्कैन चालू है
    private boolean scannerOpen = false;   // Google का स्कैनर अभी खुला है
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable nextScanRunnable = () -> {
        if (scanMode && !isFinishing()) openScanner();
    };

    // ---- लाइट ----
    private CameraManager cameraManager;
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private String torchCameraId;
    private boolean torchOn = false;
    private boolean torchWanted = false;
    private boolean torchAutoSuppressed = false;
    private boolean lightSensorListening = false;
    private float lastLux = Float.NaN;
    private CameraManager.TorchCallback torchCallback;

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadList();

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            androidx.core.graphics.Insets bars =
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);

        ListView listView = findViewById(R.id.list);
        View header = LayoutInflater.from(this).inflate(R.layout.header_main, listView, false);
        listView.addHeaderView(header, null, false);
        adapter = new RsnAdapter();
        listView.setAdapter(adapter);

        msgView = header.findViewById(R.id.msg);
        emptyView = header.findViewById(R.id.empty);
        scanButton = header.findViewById(R.id.btnScan);
        torchButton = header.findViewById(R.id.btnTorch);

        scanButton.setOnClickListener(v -> onScanButton());
        header.findViewById(R.id.btnExport).setOnClickListener(v -> onExport());
        header.findViewById(R.id.btnImport).setOnClickListener(v -> openImport());
        torchButton.setOnClickListener(v -> onTorchButton());
        ((ImageButton) header.findViewById(R.id.btnClearAll))
                .setOnClickListener(v -> clearAll());

        setupTorch();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!lightSensorListening && sensorManager != null && lightSensor != null) {
            lightSensorListening = sensorManager.registerListener(
                    lightListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(nextScanRunnable);
        if (lightSensorListening && sensorManager != null) {
            sensorManager.unregisterListener(lightListener);
            lightSensorListening = false;
        }
        if (cameraManager != null && torchCallback != null) {
            cameraManager.unregisterTorchCallback(torchCallback);
        }
        super.onDestroy();
    }

    // ================================================================ संदेश

    private void say(String text, int colorRes) {
        msgView.setText(text);
        msgView.setTextColor(getColor(colorRes));
    }

    private void say(int stringRes, int colorRes, Object... args) {
        say(getString(stringRes, args), colorRes);
    }

    // ================================================================ लिस्ट और सेव

    private void loadList() {
        list.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
        } catch (Exception ignored) { }
    }

    private void saveList() {
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        prefs.edit().putString(KEY_LIST, arr.toString()).apply();
    }

    private void refresh() {
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** स्कैन/इंपोर्ट किए टेक्स्ट से RSN जोड़ता है; दिखाने लायक संदेश लौटाता है। */
    private String addCode(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) return "";

        String code = RsnUtil.extract(text);
        if (code.isEmpty()) {
            say(R.string.msg_skipped, R.color.msg_err);
            return getString(R.string.msg_skipped);
        }
        if (list.contains(code)) {
            say(R.string.msg_exists, R.color.msg_warn, code);
            return getString(R.string.msg_exists, code);
        }
        list.add(code);
        saveList();
        refresh();
        say(R.string.msg_added, R.color.msg_ok, code);
        return getString(R.string.msg_added, code);
    }

    private void deleteAt(int index) {
        if (index < 0 || index >= list.size()) return;
        String rsn = list.remove(index);
        saveList();
        refresh();
        say(R.string.msg_deleted, R.color.msg_warn, rsn);
    }

    private void clearAll() {
        if (list.isEmpty()) {
            say(R.string.msg_nothing_to_delete, R.color.msg_warn);
            return;
        }
        list.clear();
        saveList();
        refresh();
        say(R.string.msg_all_deleted, R.color.msg_ok);
    }

    // ================================================================ स्कैनर

    private void onScanButton() {
        if (scannerOpen) return;
        if (scanMode) {
            // अगले स्कैन के इंतज़ार में बटन दबाया = स्कैन बंद
            stopScanMode();
            return;
        }
        scanMode = true;
        scanButton.setText(R.string.btn_scan_on);
        say(R.string.msg_scan_continuous, R.color.text);
        openScanner();
    }

    private void stopScanMode() {
        scanMode = false;
        mainHandler.removeCallbacks(nextScanRunnable);
        scanButton.setText(R.string.btn_scan_start);
    }

    /** Google का native स्कैनर खोलता है; auto-zoom जानबूझकर चालू है। */
    private void openScanner() {
        if (scannerOpen) return;
        try {
            if (!Float.isNaN(lastLux) && lastLux < DARK_LUX && !torchAutoSuppressed) {
                torchWanted = true;
                setTorch(true, true);
            }

            GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                    .enableAutoZoom()
                    .build();
            GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, options);

            scannerOpen = true;
            scanner.startScan()
                    .addOnSuccessListener(barcode -> {
                        scannerOpen = false;
                        String raw = barcode.getRawValue();
                        if (raw == null) raw = barcode.getDisplayValue();
                        onScanned(raw == null ? "" : raw);
                    })
                    .addOnCanceledListener(() -> {
                        scannerOpen = false;
                        onScanCancelled();
                    })
                    .addOnFailureListener(e -> {
                        scannerOpen = false;
                        onScanError(e.getMessage());
                    });
        } catch (Exception e) {
            scannerOpen = false;
            onScanError(e.getMessage());
        }
    }

    private void onScanned(String value) {
        String message = addCode(value);
        if (!message.isEmpty()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
        // स्कैनर अपने आप दोबारा खुलता है; संदेश पढ़ने के लिए थोड़ा रुकता है
        if (scanMode) mainHandler.postDelayed(nextScanRunnable, NEXT_SCAN_DELAY_MS);
    }

    private void onScanCancelled() {
        stopScanMode();
        say(R.string.msg_scan_stopped, R.color.msg_warn, list.size());
    }

    private void onScanError(String message) {
        stopScanMode();
        String text = getString(R.string.msg_scanner_error,
                message == null ? getString(R.string.msg_scanner_unknown) : message);
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        say(text, R.color.msg_err);
    }

    // ================================================================ एक्सपोर्ट

    private void onExport() {
        if (list.isEmpty()) {
            say(R.string.msg_export_empty, R.color.msg_err);
            return;
        }
        try {
            pendingExport = XlsxUtil.write(new ArrayList<>(list));
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(XLSX_MIME);
            intent.putExtra(Intent.EXTRA_TITLE, "RSN_list.xlsx");
            startActivityForResult(intent, CREATE_FILE_REQUEST);
        } catch (Exception e) {
            pendingExport = null;
            say(R.string.msg_export_cancel, R.color.msg_warn);
            Toast.makeText(this, R.string.msg_save_window_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void finishExport(Uri uri) {
        byte[] bytes = pendingExport;
        pendingExport = null;
        try {
            if (bytes == null) throw new IllegalStateException("no data");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Cannot open file");
                out.write(bytes);
                out.flush();
            }
            // फाइल सेव होते ही लिस्ट खाली, नई स्कैनिंग के लिए तैयार
            list.clear();
            saveList();
            refresh();
            say(R.string.msg_export_done, R.color.msg_ok);
        } catch (Exception e) {
            say(R.string.msg_export_cancel, R.color.msg_warn);
            Toast.makeText(this, R.string.msg_save_fail, Toast.LENGTH_SHORT).show();
        }
    }

    // ================================================================ इंपोर्ट

    private void openImport() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    XLSX_MIME, "application/vnd.ms-excel",
                    "text/csv", "text/comma-separated-values"});
            startActivityForResult(intent, OPEN_FILE_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, R.string.msg_import_picker_fail, Toast.LENGTH_SHORT).show();
        }
    }

    private void readImportFile(Uri uri) {
        new Thread(() -> {
            byte[] data = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Cannot read file");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
                data = bytes.toByteArray();
            } catch (Exception ignored) { }

            final byte[] result = data;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (result == null) {
                    Toast.makeText(this, R.string.msg_import_read_fail, Toast.LENGTH_SHORT).show();
                } else {
                    importBytes(result);
                }
            });
        }).start();
    }

    private void importBytes(byte[] data) {
        List<List<String>> rows;
        try {
            if (XlsxUtil.isXlsx(data)) {
                rows = XlsxUtil.read(data);
            } else if (XlsxUtil.isLegacyXls(data)) {
                say(R.string.msg_import_xls, R.color.msg_err);
                return;
            } else {
                rows = CsvUtil.parse(data);
            }
        } catch (Exception e) {
            say(R.string.msg_import_bad_file, R.color.msg_err);
            return;
        }

        // पहली पंक्ति में RSN / serial / code जैसा हेडर हो तो वही कॉलम पढ़ें
        int start = 0, col = -1;
        if (!rows.isEmpty()) {
            List<String> first = rows.get(0);
            for (int c = 0; c < first.size(); c++) {
                if (first.get(c).trim().matches("(?i)^(rsn|serial\\s*(number|no)?|code)$")) {
                    col = c;
                    start = 1;
                    break;
                }
            }
        }

        int added = 0, duplicates = 0, invalid = 0;
        for (int i = start; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            List<String> cells = new ArrayList<>();
            if (col >= 0) cells.add(col < row.size() ? row.get(col) : "");
            else cells.addAll(row);

            boolean found = false, hasContent = false;
            for (String cell : cells) {
                if (!cell.trim().isEmpty()) hasContent = true;
                String code = RsnUtil.extract(cell);
                if (code.isEmpty()) continue;
                found = true;
                if (list.contains(code)) duplicates++;
                else { list.add(code); added++; }
            }
            if (!found && hasContent) invalid++;
        }
        saveList();
        refresh();

        StringBuilder sb = new StringBuilder(getString(R.string.msg_import_done, added));
        if (duplicates > 0) sb.append(getString(R.string.msg_import_dups, duplicates));
        if (invalid > 0) sb.append(getString(R.string.msg_import_invalid, invalid));
        say(sb.toString(), R.color.msg_ok);
    }

    // ================================================================ नतीजे

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == OPEN_FILE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                readImportFile(data.getData());
            }
            return;
        }

        if (requestCode == CREATE_FILE_REQUEST) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                pendingExport = null;
                say(R.string.msg_export_cancel, R.color.msg_warn);
                return;
            }
            finishExport(data.getData());
        }
    }

    // ================================================================ लाइट (टॉर्च)

    private void setupTorch() {
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        lightSensor = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        findTorchCamera();

        if (cameraManager != null) {
            torchCallback = new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (!cameraId.equals(torchCameraId)) return;
                    torchOn = enabled;
                    updateTorchButton();
                    if (!enabled && shouldAutoTurnOn()) {
                        mainHandler.postDelayed(() -> setTorch(true, true), 250);
                    }
                }

                @Override public void onTorchModeUnavailable(String cameraId) {
                    if (!cameraId.equals(torchCameraId)) return;
                    torchOn = false;
                    updateTorchButton();
                }
            };
            cameraManager.registerTorchCallback(torchCallback, mainHandler);
        }
    }

    private void findTorchCamera() {
        if (cameraManager == null) return;
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchCameraId = id;
                    return;
                }
            }
        } catch (Exception ignored) { }
    }

    private void updateTorchButton() {
        torchButton.setText(torchOn ? R.string.btn_torch_off : R.string.btn_torch_on);
    }

    private boolean shouldAutoTurnOn() {
        return torchWanted && !torchAutoSuppressed;
    }

    /** यूज़र के बटन दबाने पर: बंद करने पर auto-लाइट रुक जाती है। */
    private void onTorchButton() {
        boolean turnOn = !torchOn;
        if (turnOn) {
            torchAutoSuppressed = false;
            torchWanted = true;
        } else {
            torchAutoSuppressed = true;
            torchWanted = false;
        }
        setTorch(turnOn, false);
        if (turnOn == torchOn) {
            say(turnOn ? R.string.msg_torch_on : R.string.msg_torch_off, R.color.text);
        }
    }

    private void setTorch(boolean enabled, boolean automatic) {
        if (enabled && torchCameraId == null) {
            say(R.string.msg_no_flash, R.color.msg_err);
            return;
        }
        try {
            if (cameraManager != null && torchCameraId != null) {
                cameraManager.setTorchMode(torchCameraId, enabled);
                torchOn = enabled;
                updateTorchButton();
                if (automatic) {
                    say(enabled ? R.string.msg_torch_auto_on : R.string.msg_torch_auto_off,
                            R.color.text);
                }
            }
        } catch (Exception e) {
            if (!automatic) say(R.string.msg_torch_busy, R.color.msg_err);
        }
    }

    private final SensorEventListener lightListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            float lux = event.values[0];
            lastLux = lux;
            if (lux > BRIGHT_LUX) torchAutoSuppressed = false;
            if (lux < DARK_LUX && !torchOn && !torchAutoSuppressed) {
                torchWanted = true;
                setTorch(true, true);
            }
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    // ================================================================ adapter

    private class RsnAdapter extends BaseAdapter {
        @Override public int getCount() { return list.size(); }
        @Override public Object getItem(int position) { return list.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_rsn, parent, false);
            }
            ((TextView) row.findViewById(R.id.itemNo)).setText(String.valueOf(position + 1));
            ((TextView) row.findViewById(R.id.itemRsn)).setText(list.get(position));
            row.findViewById(R.id.itemDelete).setOnClickListener(v -> deleteAt(position));
            return row;
        }
    }
}
