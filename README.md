# FlashBridge

![FlashBridge logo](assets/flashbridge.svg)

Small, local Android ↔ Windows file transfer. Files stay on the local connection. No account, cloud upload, or deletion of existing files.

## Install

1. Install `FlashBridge-Setup.exe` on Windows and `FlashBridge.apk` on Android from the [latest release](https://github.com/AbhayShah06/FlashBridge/releases/latest).
2. Open both apps. Choose the receive folder on each device.
3. Put the devices on the same network. For a direct connection without a router or internet, turn on the Android phone's hotspot and connect Windows to it. A 5 GHz or 6 GHz connection, if available, is best for large files.
4. Allow FlashBridge through Windows Firewall for **private networks** if Windows asks.
5. The Android app finds the Windows app automatically. Enter the six-digit code shown on Windows once.

On Android, **Send files to PC** selects multiple files and sends them one by one to the Windows receive folder. **Get files from PC** lets you select multiple files in the Windows receive folder and saves them to the Android receive folder. On Windows, **Add files to folder** selects multiple files for Android to fetch. A batch shows the current file and its progress in a notification, including when the phone screen is off. If the link drops, the app tries the current file again after reconnecting; it does not resume partial bytes.

The Windows installer includes an uninstaller in **Windows Settings → Installed apps → FlashBridge → Uninstall**. Uninstalling the program leaves your transfer folder and files alone. The installer is per-user and does not modify system power or firewall settings.

Both apps check the GitHub release on opening. When a newer version exists, **Update now** downloads the release, checks its SHA-256 hash, and starts the platform installer. Android still requires the system's installation confirmation. Windows closes the app before running its installer. Updates do not touch transfer folders.

## Limits and design

- Keep the Windows app running. The Android app uses a foreground transfer service and temporary CPU/Wi-Fi locks during a transfer, so the phone screen may turn off. Extremely restrictive vendor battery policies can still interrupt it.
- The connection uses plain HTTP on the local network, authenticated with a random pairing token. Use a private hotspot or trusted local network; traffic is not encrypted at the app layer.
- The apps never overwrite a completed file. Re-sending the same named content is skipped. Different content with the same name gets a numbered name. Incomplete Windows uploads stay in the app-created `.flashbridge-partials` directory; Android removes an incomplete destination document after a failed download.
- Transfer speed depends on the phone and PC radio, signal, storage, and network. The app streams in 256 KiB chunks and does not relay files through the internet.

## Build

Android: JDK 17, Android SDK 35, Gradle 8.9: `gradle :android:assembleDebug`.

Windows: Python 3.11+: `pip install pyinstaller && pyinstaller --onefile --windowed --name FlashBridge windows/flashbridge.py`. Build the installer with Inno Setup 6 and `installer/FlashBridge.iss`.

GitHub Actions builds the APK and Windows installer and publishes them as release assets on a version tag.
