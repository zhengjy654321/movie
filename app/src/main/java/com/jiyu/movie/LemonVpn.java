package com.jiyu.movie;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import kotlin.Unit;
import kotlinx.coroutines.*;
import com.github.kr328.clash.core.bridge.*;

public final class LemonVpn extends VpnService {
    static final String AUTO = "LEMON-AUTO";
    private static final String SELECT = "LEMON-SELECT";
    private static final String SOURCE = "subscription";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    static volatile String status = "等待连接";
    static volatile boolean connected;
    static volatile JSONArray nodes = new JSONArray();
    private static Bridge core;
    private boolean running;
    private boolean refreshing;
    private static ParcelFileDescriptor applicationArchive;
    private volatile boolean closing;

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("vpn","节点连接",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        startForeground(7,new Notification.Builder(this,"vpn").setSmallIcon(com.jiyu.movie.R.drawable.lemon)
            .setContentTitle("柠檬").setContentText("仅代理柠檬的网络连接").setContentIntent(open).build());
        worker.execute(() -> {
            if (closing) return;
            try {
                if (!running) startTunnel();
                if (!closing) refreshSubscription();
            } catch (Exception | LinkageError failure) {
                status = "连接失败，可在设置里重试";
                stopSelf();
            }
        });
        return START_NOT_STICKY;
    }

    private interface Operation { void start(CompletableDeferred<Unit> result); }
    private static void await(Operation action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        CompletableDeferred<Unit> result = CompletableDeferredKt.CompletableDeferred((Job)null);
        result.invokeOnCompletion(cause -> { error.set(cause); done.countDown(); return Unit.INSTANCE; });
        action.start(result);
        if (!done.await(90,TimeUnit.SECONDS) || error.get()!=null) throw new IOException("Configuration unavailable");
    }

    private void startTunnel() throws Exception {
        status = "正在连接节点";
        File home = new File(getFilesDir(),"network");
        Files.createDirectories(home.toPath());
        if (core == null) {
            applicationArchive = ParcelFileDescriptor.open(new File(getPackageCodePath()), ParcelFileDescriptor.MODE_READ_ONLY);
            core = new Bridge();
            core.nativeInit(home.getAbsolutePath(),"1.2.0",Build.VERSION.SDK_INT);
        }
        String url;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open("subscription.txt"),StandardCharsets.UTF_8))) {
            url = reader.readLine();
        }
        if (url == null || !url.startsWith("https://")) throw new IOException("Invalid subscription");
        String config = "mode: rule\nlog-level: silent\nipv6: true\nproxy-providers:\n  subscription:\n    type: http\n    url: "
            + "'" + url.replace("'", "''") + "'" + "\n    path: nodes.yaml\n    interval: 86400\n    health-check:\n      enable: true\n      lazy: false\n      url: https://www.gstatic.com/generate_204\n      interval: 300\n"
            + "proxy-groups:\n  - name: LEMON-AUTO\n    type: url-test\n    use: [subscription]\n    url: https://www.gstatic.com/generate_204\n    interval: 300\n    tolerance: 60\n"
            + "  - name: LEMON-SELECT\n    type: select\n    proxies: [LEMON-AUTO]\n    use: [subscription]\nrules:\n  - MATCH,LEMON-SELECT\n";
        Files.write(new File(home,"config.yaml").toPath(),config.getBytes(StandardCharsets.UTF_8));
        await(result -> core.nativeLoad(result,home.getAbsolutePath()));
        if (closing) return;
        Builder builder = new Builder().setSession("柠檬").setMtu(9000)
            .addAddress("172.19.0.1",30).addAddress("fdfe:dcba:9876::1",126)
            .addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("172.19.0.2").addDnsServer("fdfe:dcba:9876::2");
        builder.addAllowedApplication(getPackageName());
        ParcelFileDescriptor tun = builder.establish();
        if (tun == null) throw new IOException("VPN unavailable");
        core.nativeStartTun(tun.detachFd(),"gvisor","172.19.0.1/30,fdfe:dcba:9876::1/126","172.19.0.2,fdfe:dcba:9876::2","172.19.0.2,fdfe:dcba:9876::2",new TunInterface() {
            public void markSocket(int fd) { protect(fd); }
            public int querySocketUid(int protocol,String source,String target) { return -1; }
        });
        running = true;
        updateList();
    }

    private void updateList() throws JSONException {
        JSONObject group = new JSONObject(core.nativeQueryGroup(SELECT,"Default"));
        JSONArray list = group.getJSONArray("proxies");
        JSONArray usable = new JSONArray();
        for (int i=0; i<list.length(); i++) {
            JSONObject node = list.getJSONObject(i);
            if (!"COMPATIBLE".equalsIgnoreCase(node.optString("name"))) usable.put(node);
        }
        nodes = usable;
        String chosen = getSharedPreferences("nodes",0).getString("choice",AUTO);
        boolean exists = false;
        for (int i=0;i<usable.length();i++) exists |= chosen.equals(usable.getJSONObject(i).optString("name"));
        if (!exists && usable.length()>1) {
            chosen = AUTO;
            getSharedPreferences("nodes",0).edit().putString("choice",AUTO).apply();
        }
        if (usable.length()>1 && !chosen.equals(group.optString("now"))) core.nativePatchSelector(SELECT,chosen);
        connected = running && usable.length()>1;
        status = connected ? "已连接" : "暂未获取到节点";
    }

    private void refreshSubscription() {
        if (refreshing || closing) return;
        refreshing = true;
        CompletableDeferred<Unit> pending = CompletableDeferredKt.CompletableDeferred((Job)null);
        pending.invokeOnCompletion(cause -> {
            worker.execute(() -> {
                refreshing = false;
                if (closing) return;
                try {
                    if (cause != null) throw new IOException("Update failed");
                    updateList();
                    core.nativeHealthCheckAll();
                } catch (Exception failure) {
                    status = connected ? "已连接，上次的节点列表仍可用" : "订阅刷新失败，可重试";
                }
            });
            return Unit.INSTANCE;
        });
        core.nativeUpdateProvider(pending,"Proxy",SOURCE);
    }

    static void readNodes(java.util.function.Consumer<JSONArray> callback) {
        worker.execute(() -> {
            JSONArray result = nodes;
            if (connected) {
                try {
                    JSONArray fresh = new JSONObject(core.nativeQueryGroup(SELECT,"Default")).getJSONArray("proxies");
                    JSONArray available = new JSONArray();
                    for (int i=0;i<fresh.length();i++) {
                        JSONObject node = fresh.getJSONObject(i);
                        if (!"COMPATIBLE".equalsIgnoreCase(node.optString("name"))) available.put(node);
                    }
                    result = available;
                } catch (Exception ignored) {}
            }
            JSONArray snapshot = result;
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(snapshot));
        });
    }

    static void choose(Context context,String name,java.util.function.Consumer<Boolean> callback) {
        worker.execute(() -> {
            boolean ok = false;
            if (connected && core.nativePatchSelector(SELECT,name)) {
                try { ok = name.equals(new JSONObject(core.nativeQueryGroup(SELECT,"Default")).optString("now")); }
                catch (Exception ignored) {}
            }
            if (ok) context.getSharedPreferences("nodes",0).edit().putString("choice",name).apply();
            boolean selected = ok;
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(selected));
        });
    }

    @Override public void onRevoke() { stopSelf(); }
    @Override public void onDestroy() {
        closing = true;
        connected = false;
        nodes = new JSONArray();
        worker.execute(() -> {
            if (core != null) { if (running) core.nativeStopTun(); core.nativeReset(); }
            running = false;
        });
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
