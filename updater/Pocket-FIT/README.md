# Pocket FIT supplied firmware

Standard Pocket FIT (AR14), separate from Elite/KR02. The supplied `fit.bin` is
preserved byte-for-byte as `Pocket_FIT_supplied.bin`: 79,692 bytes, SHA-256 in
`SHA256SUMS`. Its firmware version is unconfirmed; the original folder label
“FIT 2.2” does not establish an embedded version.

The supplied updater is `com.ayaneo.newcontrollerota` 2.6.3 (code 54), APK SHA-256
`aa97a5033605b4d794ef453927d12b2cfd132dfd374fda6167c4737f9d9021d2`.
It selects AR14/USB for FIT. The application image contains RISC-V startup code;
it is not an Elite image or a bootloader backup. Hardware restoration remains untested.
