# Magnifeye

A private, sideloadable Android magnifier designed for reading menus and receipts in dim places. It uses the rear camera full-screen and has no network access, analytics, or store dependencies. Look for the cheerful old man with a magnifying glass on your launcher.

## Controls

- Slide **up/down** anywhere on the preview to increase/decrease magnification.
- Slide **left/right** to dim/brighten the torch.
- Tap the torch button to switch the light on or off.
- Rotate the phone: portrait and landscape are both supported.

Torch dimming uses Android's variable-strength flashlight support when the phone and Android version expose it. On devices/versions that only allow an on/off torch while the camera is open, the slider gracefully falls back to on/off control.

## Build and install

Install Android SDK Platform 35, Build Tools 35, JDK 17 or newer, and Gradle
8.11.1 or newer. Set `ANDROID_HOME`, then run:

```bash
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper is intentionally not checked in because its launcher JAR is a
binary file. If you prefer to use a wrapper, generate it locally after cloning:

```bash
gradle wrapper --gradle-version 8.14.3
./gradlew assembleDebug
```

The first launch asks for camera permission. The application requests only camera access and is intended for direct installation on your own device.

## Build and download using only a web browser

Every push to `main` builds an APK with GitHub Actions. You can also start a
build manually:

1. Open the repository on GitHub and select **Actions**.
2. Select **Build Android APK** in the left sidebar.
3. Select **Run workflow**, then select the green **Run workflow** button.
4. Wait for the run to finish with a green check mark and open that run.
5. Under **Artifacts**, select **pocket-magnifier-apk** to download a ZIP file.
6. Extract the ZIP and open `app-debug.apk` on the Android device.
7. If prompted, allow the browser or Files app to install unknown apps, then
   select **Install**. You can turn that permission off again afterward.

The artifact is retained for 30 days. Android may warn that the debug APK is
from an unknown source because it was privately built rather than distributed
through an app store.
