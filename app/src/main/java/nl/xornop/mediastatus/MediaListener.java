package nl.xornop.mediastatus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaListener extends NotificationListenerService {

    static final int PORT = 8099;
    static final String CHANNEL = "mediastatus";
    // Clients are served in parallel, so one slow or idle connection can't block the others.
    static final int MAX_CLIENTS = 8;
    static volatile String json = "{\"state\":\"idle\"}";

    private MediaSessionManager msm;
    private ComponentName cn;
    private final List<MediaController> watched = new CopyOnWriteArrayList<>();
    private volatile ServerSocket server;
    private volatile ExecutorService clients;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            try {
                if (msm != null) rebuild(msm.getActiveSessions(cn));
            } catch (Exception ignored) {}
            main.postDelayed(this, 15000);
        }
    };

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { build(); }
        @Override public void onMetadataChanged(MediaMetadata m) { build(); }
        @Override public void onSessionDestroyed() {
            main.post(refresh);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            this::rebuild;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        return START_STICKY;
    }

    private void goForeground() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Media Status", NotificationManager.IMPORTANCE_MIN));
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Media Status active")
                    .build();
            startForeground(1, n);
        } catch (Exception ignored) {}
    }

    @Override
    public void onListenerConnected() {
        try { startForegroundService(new Intent(this, MediaListener.class)); } catch (Exception ignored) {}
        msm = getSystemService(MediaSessionManager.class);
        cn = new ComponentName(this, MediaListener.class);
        msm.addOnActiveSessionsChangedListener(sessionsListener, cn);
        rebuild(msm.getActiveSessions(cn));
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 15000);
        startServer();
    }

    @Override
    public void onListenerDisconnected() {
        stop();
        requestRebind(new ComponentName(this, MediaListener.class));
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void stop() {
        main.removeCallbacks(refresh);
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) {}
        for (MediaController c : watched) c.unregisterCallback(cb);
        watched.clear();
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        if (clients != null) clients.shutdownNow();
        clients = null;
    }

    private void rebuild(List<MediaController> list) {
        for (MediaController c : watched) c.unregisterCallback(cb);
        watched.clear();
        if (list != null) {
            for (MediaController c : list) {
                c.registerCallback(cb);
                watched.add(c);
            }
        }
        build();
    }

    private static String mapState(PlaybackState s) {
        if (s == null) return "idle";
        switch (s.getState()) {
            case PlaybackState.STATE_PLAYING: return "playing";
            case PlaybackState.STATE_PAUSED: return "paused";
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING: return "buffering";
            case PlaybackState.STATE_STOPPED: return "stopped";
            default: return "idle";
        }
    }

    // Builds the status from the live controllers every time it is called.
    private String build() {
        MediaController pick = null;
        for (MediaController c : watched) {
            if (mapState(c.getPlaybackState()).equals("playing")) { pick = c; break; }
        }
        if (pick == null) {
            for (MediaController c : watched) {
                String st = mapState(c.getPlaybackState());
                if (st.equals("paused") || st.equals("buffering")) { pick = c; break; }
            }
        }
        if (pick == null && !watched.isEmpty()) pick = watched.get(0);

        try {
            JSONObject o = new JSONObject();
            if (pick == null) {
                o.put("state", "idle");
            } else {
                String pkg = pick.getPackageName();
                o.put("state", mapState(pick.getPlaybackState()));
                o.put("app", pkg);
                o.put("app_name", label(pkg));
                MediaMetadata md = pick.getMetadata();
                if (md != null) {
                    o.put("title", String.valueOf(md.getString(MediaMetadata.METADATA_KEY_TITLE)));
                    o.put("artist", String.valueOf(md.getString(MediaMetadata.METADATA_KEY_ARTIST)));
                }
            }
            json = o.toString();
        } catch (Exception ignored) {}
        return json;
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private void startServer() {
        if (server != null) return;
        final ExecutorService pool = Executors.newFixedThreadPool(MAX_CLIENTS);
        clients = pool;
        new Thread(() -> {
            try {
                ServerSocket s = new ServerSocket(PORT);
                server = s;
                while (!s.isClosed()) {
                    final Socket c;
                    try {
                        c = s.accept();
                    } catch (Exception e) {
                        continue; // closed by stop(), or a transient accept error
                    }
                    try {
                        pool.execute(() -> handle(c));
                    } catch (Exception e) {
                        try { c.close(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                // Port could not be opened: drop this pool so a later startServer() can retry.
                pool.shutdownNow();
                if (clients == pool) clients = null;
            }
        }, "mediastatus-http").start();
    }

    private void handle(Socket socket) {
        try (Socket c = socket) {
            c.setSoTimeout(2000);
            byte[] buf = new byte[1024];
            try { c.getInputStream().read(buf); } catch (Exception ignored) {}
            byte[] body = build().getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            OutputStream out = c.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        } catch (Exception ignored) {}
    }
}
