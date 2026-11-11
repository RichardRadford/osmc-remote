# OSMC Remote

An Android app that manages an OSMC box (Raspberry Pi or Vero) over SSH, with a Material 3 interface.

## What it does

| Tab | Features |
|---|---|
| **Home** | Live CPU temperature, load, memory and storage (refreshes every 5 s); Pi under-voltage/throttling warnings; restart/stop/start Kodi; reboot; shut down |
| **Updates** | Checks the OSMC repositories and lists pending packages. Installs updates (`apt full-upgrade`) **detached on the device** with a live log, so closing the app doesn't interrupt the update. Offers a reboot when the update needs one |
| **Config** | Pi: easy toggles and fields for common `config.txt` options (HDMI, overscan, CEC, GPU memory, overclocking, codec licences), written to `config-user.txt` when present. All devices: raw editor for any boot file. Every save makes a `.osmcremote.bak` backup first |
| **Services** | Every systemd service with its status. Start, stop, restart, enable or disable at boot, and view each service's journal. Asks first before stopping SSH, ConnMan or Kodi |
| **Tools → Audio** | Live Kodi volume and mute; default volume boost (amplification, 0–60 dB) for all media; every Kodi audio setting (output device, speaker layout, passthrough, volume steps, and so on), read live from Kodi and applied immediately |
| **Tools → Network** | Adapters (turn Wi-Fi and Bluetooth on or off), IP addresses, Wi-Fi scan, connect with a password (via a ConnMan provisioning file) and disconnect |
| **Tools → Logs** | Kodi log, system journal, kernel log and update log. Share any of them, or run OSMC's `grab-logs -A` to upload all logs and get a link for the forum |
| **Tools → Terminal** | Runs single commands (optionally as root) with quick-command chips |

## Getting the APK (GitHub Actions)

1. Create a **private** GitHub repository and push this folder to it (`git push` to `main`).
2. The **Build APK** workflow runs automatically. It takes about 4–6 minutes the first time.
3. On your phone, open the repository → **Releases** → latest → **OSMC-Remote.apk**. Allow installing from your browser when Android asks.

Every push to `main` produces a new release. All builds are signed with the same key (`app/osmcremote.keystore`), so each new APK installs over the previous one.

> The signing key and its password are committed to the repository so CI can sign without secrets. That's fine for a personal sideloaded app in a private repository. Don't publish the app to a store with this key.

To build locally instead, open the folder in Android Studio and press Run, or run `./gradlew assembleRelease`.

## Connecting

- Host: your OSMC device's IP address (Kodi → Settings → System information)
- User / password: `osmc` / `osmc` by default (please change it: `passwd` in the Terminal tab)
- The first connection saves the device's SSH host key. If the key ever changes, the app warns you before connecting.
- The password is stored with Android's EncryptedSharedPreferences only if you choose "Remember password".

Admin actions use `sudo`. OSMC's `osmc` user has passwordless sudo by default. If yours doesn't, the app uses the login password instead.

## Project layout

```
app/src/main/java/dev/osmcremote/
  ssh/SshClient.kt      JSch wrapper (coroutines, cancellable exec and streaming)
  Osmc.kt               every device operation as shell commands
  AppViewModel.kt       connection state, update progress, terminal history
  ui/                   Compose screens
app/src/main/assets/sh/ status.sh, services.sh, upgrade.sh (run on the device)
```

## Notes

- Built with Kotlin 2.0, Jetpack Compose (Material 3), `com.github.mwiede:jsch` and BouncyCastle. Requires Android 8.0 or later.
- The terminal isn't a full TTY, so interactive programs such as `nano` won't work there.
- Not affiliated with the OSMC project.
