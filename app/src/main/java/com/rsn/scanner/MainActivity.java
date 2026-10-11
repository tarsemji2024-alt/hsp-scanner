package com.rsn.scanner;

import android.app.Activity;
import android.content.Intent;
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
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reconstructed native part of HSP Scanner.
 *
 * The original APK uses a WebView for the main UI and JavaScript bridge methods:
 *   Android.startScanner()
 *   Android.saveFile(base64, fileName)
 *
 * Google ML Kit Code Scanner is used for the native scanner, with auto-zoom enabled.
 */
public class MainActivity extends Activity {

    private static final int CREATE_FILE_REQUEST = 2001;
    private static final int OPEN_FILE_REQUEST = 2002;
    private static final int CREATE_BACKUP_REQUEST = 2003;
    private static final int OPEN_BACKUP_REQUEST = 2004;
    private static final int AUTH_REQUEST = 2005;
    private static final int MAX_SNAPSHOTS = 20;

    private WebView webView;
    private String pendingBase64;
    private String pendingFileName;
    private String pendingBackupJson;

    private GmsBarcodeScanner scanner;
    private CameraManager cameraManager;
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private String torchCameraId;
    private boolean torchOn = false;
    private boolean torchWanted = false;
    private boolean torchAutoSuppressed = false;
    private boolean autoTorchPending = false;
    private boolean lightSensorListening = false;
    private float lastLux = Float.NaN;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        webView = new WebView(this);
        ViewCompat.setOnApplyWindowInsetsListener(webView, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return windowInsets;
        });
        setContentView(webView);
        ViewCompat.requestApplyInsets(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");

        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        lightSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        findTorchCamera();
        if (cameraManager != null) {
            cameraManager.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (!cameraId.equals(torchCameraId)) return;
                    torchOn = enabled;
                    callJs("window.onTorchChanged(" + enabled + ",false);");
                    if (!enabled && shouldAutoTurnOn()) {
                        mainHandler.postDelayed(() -> setTorch(true, true), 250);
                    }
                }

                @Override public void onTorchModeUnavailable(String cameraId) {
                    if (!cameraId.equals(torchCameraId)) return;
                    torchOn = false;
                    autoTorchPending = shouldAutoTurnOn();
                    callJs("window.onTorchChanged(false,false);");
                }
            }, mainHandler);
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

    private void setTorch(boolean enabled, boolean automatic) {
        if (enabled && torchCameraId == null) {
            callJs("window.onTorchError('इस डिवाइस में फ्लैश उपलब्ध नहीं है।');");
            return;
        }
        try {
            if (cameraManager != null && torchCameraId != null) {
                cameraManager.setTorchMode(torchCameraId, enabled);
                torchOn = enabled;
                callJs("window.onTorchChanged(" + enabled + "," + automatic + ");");
            }
        } catch (Exception e) {
            if (automatic) {
                autoTorchPending = true;
            } else {
                callJs("window.onTorchError('लाइट कैमरा स्कैनर के उपयोग में होने के कारण नहीं बदली जा सकी।');");
            }
        }
    }

    private boolean shouldAutoTurnOn() {
        return torchWanted && !torchAutoSuppressed;
    }

    private final SensorEventListener lightListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            float lux = event.values[0];
            lastLux = lux;
            // यूज़र ने हाथ से लाइट बंद की हो तो वह तब तक बंद रहे जब तक यूज़र खुद चालू न करे।
            // (पहले यहाँ lux > 35 पर यह रोक हट जाती थी, इसलिए लाइट बंद करते ही दोबारा जल जाती थी।)
            if (lux < 20f && !torchOn && !torchAutoSuppressed) {
                torchWanted = true;
                autoTorchPending = true;
                setTorch(true, true);
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    @Override protected void onResume() {
        super.onResume();
        CloudBackup.scheduleSync(this);
        if (!lightSensorListening && sensorManager != null && lightSensor != null)
            lightSensorListening = sensorManager.registerListener(
                    lightListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override protected void onPause() {
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (lightSensorListening && sensorManager != null) sensorManager.unregisterListener(lightListener);
        super.onDestroy();
    }

    /**
     * Opens Google's native scanner. Auto-zoom is deliberately enabled.
     */
    @JavascriptInterface
    public void startScanner() {
        runOnUiThread(() -> {
            try {
                if (!Float.isNaN(lastLux) && lastLux < 20f && !torchAutoSuppressed) {
                    torchWanted = true;
                    autoTorchPending = true;
                    setTorch(true, true);
                }
                GmsBarcodeScannerOptions options =
                        new GmsBarcodeScannerOptions.Builder()
                                .enableAutoZoom()
                                .build();

                scanner = GmsBarcodeScanning.getClient(this, options);

                scanner.startScan()
                        .addOnSuccessListener(barcode -> {
                            String raw = barcode.getRawValue();
                            if (raw == null) raw = "";

                            final String value = raw;
                            webView.post(() -> callJs(
                                    "window.onNativeScan(" +
                                            jsString(value) + "," +
                                            jsString(value) + ");"
                            ));
                        })
                        .addOnCanceledListener(() ->
                                webView.post(() ->
                                        callJs("window.onNativeCancel();")))
                        .addOnFailureListener(e ->
                                webView.post(() ->
                                        callJs("window.onNativeError(" +
                                                jsString(e.getMessage() == null
                                                        ? "Unknown scanner error"
                                                        : e.getMessage()) +
                                                ");")));
            } catch (Exception e) {
                callJs("window.onNativeError(" +
                        jsString(e.getMessage() == null ? "Scanner error" : e.getMessage()) +
                        ");");
            }
        });
    }

    /**
     * Receives an XLSX workbook encoded as Base64 from index.html and opens
     * Android's "Save As" document picker.
     */
    @JavascriptInterface
    public void saveFile(String base64, String fileName) {
        pendingBase64 = base64;
        pendingFileName = (fileName == null || fileName.trim().isEmpty())
                ? "RSN_list.xlsx" : fileName;

        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                intent.putExtra(Intent.EXTRA_TITLE, pendingFileName);
                startActivityForResult(intent, CREATE_FILE_REQUEST);
            } catch (Exception e) {
                callJs("window.onExportCancel();");
                Toast.makeText(this, "सेव विंडो नहीं खुल सकी",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Opens Android's document picker for an existing spreadsheet or CSV. */
    @JavascriptInterface
    public void openImport() {
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel", "text/csv", "text/comma-separated-values"});
                startActivityForResult(intent, OPEN_FILE_REQUEST);
            } catch (Exception e) {
                Toast.makeText(this, "फाइल चुनने की विंडो नहीं खुल सकी", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void setTorch(boolean enabled) {
        runOnUiThread(() -> {
            if (!enabled) {
                torchAutoSuppressed = true;
                autoTorchPending = false;
                torchWanted = false;
            } else {
                torchAutoSuppressed = false;
                torchWanted = true;
            }
            setTorch(enabled, false);
        });
    }

    /** Shows scan feedback as a system bottom toast above the scanner UI. */
    @JavascriptInterface
    public void showScannerMessage(String message) {
        if (message == null || message.trim().isEmpty()) return;
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    // =====================  बैकअप / रिस्टोर  =====================

    private File backupDir() {
        File d = new File(getFilesDir(), "backups");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static void writeTextAtomic(File target, String text) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) throw new java.io.IOException("rename failed");
        }
    }

    private static String readText(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        long total = 0;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > 8L * 1024 * 1024) throw new java.io.IOException("file too large");
            bytes.write(buffer, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * ऐप के प्राइवेट फोल्डर में बैकअप रखता है।
     *  - latest.json  : हर बदलाव पर ताज़ा स्थिति (हमेशा overwrite)
     *  - snap_*.json  : जोखिम वाले काम से पहले की कॉपी (एक्सपोर्ट / सभी डिलीट / इंपोर्ट / रिस्टोर),
     *                   सिर्फ़ आख़िरी MAX_SNAPSHOTS रखी जाती हैं
     */
    private void autoBackup(String json, String reason) {
        if (json == null || json.isEmpty()) return;
        try {
            File dir = backupDir();
            writeTextAtomic(new File(dir, "latest.json"), json);
            if (reason != null && !reason.trim().isEmpty()) {
                String safe = reason.replaceAll("[^a-zA-Z0-9_-]", "");
                if (safe.isEmpty()) safe = "snap";
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                writeTextAtomic(new File(dir, "snap_" + stamp + "_" + safe + ".json"), json);
                pruneSnapshots(dir);
            }
            if (CloudBackup.isEnabled(this)) {
                CloudBackup.markDirty(this);
                CloudBackup.scheduleSync(this);
            }
        } catch (Exception ignored) { }
    }

    private void pruneSnapshots(File dir) {
        File[] files = dir.listFiles((d, n) -> n.startsWith("snap_") && n.endsWith(".json"));
        if (files == null || files.length <= MAX_SNAPSHOTS) return;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName())); // नई पहले
        for (int i = MAX_SNAPSHOTS; i < files.length; i++) files[i].delete();
    }

    private void addBackupInfo(JSONArray out, File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            JSONObject o = new JSONObject(readText(in));
            JSONArray list = o.optJSONArray("list");
            JSONObject item = new JSONObject();
            item.put("name", f.getName());
            item.put("time", o.optLong("time", f.lastModified()));
            item.put("reason", o.optString("reason", ""));
            item.put("count", list == null ? 0 : list.length());
            out.put(item);
        } catch (Exception ignored) { }
    }

    private String listBackupsJson() {
        JSONArray result = new JSONArray();
        try {
            File dir = backupDir();
            File latest = new File(dir, "latest.json");
            if (latest.exists()) addBackupInfo(result, latest);
            File[] files = dir.listFiles((d, n) -> n.startsWith("snap_") && n.endsWith(".json"));
            if (files != null) {
                Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName())); // नई पहले
                for (File f : files) addBackupInfo(result, f);
            }
        } catch (Exception ignored) { }
        return result.toString();
    }

    private void restoreAuto(String name) {
        if (name == null || !name.matches("^(latest|snap_[A-Za-z0-9_-]+)\\.json$")) {
            callJs("window.onBackupError('गलत बैकअप नाम');");
            return;
        }
        try (InputStream in = new java.io.FileInputStream(new File(backupDir(), name))) {
            callJs("window.onBackupFileLoaded(" + jsString(readText(in)) + ");");
        } catch (Exception e) {
            callJs("window.onBackupError('बैकअप पढ़ नहीं सके');");
        }
    }

    /** बैकअप को यूज़र की चुनी जगह (Drive / Downloads / SD कार्ड) पर JSON फाइल में सेव करता है। */
    private void saveBackupFile(String json, String fileName) {
        pendingBackupJson = json;
        final String name = (fileName == null || fileName.trim().isEmpty())
                ? "RSN_backup.json" : fileName;
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.putExtra(Intent.EXTRA_TITLE, name);
                startActivityForResult(intent, CREATE_BACKUP_REQUEST);
            } catch (Exception e) {
                pendingBackupJson = null;
                callJs("window.onBackupSaved(false);");
                Toast.makeText(this, "सेव विंडो नहीं खुल सकी", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** पुरानी बैकअप फाइल चुनने के लिए पिकर खोलता है। */
    private void openBackupFile() {
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, OPEN_BACKUP_REQUEST);
            } catch (Exception e) {
                Toast.makeText(this, "फाइल चुनने की विंडो नहीं खुल सकी", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // =====================  Google Drive बैकअप  =====================

    private void cloudError(String message) {
        callJs("window.onCloudError(" + jsString(message) + ");");
    }

    private void pushCloudStatus() {
        callJs("window.onCloudStatus();");
    }

    /** Gmail खाता चुनवाकर Drive की अनुमति लेता है। */
    private void cloudConnect() {
        runOnUiThread(() -> {
            try {
                Identity.getAuthorizationClient(this).authorize(CloudBackup.request())
                        .addOnSuccessListener(r -> {
                            if (r.hasResolution()) {
                                try {
                                    startIntentSenderForResult(
                                            r.getPendingIntent().getIntentSender(), AUTH_REQUEST, null, 0, 0, 0);
                                } catch (Exception e) {
                                    cloudError("Google साइन-इन विंडो नहीं खुल सकी");
                                }
                            } else {
                                onCloudAuthorized(r);
                            }
                        })
                        .addOnFailureListener(e -> cloudError("Google से जुड़ नहीं सके: "
                                + (e.getMessage() == null ? "अज्ञात एरर" : e.getMessage())));
            } catch (Exception e) {
                cloudError("Google से जुड़ नहीं सके");
            }
        });
    }

    private void onCloudAuthorized(AuthorizationResult r) {
        new Thread(() -> {
            try {
                String token = r.getAccessToken();
                String email = CloudBackup.fetchEmail(token);
                boolean remote = CloudBackup.findFileId(token) != null;
                // क्लाउड में पहले से बैकअप हो तो उसे अपने आप ओवरराइट नहीं करते (यूज़र तय करेगा)
                CloudBackup.connect(this, email, remote);
                if (!remote && CloudBackup.hasLocalData(this)) {
                    try { CloudBackup.uploadLatest(this); }
                    catch (Exception e) { CloudBackup.scheduleSync(this); }
                }
                pushCloudStatus();
            } catch (Exception e) {
                cloudError("जोड़ने में समस्या: " + (e.getMessage() == null ? "इंटरनेट जाँचें" : e.getMessage()));
            }
        }).start();
    }

    private void cloudBackupNow() {
        new Thread(() -> {
            try {
                if (!CloudBackup.isEnabled(this)) { cloudError("पहले Gmail से जोड़ें"); return; }
                if (!CloudBackup.hasLocalData(this)) { cloudError("बैकअप के लिए अभी कोई RSN नहीं है"); return; }
                CloudBackup.resolveHold(this);
                CloudBackup.uploadLatest(this);
                pushCloudStatus();
            } catch (CloudBackup.NeedsAuth e) {
                cloudError("Google की अनुमति खत्म हो गई, दोबारा 'Gmail से जोड़ें' दबाएँ");
            } catch (Exception e) {
                cloudError("बैकअप नहीं हो सका। इंटरनेट जाँचें। (अपने आप दोबारा कोशिश होगी)");
                CloudBackup.scheduleSync(this);
            }
        }).start();
    }

    private void cloudRestore() {
        new Thread(() -> {
            try {
                if (!CloudBackup.isEnabled(this)) { cloudError("पहले Gmail से जोड़ें"); return; }
                String json = CloudBackup.downloadBackup(this);
                if (json == null) { cloudError("क्लाउड में अभी कोई बैकअप नहीं मिला"); return; }
                callJs("window.onCloudFileLoaded(" + jsString(json) + ");");
            } catch (CloudBackup.NeedsAuth e) {
                cloudError("Google की अनुमति खत्म हो गई, दोबारा 'Gmail से जोड़ें' दबाएँ");
            } catch (Exception e) {
                cloudError("क्लाउड से बैकअप नहीं ला सके। इंटरनेट जाँचें।");
            }
        }).start();
    }

    private void cloudDisable() {
        CloudBackup.disconnect(this);
        pushCloudStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == AUTH_REQUEST) {
            if (resultCode != RESULT_OK || data == null) { cloudError("Google से जुड़ना रद्द हुआ"); return; }
            try {
                onCloudAuthorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data));
            } catch (Exception e) {
                cloudError("Google से अनुमति नहीं मिली");
            }
            return;
        }

        if (requestCode == CREATE_BACKUP_REQUEST) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                pendingBackupJson = null;
                callJs("window.onBackupSaved(false);");
                return;
            }
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new IllegalStateException("Cannot open file");
                out.write(pendingBackupJson.getBytes(StandardCharsets.UTF_8));
                out.flush();
                callJs("window.onBackupSaved(true);");
            } catch (Exception e) {
                callJs("window.onBackupSaved(false);");
                Toast.makeText(this, "बैकअप सेव नहीं हो सका", Toast.LENGTH_SHORT).show();
            } finally {
                pendingBackupJson = null;
            }
            return;
        }

        if (requestCode == OPEN_BACKUP_REQUEST) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                if (in == null) throw new IllegalStateException("Cannot read file");
                callJs("window.onBackupFileLoaded(" + jsString(readText(in)) + ");");
            } catch (Exception e) {
                Toast.makeText(this, "बैकअप फाइल पढ़ नहीं सके", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == OPEN_FILE_REQUEST) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                if (in == null) throw new IllegalStateException("Cannot read file");
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
                String encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
                callJs("window.onImportFile(" + jsString(encoded) + ");");
            } catch (Exception e) {
                Toast.makeText(this, "इंपोर्ट फाइल पढ़ नहीं सके", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode != CREATE_FILE_REQUEST) return;

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            callJs("window.onExportCancel();");
            clearPendingFile();
            return;
        }

        Uri uri = data.getData();

        try {
            byte[] bytes = Base64.decode(pendingBase64, Base64.DEFAULT);

            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Cannot open file");
                out.write(bytes);
                out.flush();
            }

            callJs("window.onExportDone();");
        } catch (Exception e) {
            callJs("window.onExportCancel();");
            Toast.makeText(this, "फाइल सेव नहीं हो सकी",
                    Toast.LENGTH_SHORT).show();
        } finally {
            clearPendingFile();
        }
    }

    private void clearPendingFile() {
        pendingBase64 = null;
        pendingFileName = null;
    }

    private void callJs(String javascript) {
        if (webView == null) return;
        runOnUiThread(() -> webView.evaluateJavascript(javascript, null));
    }

    private static String jsString(String value) {
        if (value == null) return "null";

        String escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");

        return "\"" + escaped + "\"";
    }

    private class Bridge {

        @JavascriptInterface
        public void startScanner() {
            MainActivity.this.startScanner();
        }

        @JavascriptInterface
        public void saveFile(String base64, String fileName) {
            MainActivity.this.saveFile(base64, fileName);
        }

        @JavascriptInterface
        public void openImport() { MainActivity.this.openImport(); }

        @JavascriptInterface
        public void setTorch(boolean enabled) { MainActivity.this.setTorch(enabled); }

        @JavascriptInterface
        public void showScannerMessage(String message) {
            MainActivity.this.showScannerMessage(message);
        }

        // ---- बैकअप / रिस्टोर ----
        @JavascriptInterface
        public void autoBackup(String json, String reason) { MainActivity.this.autoBackup(json, reason); }

        @JavascriptInterface
        public String listBackups() { return MainActivity.this.listBackupsJson(); }

        @JavascriptInterface
        public void restoreAuto(String name) { MainActivity.this.restoreAuto(name); }

        @JavascriptInterface
        public void saveBackupFile(String json, String fileName) { MainActivity.this.saveBackupFile(json, fileName); }

        @JavascriptInterface
        public void openBackupFile() { MainActivity.this.openBackupFile(); }

        // ---- Google Drive बैकअप ----
        @JavascriptInterface
        public String cloudStatus() { return CloudBackup.statusJson(MainActivity.this); }

        @JavascriptInterface
        public void cloudConnect() { MainActivity.this.cloudConnect(); }

        @JavascriptInterface
        public void cloudBackupNow() { MainActivity.this.cloudBackupNow(); }

        @JavascriptInterface
        public void cloudRestore() { MainActivity.this.cloudRestore(); }

        @JavascriptInterface
        public void cloudDisable() { MainActivity.this.cloudDisable(); }

        @JavascriptInterface
        public void cloudResolved() { CloudBackup.resolveHold(MainActivity.this); }
    }
}
