# KPFE Controller Firmware Restore

Rooted Android app for restoring stock **v8 or v9** controller firmware on the
**AYANEO Pocket FIT Elite / KR02**. Package: `org.kpfe.restore`.

[Download the APK](https://github.com/mrdidit/kpfe-controller-fw-restore/releases/tag/v0.4.2-preview).
Requires Android 12+ (API 31), ARM64, root, Android user 0 and stock AYANEO services.

Select v8/v9 → **Check root & device** → confirm **Restore**. Keep the app visible
and power connected. Minimum battery: 15% while charging, 30% otherwise.
The app enters update mode through UART, transfers the pinned image, sends Quit
and cycles the display to reinitialize AYANEO controls. Android does not reboot.
Diagnostics contains entry/exit tests, reinitialization and logs.

**Hardware validation on a working Elite:**

| Transfer | App | Result |
| --- | --- | --- |
| v8 → v8 | 0.3.0-preview | Trace verified; controls/RGB confirmed |
| v9 → v8 | 0.3.0-preview | Trace verified; controls/RGB confirmed |
| v8 → v9 | 0.4.2-preview | User confirmed success; trace not collected |

Software entry requires a responding controller. Corrupted-firmware and
interrupted-flash recovery remain unproven. Installed-version reporting uses
AYANEO's controller-derived cache, not flash readback. The 0.4.2 APK predates the
v9 test and still displays its earlier “v9 not yet tested” confirmation text.

## Build

Linux, Python 3, JDK 17+ (`java`, `keytool`), Android SDK Platform 35 and Build Tools
35.0.1+. Set `ANDROID_HOME` to the SDK directory.

```sh
sh restore-app/tests/run.sh
python3 restore-app/build.py
```

Output: `dist/Controller-FW-Restore-0.4.2-preview.apk`. A first local build creates
a private signing key under `.build/restore-app-signing/`; preserve it for future
updates. Local builds use a different signer from the published APK.

The Java protocol core is in `restore-app/core/`; Android UI, service and root
helper are in `restore-app/android/`. Stock images and checksums are in
[`updater/KPFE-stock/`](updater/KPFE-stock/); the pinned vendor UART library is in
[`restore-app/vendor/`](restore-app/vendor/). Vendor firmware, library and artwork
remain third-party assets. Research captures, SDKs and signing keys are local only.
