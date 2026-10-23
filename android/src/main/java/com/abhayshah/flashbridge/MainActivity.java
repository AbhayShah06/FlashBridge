package com.abhayshah.flashbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.content.ContentResolver;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.documentfile.provider.DocumentFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int CHOOSE_FOLDER = 1;
    private static final int CHOOSE_FILE = 2;
    private static final int PORT = 48888;
    private static final int DISCOVERY_PORT = 48889;
    private static final String MAGIC = "FLASHBRIDGE_DISCOVER_V1|";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private TextView status, hostLabel, folderLabel;
    private Button send, receive;
    private volatile boolean running = true;
    private volatile String discoveredHost = "";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("flashbridge", MODE_PRIVATE);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(24, 32, 24, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        setContentView(scroll);
        TextView title = label("FlashBridge", 28);
        body.addView(title);
        body.addView(label("Fast local transfer · Android ↔ Windows", 15));
        hostLabel = label("", 16);
        body.addView(hostLabel);
        folderLabel = label("", 14);
        body.addView(folderLabel);
        Button folder = button("Choose receive folder", body, v -> chooseFolder());
        Button pair = button("Pair with Windows", body, v -> showPair());
        send = button("Send file to PC", body, v -> chooseFile());
        receive = button("Get file from PC", body, v -> listRemote());
        status = label("Open FlashBridge on Windows. Connect both devices to the same network, or connect the PC to this phone's hotspot.", 15);
        body.addView(status);
        refresh();
        startDiscovery();
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setPadding(0, 8, 0, 8);
        return view;
    }

    private Button button(String text, LinearLayout parent, View.OnClickListener click) {
        Button result = new Button(this);
        result.setText(text);
        result.setOnClickListener(click);
        parent.addView(result);
        return result;
    }

    private void refresh() {
        String host = prefs.getString("host", "");
        hostLabel.setText(host.isEmpty() ? "Looking for your Windows PC…" : "PC: " + host + (prefs.getString("token", "").isEmpty() ? " · not paired" : " · paired"));
        Uri uri = folderUri();
        folderLabel.setText(uri == null ? "Receive folder: not selected" : "Receive folder: " + DocumentFile.fromTreeUri(this, uri).getName());
        send.setEnabled(!prefs.getString("token", "").isEmpty());
        receive.setEnabled(send.isEnabled() && uri != null);
    }

    private Uri folderUri() {
        String value = prefs.getString("folder", "");
        return value.isEmpty() ? null : Uri.parse(value);
    }

    private void setStatus(String message) { runOnUiThread(() -> status.setText(message)); }

    private void startDiscovery() {
        Thread thread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(DISCOVERY_PORT)) {
                socket.setSoTimeout(2500);
                byte[] data = new byte[256];
                while (running) {
                    DatagramPacket packet = new DatagramPacket(data, data.length);
                    try { socket.receive(packet); } catch (java.net.SocketTimeoutException timeout) { continue; }
                    String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII);
                    if (!text.equals(MAGIC + PORT)) continue;
                    String host = packet.getAddress().getHostAddress();
                    discoveredHost = host;
                    if (prefs.getString("token", "").isEmpty() || prefs.getString("host", "").isEmpty()) {
                        prefs.edit().putString("host", host).apply();
                        runOnUiThread(this::refresh);
                    }
                }
            } catch (Exception e) { setStatus("Automatic discovery unavailable. Use Pair with Windows and enter the PC address."); }
        }, "flashbridge-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    private void showPair() {
        LinearLayout form = new LinearLayout(this);
        form.setPadding(28, 0, 28, 0);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("PC address shown in Windows app");
        address.setText(discoveredHost.isEmpty() ? prefs.getString("host", "") : discoveredHost);
        form.addView(address);
        EditText pin = new EditText(this);
        pin.setHint("Six-digit pairing code");
        pin.setSingleLine(true);
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        form.addView(pin);
        new AlertDialog.Builder(this).setTitle("Pair with Windows")
                .setMessage("Both devices must be on the same network. A phone hotspot works without internet.")
                .setView(form).setNegativeButton("Cancel", null)
                .setPositiveButton("Pair", (dialog, which) -> {
                    String host = address.getText().toString().trim();
                    String code = pin.getText().toString().trim();
                    if (!host.matches("[0-9a-zA-Z.:-]{2,255}") || !code.matches("[0-9]{6}")) {
                        setStatus("Enter the PC address and six-digit code shown in Windows."); return;
                    }
                    io.execute(() -> pair(host, code));
                }).show();
    }

    private HttpURLConnection connection(String host, String path, String method, boolean authenticated) throws Exception {
        URL url = new URL("http://" + host + ":" + PORT + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(120000);
        c.setUseCaches(false);
        if (authenticated) c.setRequestProperty("X-FlashBridge-Token", prefs.getString("token", ""));
        return c;
    }

    private void pair(String host, String code) {
        HttpURLConnection c = null;
        try {
            c = connection(host, "/v1/pair", "POST", false);
            byte[] bytes = new JSONObject().put("pin", code).toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            if (c.getResponseCode() != 200) throw new Exception("Pairing failed. Check the code and PC address.");
            JSONObject response = new JSONObject(readAll(c.getInputStream()));
            String token = response.getString("token");
            prefs.edit().putString("host", host).putString("token", token).apply();
            setStatus("Paired. Choose a receive folder, then send or get files.");
            runOnUiThread(this::refresh);
        } catch (Exception e) { setStatus(e.getMessage()); }
        finally { if (c != null) c.disconnect(); }
    }

    private static String readAll(InputStream input) throws Exception {
        try (InputStream in = input; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, CHOOSE_FOLDER);
    }

    private void chooseFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, CHOOSE_FILE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == CHOOSE_FOLDER) {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs.edit().putString("folder", uri.toString()).apply();
            refresh();
        } else if (request == CHOOSE_FILE) io.execute(() -> upload(uri));
    }

    private void upload(Uri uri) {
        HttpURLConnection c = null;
        try {
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
            c = connection(prefs.getString("host", ""), "/v1/files?name=" + Uri.encode(name), "PUT", true);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(size);
            final long total = size;
            setStatus("Sending " + name + "… 0%");
            try (InputStream in = resolver.openInputStream(uri); OutputStream out = c.getOutputStream()) {
                if (in == null) throw new Exception("Cannot open file");
                byte[] buf = new byte[256 * 1024]; long done = 0; int n; int last = -1;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n); done += n;
                    int percent = total == 0 ? 100 : (int)(done * 100 / total);
                    if (percent != last) { last = percent; setStatus("Sending " + name + "… " + percent + "%"); }
                }
                if (done != size) throw new Exception("The source file changed during transfer");
            }
            if (c.getResponseCode() != 201) throw new Exception("PC rejected the file (HTTP " + c.getResponseCode() + ")");
            JSONObject result = new JSONObject(readAll(c.getInputStream()));
            setStatus("Sent " + result.getString("name") + " to the PC folder.");
        } catch (Exception e) { setStatus("Send failed: " + e.getMessage()); }
        finally { if (c != null) c.disconnect(); }
    }

    private void listRemote() {
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = connection(prefs.getString("host", ""), "/v1/files", "GET", true);
                if (c.getResponseCode() != 200) throw new Exception("PC did not respond (HTTP " + c.getResponseCode() + ")");
                JSONArray items = new JSONObject(readAll(c.getInputStream())).getJSONArray("files");
                List<String> names = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) names.add(items.getJSONObject(i).getString("name"));
                runOnUiThread(() -> {
                    if (names.isEmpty()) { setStatus("No files in the PC transfer folder yet."); return; }
                    new AlertDialog.Builder(this).setTitle("Get from PC")
                            .setItems(names.toArray(new String[0]), (dialog, index) -> io.execute(() -> download(names.get(index))))
                            .setNegativeButton("Cancel", null).show();
                });
            } catch (Exception e) { setStatus("Could not list PC files: " + e.getMessage()); }
            finally { if (c != null) c.disconnect(); }
        });
    }

    private void download(String name) {
        HttpURLConnection c = null;
        DocumentFile created = null;
        try {
            Uri tree = folderUri();
            if (tree == null) throw new Exception("Choose a receive folder first");
            DocumentFile folder = DocumentFile.fromTreeUri(this, tree);
            if (folder == null || !folder.canWrite()) throw new Exception("Receive folder is unavailable");
            c = connection(prefs.getString("host", ""), "/v1/files/" + Uri.encode(name), "GET", true);
            if (c.getResponseCode() != 200) throw new Exception("PC could not send file (HTTP " + c.getResponseCode() + ")");
            long size = c.getContentLengthLong();
            if (size < 0) throw new Exception("PC did not provide a file size");
            String target = name; int index = 2;
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            while (folder.findFile(target) != null) target = stem + " (" + index++ + ")" + ext;
            created = folder.createFile("application/octet-stream", target);
            if (created == null) throw new Exception("Could not create file in receive folder");
            setStatus("Receiving " + target + "… 0%");
            try (InputStream in = c.getInputStream(); OutputStream out = getContentResolver().openOutputStream(created.getUri(), "w")) {
                if (out == null) throw new Exception("Could not write file");
                byte[] buf = new byte[256 * 1024]; long done = 0; int n; int last = -1;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n); done += n;
                    int percent = size == 0 ? 100 : (int)(done * 100 / size);
                    if (percent != last) { last = percent; setStatus("Receiving " + target + "… " + percent + "%"); }
                }
                if (done != size) throw new Exception("Transfer interrupted after " + done + " of " + size + " bytes");
                out.flush();
            }
            setStatus("Received " + target + " in your Android folder.");
        } catch (Exception e) {
            if (created != null) created.delete();
            setStatus("Receive failed: " + e.getMessage());
        } finally { if (c != null) c.disconnect(); }
    }

    @Override protected void onDestroy() {
        running = false;
        io.shutdownNow();
        super.onDestroy();
    }
}
