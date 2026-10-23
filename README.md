# FlashBridge

Small, local Android ↔ Windows file transfer. Files stay on the local connection. No account, cloud upload, or deletion of existing files.

## Install

1. Install `FlashBridge-Setup.exe` on Windows and `FlashBridge.apk` on Android from the [latest release](https://github.com/AbhayShah06/FlashBridge/releases/latest).
2. Open both apps. Choose the receive folder on each device.
3. Put the devices on the same network. For a direct connection without a router or internet, turn on the Android phone's hotspot and connect Windows to it. A 5 GHz or 6 GHz connection, if available, is best for large files.
4. Allow FlashBridge through Windows Firewall for **private networks** if Windows asks.
5. The Android app finds the Windows app automatically. Enter the six-digit code shown on Windows once.

On Android, **Send file** picks any file and sends it to the Windows receive folder. **Get from PC** lists files in the Windows receive folder and saves the chosen file to the Android receive folder. On Windows, **Add file to folder** makes a file available for Android to fetch. Both directions use the same private local connection.

The Windows installer includes an uninstaller in Windows Settings. Uninstalling the program leaves your transfer folder and files alone.

## Limits and design

- Both apps must be open during a transfer. The Android app does not run a background transfer service.
- The connection uses plain HTTP on the local network, authenticated with a random pairing token. Use a private hotspot or trusted local network; traffic is not encrypted at the app layer.
- The apps never overwrite a completed file. An incoming duplicate gets a numbered name. Incomplete Windows uploads stay in a hidden `.flashbridge-partials` directory; Android removes an incomplete destination document after a failed download.
- Transfer speed depends on the phone and PC radio, signal, storage, and network. The app streams in 256 KiB chunks and does not relay files through the internet.

## Build

Android: JDK 17, Android SDK 35, Gradle 8.9: `gradle :android:assembleDebug`.

Windows: Python 3.11+: `pip install pyinstaller && pyinstaller --onefile --windowed --name FlashBridge windows/flashbridge.py`. Build the installer with Inno Setup 6 and `installer/FlashBridge.iss`.

GitHub Actions builds the APK and Windows installer and publishes them as release assets on a version tag.
