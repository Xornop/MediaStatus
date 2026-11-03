package nl.xornop.mediastatus;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MediaListener extends NotificationListenerService {

    static final int PORT = 8099;
    static volatile String json = "{\"state\":\"idle\"}";

    private MediaSessionManager msm;
    private ComponentName cn;
    private final List<MediaController> watched = new ArrayList<>();
    private ServerSocket server;

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { update(); }
        @Override public void onMetadataChanged(MediaMetadata m) { update(); }
        @Override public void onSessionDestroyed() { update(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            this::rebuild;

    @Override
    public void onListenerConnected() {
        msm = getSystemService(MediaSessionManager.class);
        cn = new ComponentName(this, MediaListener.class);
        msm.addOnActiveSessionsChangedListener(sessionsListener, cn);
        rebuild(msm.getActiveSessions(cn));
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
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) {}
        for (MediaController c : watched) c.unregisterCallback(cb);
        watched.clear();
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
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
        update();
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

    private void update() {
        MediaController pick = null;
        for (MediaController c : watched) {
            String st = mapState(c.getPlaybackState());
            if (st.equals("playing")) { pick = c; break; }
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
        new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                ServerSocket s = server;
                while (!s.isClosed()) {
                    try (Socket c = s.accept()) {
                        c.setSoTimeout(2000);
                        byte[] buf = new byte[1024];
                        try { c.getInputStream().read(buf); } catch (Exception ignored) {}
                        byte[] body = json.getBytes(StandardCharsets.UTF_8);
                        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                        OutputStream out = c.getOutputStream();
                        out.write(head.getBytes(StandardCharsets.UTF_8));
                        out.write(body);
                        out.flush();
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }, "mediastatus-http").start();
    }
}
