# KPFE Controller Firmware Restore

Android app for restoring stock **v8 or v9** controller firmware on the
**KONKR Pocket FIT Elite / KR02**. Package: `org.kpfe.restore`. Root access required.

Based on the UART protocol and update flow reverse-engineered from AYANEO's
official service updater supplied with the Pocket Ace firmware package, and
cross-checked against the Elite's stock updater. The supplied APK failed to
install on the Elite because its signing certificate did not match
`android.uid.system` (`INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`). This standalone
app uses root access without requiring AYANEO's system signing key.

The **Elite v8 and v9 firmware images** were captured from stock updates,
verified against the saved originals by SHA-256, and are bundled unchanged
in the APK.

[Download the APK](https://github.com/mrdidit/kpfe-controller-fw-restore/releases/tag/v0.5.0-preview).
Requires Android 12+ (API 31), ARM64, root, Android user 0 and stock AYANEO services.

Select v8/v9 → **Check root & device** → confirm **Restore**. Keep the app visible
and power connected. Minimum battery: 15% while charging, 30% otherwise.
The app enters update mode through UART, transfers the pinned image, sends Quit
and cycles the display to reinitialize AYANEO controls. Android does not reboot.

Diagnostics contains entry/exit tests, reinitialization, logs and **Collect &
export one support report**. The passive ZIP captures device/USB state and updater
evidence without entering update mode or sending controller commands.

**Standard Pocket FIT preview support:** separate AR14/USB path and bundled,
hash-pinned supplied firmware (version unconfirmed). Device and image checks
reject cross-model flashing. FIT restoration is **not hardware-tested**; use the
single support report to investigate a failed AYANEO update. Report collection
and export were tested on KPFE in 0.5.0; no firmware was written during that test.

**Hardware validation on a working Elite:**

| Transfer | App | Result |
| --- | --- | --- |
| v8 → v8 | 0.3.0-preview | Trace verified; controls/RGB confirmed |
| v9 → v8 | 0.3.0-preview | Trace verified; controls/RGB confirmed |
| v8 → v9 | 0.4.2-preview | Trace verified; controls/RGB confirmed |

Software entry requires a responding controller. Corrupted-firmware and
interrupted-flash recovery remain unproven. Installed-version reporting uses
AYANEO's controller-derived cache, not flash readback. 

> [!WARNING]
> **Use at your own risk!**
> 
> This tool restores controller firmware, but things can still go spectacularly wrong.
> I take no responsibility for bricked controllers, broken devices, corrupted firmware, damaged hardware, shattered Windows (the OS or the actual glass), spontaneous combustion, or anything else that decides to give up on life.
> If it breaks, explodes, catches fire, or summons a demon, that's between you and your device.
>
>**All disclaimers apply to the extent permitted by applicable law.**

<img width="1920" height="1080" alt="Screenshot_20261114-111205" src="https://github.com/user-attachments/assets/c3587262-0254-4d10-b657-995d79747b56" />
