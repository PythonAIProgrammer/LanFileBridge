# LanFileBridge

LanFileBridge is a local-network file sharing tool for PC and Android. When both devices are connected to the same Wi-Fi network, they can automatically discover each other, browse accessible files, preview images and videos online, search file names, and download files directly to local storage.

All transfers happen over the local network. No cloud service is used.

## Features

- Automatic LAN discovery between PC and Android
- PC web console powered by a lightweight Node.js service
- Native Android app
- Browse accessible files on the other device
- Search file names online from both PC and Android
- Image and video thumbnails
- Online image preview and video playback
- Direct file download
- Android APK included in `dist/`

## Project Structure

```text
LanFileBridge/
  pc/       PC-side Node.js service and web UI
  android/  Native Android app source
  dist/     Prebuilt Android APK
```

## PC Usage

Requirements:

- Node.js 18 or newer

Run:

```powershell
cd pc
npm.cmd start
```

Then open:

```text
http://localhost:4317
```

## Android APK

The prebuilt debug APK is available here:

```text
dist/LanFileBridge-android-debug.apk
```

After installing the app, grant the requested "All files access" permission so the Android side can expose shared storage files.

## Android Build

Open the `android/` folder in Android Studio, or use the included build script after installing Android SDK build tools:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File android/build-apk.ps1
```

## Network Protocol

- UDP discovery: port `4318`
- HTTP file API: port `4317`

Main APIs:

- `GET /api/me`
- `GET /api/peers`
- `GET /api/files?path=...`
- `GET /api/search?q=...&path=...`
- `GET /api/preview?path=...`
- `GET /api/download?path=...`

## Security Notes

This project is intended for trusted local networks. Devices on the same LAN can browse accessible files exposed by the running app. Avoid using it on public Wi-Fi unless you add pairing, authentication, and access control.
