# Media Status

A tiny Android TV / Google TV app that reports what is playing on the device (app, playback state, title) over a local HTTP endpoint, so Home Assistant can read it as a REST sensor.

It was built for the Chromecast with Google TV (Android 14), where the usual options fall short:

- The Android TV Remote integration reports the active app, but not play/pause state.
- Google Cast goes offline in standby.
- ADB is unreliable on recent Google TV versions (wireless debugging uses randomized ports).
- The Home Assistant Companion App gets throttled in the background and only exposes a "music active" sensor.

Media Status uses Android's `MediaSessionManager` through a notification listener. Notification listeners are managed by the system and are not killed by battery optimization like regular background apps.

## Features

- Reports `playing`, `paused`, `buffering`, `stopped` or `idle`
- Reports the app (package name and display name), title and artist when the app provides them
- No cloud, no accounts, no configuration inside the app
- No third-party dependencies

## How it works

The app registers a notification listener service. While it is enabled, it watches all active media sessions on the device and serves the current state as JSON on port `8099`. If several sessions exist, a playing session wins, then paused or buffering, then the first one.

```
GET http://<tv-ip>:8099/
```

```json
{
  "state": "playing",
  "app": "com.netflix.ninja",
  "app_name": "Netflix",
  "title": "Some title",
  "artist": "null"
}
```

When nothing is active, the response is `{"state":"idle"}`. Fields that an app does not provide may be missing or the string `"null"`.

## Download

Download the latest `mediastatus.apk` from the [Releases](../../releases) page of this repository.

## Build it yourself

The project builds without a local Android SDK by using GitHub Actions.

1. Fork this repository.
2. Open the **Actions** tab of your fork and enable workflows if GitHub asks.
3. Select **Build APK** and click **Run workflow**.
4. When the run finishes, download the **mediastatus-apk** artifact (requires being logged in to GitHub). It contains `mediastatus.apk`.

To publish a release with the APK attached, create a release with a tag starting with `v` (for example `v1.0`). The workflow builds the APK and attaches it automatically.

To build locally instead, use Gradle 8.7 with JDK 17 and the Android SDK (API 34), then run `gradle assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install

1. Sideload `mediastatus.apk` on the TV (for example with the Downloader app or `adb install`).
2. If installation is blocked, allow **Install unknown apps** for the app you used to open the APK. If Play Protect blocks it, choose **More details → Install anyway**, or disable Play Protect in Play Store > Profile icon > Play Protect settings
3. Open **Media Status**.

## Grant notification access

The app needs notification access to read media sessions.

1. In the app, select **Open notification access** and enable **Media Status**.
2. Some Google TV builds do not have this settings screen. In that case, run this once from a computer with ADB access:

```
adb shell cmd notification allow_listener nl.xornop.mediastatus/nl.xornop.mediastatus.MediaListener
```

The app shows its status and the URL it serves on the main screen. Open that URL in a browser to verify it works.

## Home Assistant

Give the TV a fixed IP address (DHCP reservation), then add to `configuration.yaml`:

```yaml
rest:
  - resource: http://192.168.1.50:8099
    scan_interval: 3
    sensor:
      - name: Chromecast media
        value_template: "{{ value_json.state }}"
        json_attributes:
          - app
          - app_name
          - title
          - artist
```

A first-time `rest:` entry requires a full Home Assistant restart. Reloading YAML is not enough.

This creates `sensor.chromecast_media`. The state is the playback state, and the app is available in the `app` and `app_name` attributes.

Example condition:

```yaml
condition: state
entity_id: sensor.chromecast_media
attribute: app
state:
  - com.netflix.ninja
  - com.google.android.youtube.tv
```

Combine this with the Android TV Remote integration for power and button control.

## Limitations

- When the TV is in deep sleep, the endpoint is unreachable and the REST sensor becomes `unavailable`. Handle this in a template if you need a clean `off` state.
- Some apps leave a stale session in `paused` after you close them.
- Only apps that publish a media session are reported. Apps that do not use Android's media session API will not show up.
- The endpoint has no authentication and no encryption. Only expose it on a trusted local network.
- This project is untested on devices other than the Chromecast with Google TV.

## Troubleshooting

- **Browser cannot reach the URL:** check that notification access is enabled (the main screen shows this), that the TV is awake, and that the IP is correct.
- **Sensor missing in Home Assistant:** run *Developer tools → YAML → Check configuration*, restart Home Assistant and check the logs for `rest` errors.
- **State always `idle`:** the app you are using may not publish a media session. Try another app, such as YouTube or Netflix.

## License

Choose a license for your repository (for example MIT) and add a `LICENSE` file.
