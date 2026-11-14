# Vendor serial library

`arm64-v8a/libserial_port.so` is the unchanged ARM64 library extracted from the
installed AYANEO Settings **1.1.114 / code 149**, package `com.ayaneo.settings`.
The original APK is not needed to build this app.

- Original APK SHA-256: `2a9a1ddaa71211bfac6daee96b375ea204e84107551c23040cbab0d81c215829`
- APK entry: `lib/arm64-v8a/libserial_port.so`
- Library: **9,992 bytes**, SHA-256 `0a939a29544a76913ad682a57cd572246cc23c57cdff4db5040282f90532e3e6`

The build validates the library's size, hash and ARM64 ELF structure before
packaging. Its load segments have 4 KiB alignment; 16 KiB ELF compatibility is
not established. This vendor binary is not built from this repository's sources.
