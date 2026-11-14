# Stock Elite firmware

Unmodified AYANEO Pocket FIT Elite / KR02 application images, captured from the
stock updater. These are not bootloader or configuration backups; not for Pocket Ace.

| Image | Bytes | UART packets | Acquired |
| --- | ---: | ---: | --- |
| `KPFE_KR02_v8.bin` | 58,568 | 1,010 | 2026-09-11 |
| `KPFE_KR02_v9.bin` | 58,592 | 1,011 | 2026-11-14 |

v9 was checked byte-for-byte against the stock updater's downloaded file.
Both images match the saved originals, are bundled unchanged in the APK, and
are pinned by size and SHA-256 in the build, app and protocol core.
Verify locally: `cd updater/KPFE-stock && sha256sum -c SHA256SUMS`.
