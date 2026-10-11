package com.rsn.scanner;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;


public class CloudBackup {

    static final int JOB_ID = 7301;
    private static final String PREFS = "cloud_backup";
    private static final String FILE_NAME = "rsn_backup.json";
    private static final String SCOPE_DRIVE = "https://www.googleapis.com/auth/drive.appdata";

    static class NeedsAuth extends Exception {
        NeedsAuth() { super("NEEDS_AUTH"); }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) { return prefs(c).getBoolean("enabled", false); }
    static boolean isDirty(Context c) { return prefs(c).getBoolean("dirty", false); }
    static boolean isHold(Context c) { return prefs(c).getBoolean("hold", false); }

    static void markDirty(Context c) {
    SharedPreferences p = prefs(c);
    p.edit().putBoolean("dirty", true).putLong("ver", p.getLong("ver", 0) + 1).apply();
    }

    static void connect(Context c, String email, boolean remoteExists) {
        prefs(c).edit()
                .putBoolean("enabled", true)
                .putString("email", email == null ? "" : email)
                .putBoolean("hold", remoteExists) 
                .putBoolean("dirty", !remoteExists)
                .apply();
    }

    static void disconnect(Context c) {
        prefs(c).edit().clear().apply();
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Exception ignored) { }
    }

       static void resolveHold(Context c) {
        SharedPreferences p = prefs(c);
        p.edit().putBoolean("hold", false).putBoolean("dirty", true).putLong("ver", p.getLong("ver", 0) + 1).apply();
        scheduleSync(c);
    }

    static String statusJson(Context c) {
        SharedPreferences p = prefs(c);
        try {
            JSONObject o = new JSONObject();
            o.put("enabled", p.getBoolean("enabled", false));
            o.put("email", p.getString("email", ""));
            o.put("lastSync", p.getLong("lastSync", 0));
            o.put("dirty", p.getBoolean("dirty", false));
            o.put("hold", p.getBoolean("hold", false));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }


    static void scheduleSync(Context c) {
        try {
            if (!isEnabled(c) || !isDirty(c) || isHold(c)) return;
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, SyncJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(5000)
                    .build();
            js.schedule(job);
        } catch (Exception ignored) { }
    }

   static AuthorizationRequest request() {
        return AuthorizationRequest.builder()
                .setRequestedScopes(Arrays.asList(new Scope(SCOPE_DRIVE), new Scope("email")))
                .build();
    }

    private static String silentToken(Context c) throws Exception {
        AuthorizationResult r = Tasks.await(
                Identity.getAuthorizationClient(c).authorize(request()), 30, TimeUnit.SECONDS);
        if (r.hasResolution() || r.getAccessToken() == null) throw new NeedsAuth();
        return r.getAccessToken();
    }

    private static String http(String method, String url, String token,
                               String contentType, byte[] body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        boolean patch = "PATCH".equals(method); // HttpURLConnection PATCH नहीं जानता: POST + override
        conn.setRequestMethod(patch ? "POST" : method);
        if (patch) conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = conn.getOutputStream()) { out.write(body); }
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String text = in == null ? "" : readAll(in);
        if (code == 401) throw new NeedsAuth();
        if (code >= 400) throw new IOException("HTTP " + code + " " + text);
        return text;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) != -1) bytes.write(buf, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String findFileId(String token) throws Exception {
        String q = URLEncoder.encode("name='" + FILE_NAME + "'", "UTF-8");
        String res = http("GET", "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=" + q
                + "&fields=files(id)&pageSize=1", token, null, null);
        JSONArray files = new JSONObject(res).optJSONArray("files");
        return (files != null && files.length() > 0) ? files.getJSONObject(0).getString("id") : null;
    }

    static String fetchEmail(String token) {
        try {
            String res = http("GET", "https://www.googleapis.com/oauth2/v3/userinfo", token, null, null);
            return new JSONObject(res).optString("email", "");
        } catch (Exception e) {
            return "";
        }
    }

    static boolean hasLocalData(Context c) {
        return new File(new File(c.getFilesDir(), "backups"), "latest.json").exists();
    }

        static synchronized void uploadLatest(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        File f = new File(new File(c.getFilesDir(), "backups"), "latest.json");
        if (!f.exists()) { p.edit().putBoolean("dirty", false).apply(); return; }

        long startVer = p.getLong("ver", 0);
        String json = readAll(new FileInputStream(f));
        String token = silentToken(c);
        String id = findFileId(token);

        if (id == null) {
            String boundary = "rsnb" + System.currentTimeMillis();
            String meta = "{\"name\":\"" + FILE_NAME + "\",\"parents\":[\"appDataFolder\"]}";
            String body = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta
                    + "\r\n--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + json
                    + "\r\n--" + boundary + "--";
            http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", token,
                    "multipart/related; boundary=" + boundary, body.getBytes(StandardCharsets.UTF_8));
        } else {
            http("PATCH", "https://www.googleapis.com/upload/drive/v3/files/" + id + "?uploadType=media", token,
                    "application/json; charset=UTF-8", json.getBytes(StandardCharsets.UTF_8));
        }

        SharedPreferences.Editor e = p.edit().putLong("lastSync", System.currentTimeMillis());
        if (p.getLong("ver", 0) == startVer) e.putBoolean("dirty", false); // अपलोड के दौरान नया बदलाव आया तो dirty रहे
        e.apply();
        if (p.getBoolean("dirty", false)) scheduleSync(c);
    }

        static String downloadBackup(Context c) throws Exception {
        String token = silentToken(c);
        String id = findFileId(token);
        if (id == null) return null;
        return http("GET", "https://www.googleapis.com/drive/v3/files/" + id + "?alt=media", token, null, null);
    }
}
