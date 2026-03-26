# Vibe Cast

Vibe Cast is a local-first Android TV receiver that exposes an HTTP controller page and a WebSocket control channel over the same Wi-Fi network.

## What is in this repo

- Android TV receiver app in Kotlin + Compose
- Embedded local HTTP/WebSocket server
- Media3 / ExoPlayer playback for MP4 and HLS
- Media3 playback for MP4, MKV, WebM, HLS, DASH, SmoothStreaming, and RTSP
- QR-based pairing screen
- Browser controller UI served directly from the TV app

## Local protocol

HTTP controller page:

- `GET /`

WebSocket endpoint:

- `ws://<tv-ip>:<port>/ws`

Sample commands:

```json
{ "action": "play", "url": "https://example.com/video.m3u8" }
{ "action": "play", "url": "https://example.com/proxy?id=123", "format": "hls" }
{ "action": "play", "url": "https://example.com/video.mpd", "format": "dash" }
{ "action": "play", "url": "https://example.com/file.mkv", "format": "mkv" }
{ "action": "play", "url": "https://pub-xxxx.r2.dev/raw", "format": "progressive", "container": "mkv" }
{ "action": "play", "url": "rtsp://192.168.1.5/live", "format": "rtsp" }
{ "action": "play", "url": "https://example.com/protected", "format": "mp4", "headers": { "Referer": "https://example.com" } }
{ "action": "pause" }
{ "action": "resume" }
{ "action": "seek", "positionMs": 120000 }
{ "action": "volume", "value": 65 }
```

The receiver broadcasts JSON state snapshots back to connected controllers.

## Format notes

- ExoPlayer in this app supports HLS, DASH, SmoothStreaming, RTSP, and progressive files.
- MKV, MP4, WebM, MP3, AAC, FLAC, WAV, and OGG containers/streams are handled when the TV device has the required decoders.
- No Android TV app can guarantee every audio/video codec on every device. Actual decode support still depends on the TV chipset and available hardware/software decoders.
- For proxy URLs, workers endpoints, or signed URLs without a file extension, send a `format` hint such as `hls`, `dash`, or `progressive`.
- For progressive opaque URLs, also send a `container` hint such as `mkv`, `mp4`, or `webm`.

## Build

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot'
.\gradlew.bat --no-daemon assembleDebug
```

Debug APK:

- `app/build/outputs/apk/debug/app-debug.apk`
- `release/vibe-cast-debug.apk` for direct download from GitHub

## Website integration

The web side should connect to a known receiver endpoint:

- `ws://<tv-ip>:<port>/ws`
- `http://<tv-ip>:<port>/state`

Recommended flow:

1. User scans the QR code shown by the TV app.
2. Your site receives or stores the TV IP and port.
3. Your site opens the WebSocket and sends playback commands.
4. Save the receiver in `localStorage` for one-click reconnect later.

A small helper for browser-side integration is included at `sdk/vibecast-web.js`.

Example:

```html
<script src="/sdk/vibecast-web.js"></script>
<script>
  const client = new window.VibeCastWeb.VibeCastClient();

  await client.connect({
    host: "192.168.1.23",
    port: 8080,
    remember: true,
  });

  client.play("https://example.com/stream.m3u8");
</script>
```

The site should not try to generically scan the local network from JavaScript. Use QR pairing or a previously remembered receiver instead.

Safer website integration from an HTTPS site:

```js
const client = new window.VibeCastWeb.VibeCastClient({
  host: "192.168.1.23",
  port: 8080,
});

client.launchController("https://example.com/stream.m3u8");
```

That opens the TV app's local controller page and auto-starts playback using the `?play=` query parameter.

## Notes

- The project is configured for `compileSdk 36` and `targetSdk 36`.
- Android SDK is expected at `H:\Android\Sdk` on this machine.
- NanoHTTPD is vendored in-source under `app/src/main/java/org/nanohttpd` and its license is copied to `third_party/nanohttpd/LICENSE.md`.
