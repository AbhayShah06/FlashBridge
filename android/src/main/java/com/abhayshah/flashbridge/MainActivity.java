package com.abhayshah.flashbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.content.ContentResolver;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.ViewGroup;
import android.view.Gravity;
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
import java.util.LinkedHashSet;
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
        body.setPadding(dp(20), dp(28), dp(20), dp(28));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(17, 18, 20));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
            }
            return insets;
        });
        scroll.addView(body);
        setContentView(scroll);
        TextView kicker = label("LOCAL TRANSFER", 12);
        kicker.setTextColor(Color.rgb(190, 242, 100));
        kicker.setTypeface(null, Typeface.BOLD);
        kicker.setLetterSpacing(.16f);
        body.addView(kicker);
        TextView title = label("FlashBridge", 34);
        title.setTextColor(Color.rgb(244, 245, 242));
        title.setTypeface(null, Typeface.BOLD);
        body.addView(title);
        TextView subtitle = label("Your files, straight between devices.", 15);
        subtitle.setTextColor(Color.rgb(167, 171, 176));
        body.addView(subtitle);

        LinearLayout connectionCard = card(body);
        connectionCard.addView(cardTitle("CONNECTION"));
        hostLabel = label("", 16);
        connectionCard.addView(hostLabel);
        button("Pair with Windows", connectionCard, false, v -> showPair());

        LinearLayout filesCard = card(body);
        filesCard.addView(cardTitle("FILES"));
        folderLabel = label("", 14);
        folderLabel.setTextColor(Color.rgb(167, 171, 176));
        filesCard.addView(folderLabel);
        button("Choose receive folder", filesCard, false, v -> chooseFolder());
        send = button("Send files to PC", filesCard, true, v -> chooseFile());
        receive = button("Get files from PC", filesCard, false, v -> listRemote());

        LinearLayout progressCard = card(body);
        progressCard.addView(cardTitle("TRANSFER"));
        status = label("Open FlashBridge on Windows. Connect both devices to the same network, or connect the PC to this phone's hotspot.", 15);
        progressCard.addView(status);
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
        view.setTextColor(Color.rgb(244, 245, 242));
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout card(LinearLayout body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(29, 31, 34));
        background.setCornerRadius(dp(20));
        background.setStroke(dp(1), Color.rgb(51, 54, 58));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(20);
        body.addView(card, params);
        return card;
    }

    private TextView cardTitle(String text) {
        TextView view = label(text, 11);
        view.setTextColor(Color.rgb(190, 242, 100));
        view.setTypeface(null, Typeface.BOLD);
        view.setLetterSpacing(.13f);
        return view;
    }

    private Button button(String text, LinearLayout parent, boolean primary, View.OnClickListener click) {
        Button result = new Button(this);
        result.setText(text);
        result.setAllCaps(false);
        result.setTextSize(15);
        result.setTypeface(null, Typeface.BOLD);
        result.setTextColor(primary ? Color.rgb(17, 18, 20) : Color.rgb(244, 245, 242));
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? Color.rgb(190, 242, 100) : Color.rgb(43, 46, 49));
        background.setCornerRadius(dp(14));
        if (!primary) background.setStroke(dp(1), Color.rgb(64, 68, 71));
        result.setBackground(background);
        result.setElevation(0);
        result.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.topMargin = dp(10);
        result.setOnClickListener(click);
        parent.addView(result, params);
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
                    prefs.edit().putString("discovered_host", host).apply();
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
            int responseCode = c.getResponseCode();
            if (responseCode != 200) throw new Exception(FriendlyErrors.forPairCode(responseCode));
            JSONObject response = new JSONObject(readAll(c.getInputStream()));
            String token = response.getString("token");
            prefs.edit().putString("host", host).putString("token", token).apply();
            setStatus("Paired. Choose a receive folder, then send or get files.");
            runOnUiThread(this::refresh);
        } catch (Exception e) { setStatus(FriendlyErrors.from(e)); }
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
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, CHOOSE_FILE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == CHOOSE_FOLDER) {
            Uri uri = data.getData();
            if (uri == null) return;
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs.edit().putString("folder", uri.toString()).apply();
            refresh();
        } else if (request == CHOOSE_FILE) {
            LinkedHashSet<Uri> selected = new LinkedHashSet<>();
            ClipData clips = data.getClipData();
            if (clips != null) for (int i = 0; i < clips.getItemCount(); i++) selected.add(clips.getItemAt(i).getUri());
            if (data.getData() != null) selected.add(data.getData());
            if (!selected.isEmpty()) startSend(new ArrayList<>(selected));
        }
    }

    private void startSend(ArrayList<Uri> uris) {
        for (Uri uri : uris) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) {}
        }
        Intent task = new Intent(this, TransferService.class).setAction(TransferService.SEND).putParcelableArrayListExtra("uris", uris);
        startForegroundService(task);
    }

    private void startReceive(ArrayList<String> names) {
        Intent task = new Intent(this, TransferService.class).setAction(TransferService.RECEIVE).putStringArrayListExtra("names", names);
        startForegroundService(task);
    }
    private void listRemote() {
        io.execute(() -> {
            try {
                JSONArray items = null;
                LinkedHashSet<String> candidates = new LinkedHashSet<>();
                candidates.add(prefs.getString("discovered_host", ""));
                candidates.add(prefs.getString("host", ""));
                boolean unauthorized = false;
                boolean unavailableFolder = false;
                for (String host : candidates) {
                    if (host.isEmpty()) continue;
                    HttpURLConnection c = null;
                    try {
                        c = connection(host, "/v1/files", "GET", true);
                        c.setConnectTimeout(3000);
                        c.setReadTimeout(5000);
                        int code = c.getResponseCode();
                        if (code == 401) { unauthorized = true; continue; }
                        if (code == 503) { unavailableFolder = true; continue; }
                        if (code != 200) continue;
                        items = new JSONObject(readAll(c.getInputStream())).getJSONArray("files");
                        prefs.edit().putString("host", host).apply();
                        break;
                    } catch (java.io.IOException ignored) { /* Try the other known PC address. */ }
                    finally { if (c != null) c.disconnect(); }
                }
                if (items == null) throw new Exception(unavailableFolder ? FriendlyErrors.FOLDER : unauthorized
                        ? "Pairing expired. Pair with Windows again." : FriendlyErrors.OFFLINE);
                List<String> names = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) names.add(items.getJSONObject(i).getString("name"));
                runOnUiThread(() -> {
                    if (names.isEmpty()) { setStatus("No files in the PC transfer folder yet."); return; }
                    boolean[] checked = new boolean[names.size()];
                    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Get from PC")
                            .setMultiChoiceItems(names.toArray(new String[0]), checked, (whichDialog, index, selected) -> checked[index] = selected)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Get selected", (whichDialog, which) -> {
                                ArrayList<String> selected = new ArrayList<>();
                                for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(names.get(i));
                                if (!selected.isEmpty()) startReceive(selected);
                            }).show();
                    installSwipeBack(dialog.findViewById(android.R.id.content), dialog::dismiss);
                });
            } catch (Exception e) { setStatus(FriendlyErrors.from(e)); }
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
        if (prefs != null && hostLabel != null) refresh();
        UpdateManager.onResume(this);
    }

    @Override protected void onDestroy() {
        running = false;
        mainHandler.removeCallbacks(statusPoll);
        io.shutdownNow();
        super.onDestroy();
    }
}
