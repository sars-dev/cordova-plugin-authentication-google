# Example app

A Cordova app with one button per plugin method and a log of every result, used to test the plugin on a real device.

## 1. Google Cloud

Follow [Google Cloud setup](../README.md#google-cloud-setup) in the main README. For this app:

- **Web application** client: pass its ID to `npm run plugin` below.
- **Android** client: package name `io.github.sarsdev.googleauth.example` (the `id` in [config.xml](config.xml)) and the SHA-1 of your **debug** keystore.
- Add the Google account of the device or emulator as a **test user**.

## 2. Run

```bash
cd example
npm install
npm run plugin -- YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
npm run android
```

`npm run plugin` packs the plugin with `npm pack` and installs it, so the app gets exactly what would be published to npm. **Run it again after every change to the plugin's native or `www` code.**

Use a physical device or an emulator image **with Google Play**, signed in to a Google account.

## 3. Debug

- JavaScript console: open `chrome://inspect` in desktop Chrome and inspect the app's WebView.
- Native logs: `adb logcat -s GoogleAuth`

For errors such as `CONFIGURATION_ERROR` / `[28444]`, see [Troubleshooting](../README.md#troubleshooting) and [Errors](../README.md#errors) in the main README.
