KCP TV Android App
====================

Open this folder in Android Studio, let Gradle sync, then build/run the app.

The app:
- Uses app/src/main/res/raw/channels.m3u
- Shows a searchable channel list
- Plays HLS streams with AndroidX Media3 ExoPlayer
- Remembers the last channel
- Supports favorites
- Shows clearer loading and failure messages
- Allows cleartext HTTP streams through AndroidManifest.xml

Build:
1. Open Android Studio.
2. File > Open > select this android-live-tv folder.
3. Wait for Gradle sync.
4. Build > Build Bundle(s) / APK(s) > Build APK(s).

Notes:
- Some channels may fail if the stream is offline, blocked, or unsupported by the device.
- Xtream/Stalker credentials are not bundled because APK files can be extracted.
- Android Studio needs internet access during the first Gradle sync to download Media3.
