package nl.xornop.mediastatus;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {

    private TextView info;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(80, 80, 80, 80);

        info = new TextView(this);
        info.setTextSize(22);
        l.addView(info);

        Button btn = new Button(this);
        btn.setText("Meldingentoegang openen");
        btn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                info.append("\n\nInstellingen niet beschikbaar op deze TV. Gebruik via een pc:\n"
                        + "adb shell cmd notification allow_listener "
                        + "nl.xornop.mediastatus/nl.xornop.mediastatus.MediaListener");
            }
        });
        l.addView(btn);
        setContentView(l);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean ok = enabled != null && enabled.contains(getPackageName());
        info.setText("Status: " + (ok ? "meldingentoegang AAN" : "meldingentoegang UIT")
                + "\nURL: http://" + ip() + ":" + MediaListener.PORT
                + "\n\n" + MediaListener.json);
    }

    private String ip() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "?";
    }
}
