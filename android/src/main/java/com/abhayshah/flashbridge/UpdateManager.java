package com.abhayshah.flashbridge;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Same user-approved APK update flow as LocalMusic, using release asset hashes. */
final class UpdateManager {
    private static final String MANIFEST = "https://github.com/AbhayShah06/FlashBridge/releases/latest/download/update.json";
    private static JSONObject pendingPermission;
    private UpdateManager() {}

    static void check(Activity activity, LinearLayout parent) {
        new Thread(() -> {
            try {
                JSONObject manifest = new JSONObject(read(MANIFEST));
                int current = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
                if (manifest.getInt("versionCode") > current)
                    activity.runOnUiThread(() -> show(activity, parent, manifest));
            } catch (Exception ignored) { /* Offline transfers still work. */ }
        }, "flashbridge-update-check").start();
    }

    private static void show(Activity activity, LinearLayout parent, JSONObject manifest) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        TextView notice = new TextView(activity);
        notice.setText("Update " + manifest.optString("versionName") + " available");
        notice.setTextSize(16);
        notice.setTextColor(Color.rgb(244, 245, 242));
        notice.setPadding(0, 16, 0, 4);
        Button update = new Button(activity);
        update.setText("Update now");
        update.setAllCaps(false);
        update.setTextColor(Color.rgb(17, 18, 20));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(190, 242, 100));
        background.setCornerRadius(16 * activity.getResources().getDisplayMetrics().density);
        update.setBackground(background);
        update.setOnClickListener(v -> {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                pendingPermission = manifest;
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                Toast.makeText(activity, "Allow installs for FlashBridge; download will continue when you return.", Toast.LENGTH_LONG).show();
            } else downloadAndInstall(activity, update, manifest);
        });
        parent.addView(notice, 1);
        parent.addView(update, 2);
    }

    static void onResume(Activity activity) {
        if (pendingPermission != null && activity.getPackageManager().canRequestPackageInstalls()) {
            JSONObject manifest = pendingPermission;
            pendingPermission = null;
            downloadAndInstall(activity, null, manifest);
        }
    }

    private static void downloadAndInstall(Activity activity, Button button, JSONObject manifest) {
        if (button != null) { button.setEnabled(false); button.setText("Downloading update…"); }
        new Thread(() -> {
            File temporary = null;
            try {
                String url = manifest.getString("apkUrl");
                if (!url.startsWith("https://github.com/AbhayShah06/FlashBridge/releases/download/")) throw new SecurityException("Invalid update address");
                File folder = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (folder == null) throw new Exception("Update folder unavailable");
                temporary = new File(folder, "flashbridge-update.part");
                File apk = new File(folder, "flashbridge-update.apk");
                download(url, temporary);
                if (!sha256(temporary).equalsIgnoreCase(manifest.getString("apkSha256"))) throw new SecurityException("Update checksum mismatch");
                if (apk.exists() && !apk.delete()) throw new Exception("Could not replace old update file");
                if (!temporary.renameTo(apk)) throw new Exception("Could not prepare update");
                activity.runOnUiThread(() -> {
                    Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
                    activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (button != null) { button.setEnabled(true); button.setText("Retry update"); }
                    Toast.makeText(activity, "Update failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally { if (temporary != null) temporary.delete(); }
        }, "flashbridge-update-download").start();
    }

    private static String read(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setUseCaches(false);
        try (InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static void download(String url, File file) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
            byte[] b = new byte[256 * 1024]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
            out.getFD().sync();
        } finally { c.disconnect(); }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[256 * 1024]; int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }
}
