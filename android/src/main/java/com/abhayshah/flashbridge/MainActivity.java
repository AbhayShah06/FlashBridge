package com.abhayshah.flashbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.content.ContentResolver;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            String value = prefs.getString("status", "");
            if (!value.isEmpty() && !value.equals(status.getText().toString())) status.setText(value);
            mainHandler.postDelayed(this, 500);
        }
    };

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
        UpdateManager.check(this, body);
        mainHandler.post(statusPoll);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 90);
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

    private void setStatus(String message) {
        prefs.edit().putString("status", message).apply();
        runOnUiThread(() -> status.setText(message));
    }

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
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Pair with Windows")
                .setMessage("Both devices must be on the same network. A phone hotspot works without internet.")
                .setView(form).setNegativeButton("Cancel", null)
                .setPositiveButton("Pair", (whichDialog, which) -> {
                    String host = address.getText().toString().trim();
                    String code = pin.getText().toString().trim();
                    if (!host.matches("[0-9a-zA-Z.:-]{2,255}") || !code.matches("[0-9]{6}")) {
                        setStatus("Enter the PC address and six-digit code shown in Windows."); return;
                    }
                    io.execute(() -> pair(host, code));
                }).show();
        installSwipeBack(dialog.findViewById(android.R.id.content), dialog::dismiss);
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
        } else if (request == CHOOSE_FILE) startSend(uri);
    }

    private void startSend(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) {}
        Intent task = new Intent(this, TransferService.class).setAction(TransferService.SEND).setData(uri);
        startForegroundService(task);
    }

    private void startReceive(String name) {
        Intent task = new Intent(this, TransferService.class).setAction(TransferService.RECEIVE).putExtra("name", name);
        startForegroundService(task);
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
                    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Get from PC")
                            .setItems(names.toArray(new String[0]), (whichDialog, index) -> startReceive(names.get(index)))
                            .setNegativeButton("Cancel", null).show();
                    installSwipeBack(dialog.findViewById(android.R.id.content), dialog::dismiss);
                });
            } catch (Exception e) { setStatus("Could not list PC files: " + e.getMessage()); }
            finally { if (c != null) c.disconnect(); }
        });
    }

    private void installSwipeBack(View view, Runnable dismiss) {
        if (view == null || !(view.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) view.getParent();
        int index = parent.indexOfChild(view);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        parent.removeView(view);
        SwipeDismissLayout swipe = new SwipeDismissLayout(this, dismiss);
        swipe.addView(view, new FrameLayout.LayoutParams(-1, -1));
        parent.addView(swipe, index, params);
    }

    @Override protected void onResume() {
        super.onResume();
        UpdateManager.onResume(this);
    }

    @Override protected void onDestroy() {
        running = false;
        mainHandler.removeCallbacks(statusPoll);
        io.shutdownNow();
        super.onDestroy();
    }
}
