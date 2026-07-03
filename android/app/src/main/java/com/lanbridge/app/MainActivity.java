package com.lanbridge.app;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int HTTP_PORT = 4317;
    private static final int DISCOVERY_PORT = 4318;
    private static final int BLUE = Color.rgb(23, 105, 224);
    private static final int TEAL = Color.rgb(13, 148, 136);
    private static final int CORAL = Color.rgb(226, 85, 68);
    private static final int AMBER = Color.rgb(216, 146, 22);
    private static final int INK = Color.rgb(23, 32, 42);
    private static final int MUTED = Color.rgb(102, 112, 133);
    private static final int PAPER = Color.rgb(247, 249, 252);

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Map<String, Peer> peers = new HashMap<>();
    private final String deviceId = UUID.randomUUID().toString();
    private final File storageRoot = Environment.getExternalStorageDirectory();
    private String deviceName;
    private Peer activePeer;
    private String activePath = "";
    private String activeSearch = "";
    private WifiManager.MulticastLock multicastLock;
    private LinearLayout peerList;
    private LinearLayout fileList;
    private LinearLayout breadcrumbs;
    private TextView title;
    private TextView localInfo;
    private TextView peerCount;
    private EditText searchInput;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        deviceName = Build.MANUFACTURER + " " + Build.MODEL;
        requestRuntimePermissions();
        acquireMulticastLock();
        setContentView(buildUi());
        startHttpServer();
        startDiscovery();
        refreshLocalInfo();
        renderPeers();
        renderEmpty("等待发现设备", "保持手机和电脑连接同一个局域网。");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (localInfo != null) refreshLocalInfo();
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception ignored) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 10);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 12);
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAPER);
        LinearLayout root = column();
        root.setPadding(dp(18), dp(18), dp(18), dp(26));
        scroll.addView(root);

        LinearLayout brand = row();
        brand.addView(badge("L", BLUE, 52));
        LinearLayout brandText = column();
        brandText.addView(text("LanFileBridge", 26, INK, true));
        brandText.addView(text("安卓端局域网文件互传", 14, MUTED, false));
        brand.addView(brandText);
        root.addView(brand);

        LinearLayout hero = panel();
        hero.addView(text("照片和视频可预览，点开即可播放。", 25, INK, true));
        TextView intro = text("授权所有文件访问后，电脑可以浏览手机共享存储中的可访问文件。", 15, MUTED, false);
        intro.setPadding(0, dp(8), 0, 0);
        hero.addView(intro);
        root.addView(hero);

        LinearLayout local = panel();
        local.addView(sectionTitle("本机", "在线"));
        localInfo = text("", 14, MUTED, false);
        localInfo.setPadding(0, dp(8), 0, dp(12));
        local.addView(localInfo);
        Button permission = primaryButton("打开所有文件访问权限");
        permission.setOnClickListener(v -> requestRuntimePermissions());
        local.addView(permission);
        root.addView(local);

        LinearLayout devices = panel();
        LinearLayout deviceTitle = rowBetween();
        deviceTitle.addView(text("在线设备", 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        peerCount = text("0", 14, TEAL, true);
        deviceTitle.addView(peerCount);
        devices.addView(deviceTitle);
        peerList = column();
        peerList.setPadding(0, dp(10), 0, 0);
        devices.addView(peerList);
        root.addView(devices);

        LinearLayout browser = panel();
        LinearLayout top = rowBetween();
        title = text("文件浏览", 22, INK, true);
        Button refresh = secondaryButton("刷新");
        refresh.setOnClickListener(v -> loadActiveFiles());
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(refresh);
        browser.addView(top);

        LinearLayout searchRow = row();
        searchRow.setPadding(0, dp(12), 0, 0);
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Search files on selected device");
        searchInput.setTextColor(INK);
        searchInput.setHintTextColor(MUTED);
        searchInput.setBackground(makeBg(Color.WHITE, dp(8), Color.rgb(223, 229, 236)));
        searchInput.setPadding(dp(12), 0, dp(12), 0);
        Button search = primaryButton("Search");
        Button clear = secondaryButton("Clear");
        search.setOnClickListener(v -> runSearch());
        clear.setOnClickListener(v -> clearSearch());
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams searchButtonParams = new LinearLayout.LayoutParams(-2, dp(46));
        searchButtonParams.setMargins(dp(8), 0, 0, 0);
        searchRow.addView(search, searchButtonParams);
        LinearLayout.LayoutParams clearButtonParams = new LinearLayout.LayoutParams(-2, dp(46));
        clearButtonParams.setMargins(dp(8), 0, 0, 0);
        searchRow.addView(clear, clearButtonParams);
        browser.addView(searchRow);

        HorizontalScrollView crumbScroll = new HorizontalScrollView(this);
        breadcrumbs = row();
        breadcrumbs.setPadding(0, dp(12), 0, dp(8));
        crumbScroll.addView(breadcrumbs);
        browser.addView(crumbScroll);

        fileList = column();
        browser.addView(fileList);
        root.addView(browser);
        return scroll;
    }

    private LinearLayout panel() {
        LinearLayout view = column();
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        view.setBackground(makeBg(Color.WHITE, dp(8), Color.rgb(223, 229, 236)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(16), 0, 0);
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout rowBetween() {
        LinearLayout view = row();
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView badge(String value, int color, int sizeDp) {
        TextView view = text(value, 15, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(makeBg(color, dp(8), color));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        params.setMargins(0, 0, dp(12), 0);
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout sectionTitle(String left, String right) {
        LinearLayout row = rowBetween();
        row.addView(text(left, 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text(right, 13, TEAL, true));
        return row;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(makeBg(BLUE, dp(8), BLUE));
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = primaryButton(value);
        button.setTextColor(INK);
        button.setBackground(makeBg(Color.rgb(239, 244, 251), dp(8), Color.rgb(223, 229, 236)));
        return button;
    }

    private android.graphics.drawable.Drawable makeBg(int color, int radius, int stroke) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        d.setStroke(1, stroke);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("LanFileBridgeDiscovery");
        multicastLock.setReferenceCounted(true);
        multicastLock.acquire();
    }

    private void refreshLocalInfo() {
        String permission = hasAllFilesAccess() ? "所有文件访问已授权" : "请授权所有文件访问";
        localInfo.setText(deviceName + "\n地址: http://" + localIp() + ":" + HTTP_PORT + "\n共享范围: " + storageRoot.getAbsolutePath() + "\n" + permission);
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private void startDiscovery() {
        io.execute(() -> {
            try (DatagramSocket socket = new DatagramSocket(DISCOVERY_PORT)) {
                socket.setBroadcast(true);
                byte[] buffer = new byte[4096];
                io.execute(() -> broadcastLoop(socket));
                while (!Thread.currentThread().isInterrupted()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    JSONObject json = new JSONObject(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                    if (!"LanFileBridge".equals(json.optString("app"))) continue;
                    if (deviceId.equals(json.optString("id"))) continue;
                    Peer peer = new Peer();
                    peer.id = json.optString("id");
                    peer.name = json.optString("name", "Unknown device");
                    peer.type = json.optString("type", "device");
                    peer.host = packet.getAddress().getHostAddress();
                    peer.port = json.optInt("port", HTTP_PORT);
                    peer.url = "http://" + peer.host + ":" + peer.port;
                    peer.seenAt = System.currentTimeMillis();
                    peers.put(peer.id, peer);
                    if (!"reply".equals(json.optString("kind"))) sendAnnouncement(socket, packet.getAddress(), "reply");
                    runOnUiThread(this::renderPeers);
                }
            } catch (Exception e) {
                runOnUiThread(() -> toast("发现服务异常: " + e.getMessage()));
            }
        });
    }

    private void broadcastLoop(DatagramSocket socket) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                for (InetAddress address : broadcastTargets()) sendAnnouncement(socket, address, "announce");
                Thread.sleep(3000);
            } catch (Exception ignored) {
            }
        }
    }

    private void sendAnnouncement(DatagramSocket socket, InetAddress address, String kind) throws Exception {
        JSONObject json = new JSONObject();
        json.put("app", "LanFileBridge");
        json.put("version", 2);
        json.put("kind", kind);
        json.put("id", deviceId);
        json.put("name", deviceName);
        json.put("type", "android");
        json.put("port", HTTP_PORT);
        json.put("url", "http://" + localIp() + ":" + HTTP_PORT);
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(body, body.length, address, DISCOVERY_PORT));
    }

    private List<InetAddress> broadcastTargets() {
        List<InetAddress> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            InetAddress global = InetAddress.getByName("255.255.255.255");
            result.add(global);
            seen.add(global.getHostAddress());
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                for (java.net.InterfaceAddress item : iface.getInterfaceAddresses()) {
                    InetAddress broadcast = item.getBroadcast();
                    if (broadcast != null && seen.add(broadcast.getHostAddress())) result.add(broadcast);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void startHttpServer() {
        io.execute(() -> {
            try (ServerSocket server = new ServerSocket(HTTP_PORT)) {
                while (!Thread.currentThread().isInterrupted()) {
                    Socket socket = server.accept();
                    io.execute(() -> handleSocket(socket));
                }
            } catch (Exception e) {
                runOnUiThread(() -> toast("文件服务异常: " + e.getMessage()));
            }
        });
    }

    private void handleSocket(Socket socket) {
        try (Socket s = socket) {
            InputStream input = new BufferedInputStream(s.getInputStream());
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            int b;
            while ((b = input.read()) != -1) {
                head.write(b);
                matched = (matched == 0 && b == '\r') || (matched == 1 && b == '\n') || (matched == 2 && b == '\r') || (matched == 3 && b == '\n') ? matched + 1 : 0;
                if (matched == 4) break;
            }
            String[] parts = head.toString("UTF-8").split("\r\n")[0].split(" ");
            if (parts.length < 2) {
                writeText(s, 400, "Bad request");
                return;
            }
            URLParts url = URLParts.parse(parts[1]);
            try {
                if ("/api/me".equals(url.path)) writeJson(s, 200, meJson());
                else if ("/api/files".equals(url.path)) writeJson(s, 200, filesJson(url.query.get("path")));
                else if ("/api/search".equals(url.path)) writeJson(s, 200, searchJson(url.query.get("q"), url.query.get("path")));
                else if ("/api/download".equals(url.path)) writeFile(s, url.query.get("path"), true, headerValue(head.toString("UTF-8"), "Range"));
                else if ("/api/preview".equals(url.path)) writeFile(s, url.query.get("path"), false, headerValue(head.toString("UTF-8"), "Range"));
                else if ("/api/ping".equals(url.path)) writeJson(s, 200, new JSONObject().put("ok", true));
                else writeJson(s, 404, new JSONObject().put("error", "Not found"));
            } catch (Exception e) {
                writeJson(s, 500, new JSONObject().put("error", e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        } catch (Exception ignored) {
        }
    }

    private String headerValue(String request, String name) {
        for (String line : request.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private JSONObject meJson() throws Exception {
        return new JSONObject()
            .put("id", deviceId)
            .put("name", deviceName)
            .put("type", "android")
            .put("port", HTTP_PORT)
            .put("url", "http://" + localIp() + ":" + HTTP_PORT)
            .put("shareRoot", storageRoot.getAbsolutePath());
    }

    private JSONObject filesJson(String rel) throws Exception {
        if (!hasAllFilesAccess()) throw new IllegalArgumentException("请先在安卓端授权所有文件访问权限。");
        File folder = safeFile(rel == null || rel.isEmpty() ? storageRoot.getAbsolutePath() : rel);
        if (!folder.isDirectory()) throw new IllegalArgumentException("不是文件夹。");
        JSONArray arr = new JSONArray();
        File[] files = folder.listFiles();
        List<File> sorted = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                if (file.canRead()) sorted.add(file);
            }
        }
        sorted.sort(Comparator.comparing(File::isFile).thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        for (File file : sorted) arr.put(fileJson(file));
        return new JSONObject().put("path", folder.getAbsolutePath()).put("entries", arr);
    }

    private JSONObject searchJson(String query, String rel) throws Exception {
        if (!hasAllFilesAccess()) throw new IllegalArgumentException("请先在安卓端授权所有文件访问权限。");
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        JSONArray arr = new JSONArray();
        if (needle.isEmpty()) return new JSONObject().put("query", "").put("entries", arr);
        File root = safeFile(rel == null || rel.isEmpty() ? storageRoot.getAbsolutePath() : rel);
        List<File> stack = new ArrayList<>();
        stack.add(root);
        int visited = 0;
        int maxVisited = 50000;
        int maxResults = 250;
        while (!stack.isEmpty() && arr.length() < maxResults && visited < maxVisited) {
            File current = stack.remove(stack.size() - 1);
            visited += 1;
            if (!current.canRead()) continue;
            if (current.getName().toLowerCase(Locale.ROOT).contains(needle)) {
                arr.put(fileJson(current));
            }
            if (!current.isDirectory()) continue;
            File[] children = current.listFiles();
            if (children == null) continue;
            for (int i = children.length - 1; i >= 0; i--) {
                stack.add(children[i]);
            }
        }
        return new JSONObject()
            .put("query", query == null ? "" : query)
            .put("path", root.getAbsolutePath())
            .put("truncated", !stack.isEmpty())
            .put("entries", arr);
    }

    private JSONObject fileJson(File file) throws Exception {
        String extension = ext(file.getName());
        return new JSONObject()
            .put("name", file.getName())
            .put("path", file.getAbsolutePath())
            .put("type", file.isDirectory() ? "folder" : "file")
            .put("size", file.isFile() ? file.length() : 0)
            .put("modified", file.lastModified())
            .put("ext", extension)
            .put("media", mediaType(extension));
    }

    private File safeFile(String value) throws Exception {
        File file = new File(value).getCanonicalFile();
        File root = storageRoot.getCanonicalFile();
        if (!file.getPath().equals(root.getPath()) && !file.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IllegalArgumentException("只能访问共享存储中的文件。");
        }
        return file;
    }

    private void writeFile(Socket s, String rel, boolean attachment, String range) throws Exception {
        if (!hasAllFilesAccess()) throw new IllegalArgumentException("请先在安卓端授权所有文件访问权限。");
        File file = safeFile(rel == null ? "" : rel);
        if (!file.isFile()) {
            writeText(s, 404, "Not found");
            return;
        }
        String type = URLConnection.guessContentTypeFromName(file.getName());
        if (type == null) type = "application/octet-stream";
        long length = file.length();
        long start = 0;
        long end = length - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String[] pieces = range.substring(6).split("-", 2);
            if (pieces.length > 0 && pieces[0].length() > 0) start = Long.parseLong(pieces[0]);
            if (pieces.length > 1 && pieces[1].length() > 0) end = Long.parseLong(pieces[1]);
            if (start <= end && end < length) partial = true;
        }
        String filename = URLEncoder.encode(file.getName(), "UTF-8");
        String status = partial ? "206 Partial Content" : "200 OK";
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(status).append("\r\n");
        header.append("Content-Type: ").append(type).append("\r\n");
        header.append("Accept-Ranges: bytes\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        header.append("Content-Disposition: ").append(attachment ? "attachment" : "inline").append("; filename*=UTF-8''").append(filename).append("\r\n");
        if (partial) header.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(length).append("\r\n");
        header.append("Content-Length: ").append(end - start + 1).append("\r\n\r\n");
        OutputStream out = s.getOutputStream();
        out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        try (FileInputStream in = new FileInputStream(file)) {
            if (start > 0) in.skip(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = end - start + 1;
            int n;
            while (remaining > 0 && (n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }
        out.flush();
    }

    private void writeJson(Socket s, int status, JSONObject json) throws Exception {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + body.length + "\r\nAccess-Control-Allow-Origin: *\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void writeText(Socket s, int status, String value) throws Exception {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " Error\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void renderPeers() {
        long cutoff = System.currentTimeMillis() - 15000;
        List<Peer> visible = new ArrayList<>();
        for (Peer peer : peers.values()) if (peer.seenAt >= cutoff) visible.add(peer);
        peerCount.setText(String.valueOf(visible.size()));
        peerList.removeAllViews();
        if (visible.isEmpty()) {
            peerList.addView(text("暂未发现设备，保持双方在同一 Wi-Fi。", 14, MUTED, false));
            return;
        }
        for (Peer peer : visible) {
            Button button = secondaryButton((peer.type.equals("pc") ? "PC  " : "APP  ") + peer.name + "\n" + peer.host + ":" + peer.port);
            button.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, 0, 0, dp(8));
            button.setLayoutParams(params);
            button.setOnClickListener(v -> {
                activePeer = peer;
                activePath = "";
                activeSearch = "";
                if (searchInput != null) searchInput.setText("");
                loadActiveFiles();
            });
            peerList.addView(button);
        }
    }

    private void loadActiveFiles() {
        if (activePeer == null) {
            renderEmpty("选择一台设备", "发现 PC 或另一台手机后，点设备卡片即可浏览文件。");
            return;
        }
        title.setText(activeSearch.isEmpty() ? (activePath.isEmpty() ? activePeer.name : new File(activePath).getName()) : "Search results");
        renderBreadcrumbs();
        io.execute(() -> {
            try {
                String url = activeSearch.isEmpty()
                    ? activePeer.url + "/api/files?path=" + URLEncoder.encode(activePath, "UTF-8")
                    : activePeer.url + "/api/search?q=" + URLEncoder.encode(activeSearch, "UTF-8") + "&path=" + URLEncoder.encode(activePath, "UTF-8");
                JSONObject json = fetchJson(url);
                JSONArray entries = json.getJSONArray("entries");
                boolean truncated = json.optBoolean("truncated", false);
                runOnUiThread(() -> {
                    renderFiles(entries);
                    if (truncated) toast("Search stopped after the result limit.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> renderEmpty("无法读取文件", e.getMessage()));
            }
        });
    }

    private void runSearch() {
        if (activePeer == null) {
            toast("Choose a device first.");
            return;
        }
        String query = searchInput == null ? "" : searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            clearSearch();
            return;
        }
        activeSearch = query;
        loadActiveFiles();
    }

    private void clearSearch() {
        activeSearch = "";
        if (searchInput != null) searchInput.setText("");
        loadActiveFiles();
    }

    private JSONObject fetchJson(String spec) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(spec).openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(8000);
        InputStream input = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        try (InputStream in = input) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            if (conn.getResponseCode() >= 400) throw new IllegalArgumentException(json.optString("error", conn.getResponseMessage()));
            return json;
        }
    }

    private void renderBreadcrumbs() {
        breadcrumbs.removeAllViews();
        if (activePeer == null) return;
        Button root = secondaryButton(activePeer.name);
        root.setOnClickListener(v -> {
            activeSearch = "";
            if (searchInput != null) searchInput.setText("");
            activePath = "";
            loadActiveFiles();
        });
        breadcrumbs.addView(root);
        if (!activeSearch.isEmpty()) {
            Button search = secondaryButton("Search: " + activeSearch);
            search.setOnClickListener(v -> clearSearch());
            breadcrumbs.addView(search);
        } else if (!activePath.isEmpty()) {
            File current = new File(activePath);
            List<File> stack = new ArrayList<>();
            while (current != null) {
                stack.add(0, current);
                current = current.getParentFile();
            }
            for (File item : stack) {
                Button crumb = secondaryButton(item.getName().isEmpty() ? item.getPath() : item.getName());
                String target = item.getPath();
                crumb.setOnClickListener(v -> {
                    activePath = target;
                    loadActiveFiles();
                });
                breadcrumbs.addView(crumb);
            }
        }
    }

    private void renderFiles(JSONArray entries) {
        fileList.removeAllViews();
        if (entries.length() == 0) {
            renderEmpty("空文件夹", "这里没有可浏览的文件。");
            return;
        }
        for (int i = 0; i < entries.length(); i++) {
            try {
                JSONObject item = entries.getJSONObject(i);
                fileList.addView(fileCard(item));
            } catch (Exception ignored) {
            }
        }
    }

    private View fileCard(JSONObject item) throws Exception {
        LinearLayout card = row();
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(makeBg(Color.WHITE, dp(8), Color.rgb(223, 229, 236)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(params);

        String type = item.getString("type");
        String ext = item.optString("ext", "");
        String media = item.optString("media", "");
        FrameLayout preview = previewBox(item, type, ext, media);
        LinearLayout meta = column();
        meta.addView(text(item.getString("name"), 16, INK, true));
        meta.addView(text(type.equals("folder") ? "文件夹" : fmtSize(item.optLong("size")), 13, MUTED, false));
        card.addView(preview);
        card.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(text(type.equals("folder") ? "打开" : (media.isEmpty() ? "提取" : "预览"), 14, BLUE, true));
        card.setOnClickListener(v -> {
            try {
                if (type.equals("folder")) {
                    activePath = item.getString("path");
                    activeSearch = "";
                    if (searchInput != null) searchInput.setText("");
                    loadActiveFiles();
                } else if (!media.isEmpty()) {
                    openViewer(item);
                } else {
                    download(item.getString("path"), item.getString("name"));
                }
            } catch (Exception e) {
                toast(e.getMessage());
            }
        });
        return card;
    }

    private FrameLayout previewBox(JSONObject item, String type, String ext, String media) throws Exception {
        FrameLayout box = new FrameLayout(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(76), dp(58));
        params.setMargins(0, 0, dp(12), 0);
        box.setLayoutParams(params);
        box.setBackground(makeBg(type.equals("folder") ? AMBER : colorFor(type, ext), dp(8), Color.TRANSPARENT));
        if (media.isEmpty()) {
            TextView icon = text(type.equals("folder") ? "DIR" : (ext.isEmpty() ? "FILE" : ext.toUpperCase(Locale.ROOT)), 13, Color.WHITE, true);
            icon.setGravity(Gravity.CENTER);
            box.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        } else {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(image, new FrameLayout.LayoutParams(-1, -1));
            if ("video".equals(media)) {
                TextView play = text("▶", 22, Color.WHITE, true);
                play.setGravity(Gravity.CENTER);
                box.addView(play, new FrameLayout.LayoutParams(-1, -1));
            }
            loadThumbnail(image, item.getString("path"), media);
        }
        return box;
    }

    private void loadThumbnail(ImageView target, String path, String media) throws Exception {
        String spec = activePeer.url + "/api/preview?path=" + URLEncoder.encode(path, "UTF-8");
        io.execute(() -> {
            try {
                Bitmap bitmap;
                if ("video".equals(media)) {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(spec, new HashMap<String, String>());
                    bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    retriever.release();
                } else {
                    HttpURLConnection conn = (HttpURLConnection) new URL(spec).openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(8000);
                    try (InputStream in = conn.getInputStream()) {
                        bitmap = BitmapFactory.decodeStream(in);
                    }
                }
                if (bitmap != null) runOnUiThread(() -> target.setImageBitmap(bitmap));
            } catch (Exception ignored) {
            }
        });
    }

    private void openViewer(JSONObject item) throws Exception {
        String media = item.optString("media", "");
        String name = item.getString("name");
        String path = item.getString("path");
        String src = activePeer.url + "/api/preview?path=" + URLEncoder.encode(path, "UTF-8");
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column();
        root.setBackgroundColor(Color.rgb(12, 18, 28));
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout bar = rowBetween();
        bar.addView(text(name, 16, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button save = primaryButton("下载");
        save.setOnClickListener(v -> {
            try {
                download(path, name);
            } catch (Exception e) {
                toast(e.getMessage());
            }
        });
        bar.addView(save);
        root.addView(bar);
        if ("video".equals(media)) {
            VideoView video = new VideoView(this);
            video.setVideoURI(Uri.parse(src));
            video.setMediaController(new MediaController(this));
            video.setOnPreparedListener(mp -> video.start());
            root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
            io.execute(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(src).openConnection();
                    try (InputStream in = conn.getInputStream()) {
                        Bitmap bitmap = BitmapFactory.decodeStream(in);
                        runOnUiThread(() -> image.setImageBitmap(bitmap));
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> toast(e.getMessage()));
                }
            });
        }
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        dialog.setOnShowListener(d -> {
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            }
        });
        dialog.show();
    }

    private int colorFor(String type, String ext) {
        if ("folder".equals(type)) return AMBER;
        if (mediaType(ext).equals("image")) return TEAL;
        if (mediaType(ext).equals("video")) return BLUE;
        if (ext.matches("doc|docx|xls|xlsx|ppt|pptx|pdf|txt|md")) return CORAL;
        return BLUE;
    }

    private void renderEmpty(String a, String b) {
        fileList.removeAllViews();
        TextView icon = badge("~", CORAL, 58);
        TextView heading = text(a, 18, INK, true);
        TextView msg = text(b, 14, MUTED, false);
        LinearLayout empty = column();
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(0, dp(28), 0, dp(28));
        empty.addView(icon);
        empty.addView(heading);
        empty.addView(msg);
        fileList.addView(empty);
    }

    private void download(String path, String name) throws Exception {
        String url = activePeer.url + "/api/download?path=" + URLEncoder.encode(path, "UTF-8");
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle(name);
        request.setDescription("LanFileBridge");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "LanFileBridge/" + name);
        ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
        toast("已开始提取到 Downloads/LanFileBridge");
    }

    private String localIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') < 0) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private String fmtSize(long size) {
        if (size <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = Math.min((int) (Math.log(size) / Math.log(1024)), units.length - 1);
        return String.format(Locale.ROOT, index == 0 ? "%.0f %s" : "%.1f %s", size / Math.pow(1024, index), units[index]);
    }

    private String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String mediaType(String ext) {
        if (ext.matches("jpg|jpeg|png|gif|webp")) return "image";
        if (ext.matches("mp4|mov|m4v|webm|mkv|avi|3gp")) return "video";
        return "";
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        io.shutdownNow();
    }

    private static class Peer {
        String id;
        String name;
        String type;
        String host;
        int port;
        String url;
        long seenAt;
    }

    private static class URLParts {
        String path;
        Map<String, String> query = new HashMap<>();

        static URLParts parse(String raw) throws Exception {
            URLParts parts = new URLParts();
            String[] split = raw.split("\\?", 2);
            parts.path = split[0];
            if (split.length > 1) {
                for (String pair : split[1].split("&")) {
                    if (pair.isEmpty()) continue;
                    String[] kv = pair.split("=", 2);
                    String key = URLDecoder.decode(kv[0], "UTF-8");
                    String value = kv.length > 1 ? URLDecoder.decode(kv[1], "UTF-8") : "";
                    parts.query.put(key, value);
                }
            }
            return parts;
        }
    }
}
