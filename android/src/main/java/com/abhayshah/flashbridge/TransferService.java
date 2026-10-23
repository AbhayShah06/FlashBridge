package com.abhayshah.flashbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import androidx.documentfile.provider.DocumentFile;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps the transfer alive after the screen locks; never overwrites an existing file. */
public class TransferService extends Service {
    public static final String SEND = "com.abhayshah.flashbridge.SEND";
    public static final String RECEIVE = "com.abhayshah.flashbridge.RECEIVE";
    private static final int PORT = 48888;
    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL = "transfers";
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private SharedPreferences prefs;
    private NotificationManager notifications;

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("flashbridge", MODE_PRIVATE);
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!BUSY.compareAndSet(false, true)) return START_NOT_STICKY;
        startForeground(NOTIFICATION_ID, notification("Starting transfer…"));
        new Thread(() -> {
            PowerManager.WakeLock wake = null;
            WifiManager.WifiLock wifi = null;
            try {
                wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlashBridge:Transfer");
                wake.acquire(30 * 60 * 1000L);
                WifiManager wifiManager = getSystemService(WifiManager.class);
                if (wifiManager != null) {
                    wifi = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FlashBridge:Transfer");
                    wifi.setReferenceCounted(false);
                    wifi.acquire();
                }
                if (SEND.equals(intent.getAction())) upload(intent.getData());
                else if (RECEIVE.equals(intent.getAction())) download(intent.getStringExtra("name"));
                else throw new Exception("Unknown transfer action");
            } catch (Exception e) { update("Transfer failed: " + e.getMessage()); }
            finally {
                if (wifi != null && wifi.isHeld()) wifi.release();
                if (wake != null && wake.isHeld()) wake.release();
                BUSY.set(false);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
        }, "flashbridge-transfer").start();
        return START_NOT_STICKY;
    }

    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("FlashBridge").setContentText(message).setOnlyAlertOnce(true)
                .setOngoing(true).setContentIntent(open).build();
    }

    private void update(String message) {
        prefs.edit().putString("status", message).apply();
        notifications.notify(NOTIFICATION_ID, notification(message));
    }

    private HttpURLConnection connection(String path, String method) throws Exception {
        String host = prefs.getString("host", "");
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + PORT + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(120000);
        c.setUseCaches(false);
        c.setRequestProperty("X-FlashBridge-Token", prefs.getString("token", ""));
        return c;
    }

    private void upload(Uri uri) throws Exception {
        if (uri == null) throw new Exception("Choose a file first");
        ContentResolver resolver = getContentResolver();
        String name = "file"; long size = -1;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
                if (!cursor.isNull(1)) size = cursor.getLong(1);
            }
        }
        if (size < 0) throw new Exception("The file provider did not give a size. Choose a local file.");
        if (name == null || name.isEmpty()) name = "file";
        HttpURLConnection c = connection("/v1/files?name=" + Uri.encode(name), "PUT");
        try {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(size);
            final long total = size;
            update("Sending " + name + "… 0%");
            try (InputStream in = resolver.openInputStream(uri); OutputStream out = c.getOutputStream()) {
                if (in == null) throw new Exception("Cannot open file");
                byte[] buf = new byte[256 * 1024]; long done = 0; int n; int last = -1;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n); done += n;
                    int percent = total == 0 ? 100 : (int)(done * 100 / total);
                    if (percent != last) { last = percent; update("Sending " + name + "… " + percent + "%"); }
                }
                if (done != size) throw new Exception("The source file changed during transfer");
            }
            if (c.getResponseCode() != 201) throw new Exception("PC rejected file (HTTP " + c.getResponseCode() + ")");
            String reply = readAll(c.getInputStream());
            update("Sent " + new JSONObject(reply).getString("name") + " to PC");
        } finally { c.disconnect(); }
    }

    private void download(String name) throws Exception {
        if (name == null || name.isEmpty()) throw new Exception("Choose a file first");
        String folderValue = prefs.getString("folder", "");
        if (folderValue.isEmpty()) throw new Exception("Choose a receive folder first");
        DocumentFile folder = DocumentFile.fromTreeUri(this, Uri.parse(folderValue));
        if (folder == null || !folder.canWrite()) throw new Exception("Receive folder is unavailable");
        DocumentFile existing = folder.findFile(name);
        if (existing != null && existing.isFile()) {
            HttpURLConnection hashRequest = connection("/v1/hash/" + Uri.encode(name), "GET");
            try {
                if (hashRequest.getResponseCode() == 200) {
                    JSONObject remote = new JSONObject(readAll(hashRequest.getInputStream()));
                    if (existing.length() == remote.getLong("size") && hash(existing.getUri()).equalsIgnoreCase(remote.getString("sha256"))) {
                        update("Already have " + name + " on phone");
                        return;
                    }
                }
            } finally { hashRequest.disconnect(); }
        }
        HttpURLConnection c = connection("/v1/files/" + Uri.encode(name), "GET");
        DocumentFile created = null;
        try {
            if (c.getResponseCode() != 200) throw new Exception("PC could not send file (HTTP " + c.getResponseCode() + ")");
            long size = c.getContentLengthLong();
            if (size < 0) throw new Exception("PC did not provide a file size");
            String target = name; int index = 2;
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            while (folder.findFile(target) != null) target = stem + " (" + index++ + ")" + ext;
            created = folder.createFile("application/octet-stream", ".flashbridge-" + System.currentTimeMillis() + ".part");
            if (created == null) throw new Exception("Could not create file in receive folder");
            update("Receiving " + target + "… 0%");
            try (InputStream in = c.getInputStream(); OutputStream out = getContentResolver().openOutputStream(created.getUri(), "w")) {
                if (out == null) throw new Exception("Could not write file");
                byte[] buf = new byte[256 * 1024]; long done = 0; int n; int last = -1;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n); done += n;
                    int percent = size == 0 ? 100 : (int)(done * 100 / size);
                    if (percent != last) { last = percent; update("Receiving " + target + "… " + percent + "%"); }
                }
                if (done != size) throw new Exception("Transfer interrupted after " + done + " of " + size + " bytes");
                out.flush();
            }
            if (!created.renameTo(target)) throw new Exception("Could not finish file in receive folder");
            update("Received " + target + " on phone");
        } catch (Exception e) {
            if (created != null) created.delete();
            throw e;
        } finally { c.disconnect(); }
    }

    private static String readAll(InputStream input) throws Exception {
        try (InputStream in = input; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String hash(Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("Cannot read existing file");
            byte[] b = new byte[256 * 1024]; int n;
            while ((n = input.read(b)) != -1) digest.update(b, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
