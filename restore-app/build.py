#!/usr/bin/env python3
"""Build/sign the standalone rooted Android app locally. Never deploy or flash."""
from pathlib import Path
import hashlib
import json
import os
import re
import secrets
import shutil
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "restore-app"
BUILD = ROOT / ".build/restore-app"
DIST = ROOT / "dist"
VERSION = "0.5.0-preview"
VERSION_CODE = 7
NAME = "Controller-FW-Restore-" + VERSION + ".apk"
FIRMWARE_IMAGES = (
    {"version": "8", "asset": "KPFE_KR02_v8.bin", "bytes": 58568,
     "sha256": "fd48a4b0852e3f888874cf1acce4ca2cfd7225ad3b40581e144d69a6a9f1e3cd"},
    {"version": "9", "asset": "KPFE_KR02_v9.bin", "bytes": 58592,
     "sha256": "1a43b810b4a5d19ea6e41c14b2b4b1b734b2959a12b200f38868221dc01ee0fe"},
    {"version": "FIT-supplied", "profile": "FIT", "directory": "Pocket-FIT",
     "asset": "Pocket_FIT_supplied.bin", "bytes": 79692,
     "sha256": "c152c91813437e7d7124cff477a6e46b230ed1adb83c4ad84c48ad74239f2ba2"},
)
NATIVE_LIBRARY = APP / "vendor/arm64-v8a/libserial_port.so"
NATIVE_APK_ORIGIN = "evidence/2026-09-11-ayasettings-1.1.114-export/apks/000-base.apk"
NATIVE_APK_HASH = "2a9a1ddaa71211bfac6daee96b375ea204e84107551c23040cbab0d81c215829"
NATIVE_ENTRY = "lib/arm64-v8a/libserial_port.so"
NATIVE_HASH = "0a939a29544a76913ad682a57cd572246cc23c57cdff4db5040282f90532e3e6"
NATIVE_BYTES = 9992


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def image_path(image):
    return ROOT / "updater" / image.get("directory", "KPFE-stock") / image["asset"]


def resolve_android_sdk(environ=None, workspace_root=None, home=None):
    """Find installed API 35/build tools; never download or modify an SDK.

    Explicit SDK environment variables take precedence and fail clearly when
    incomplete. Without them, preserve the workspace layout, then try normal
    Linux/macOS Android Studio locations. Preview build-tools are not selected.
    """
    environ = os.environ if environ is None else environ
    workspace_root = ROOT if workspace_root is None else Path(workspace_root)
    home = Path.home() if home is None else Path(home)
    configured = [Path(environ[key]).expanduser().resolve()
                  for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if environ.get(key)]
    if len(set(configured)) > 1:
        raise ValueError("ANDROID_HOME and ANDROID_SDK_ROOT must name the same SDK")
    candidates = configured[:1] or [workspace_root / ".tools/android-sdk",
                                  home / "Android/Sdk", home / "Library/Android/sdk"]
    required = ("aapt2", "zipalign", "lib/d8.jar", "lib/apksigner.jar")
    failures = []
    for sdk in candidates:
        platforms = (sdk / "platforms/android-35/android.jar", sdk / "android-35/android.jar")
        android = next((path for path in platforms if path.is_file()), None)
        versions = []
        for path in (sdk / "build-tools").glob("*"):
            match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", path.name)
            if match and tuple(map(int, match.groups())) >= (35, 0, 1):
                versions.append((tuple(map(int, match.groups())), path))
        tools_candidates = [path for _, path in sorted(versions, reverse=True)]
        tools_candidates.append(sdk / "android-15")  # Existing extracted 35.0.1 package.
        tools = next((path for path in tools_candidates
                      if all((path / item).is_file() for item in required)), None)
        if android is not None and tools is not None:
            return sdk, tools, android
        failures.append(str(sdk) + ": " + ", ".join(
            missing for missing, found in (("API 35 android.jar missing", android),
                                           ("complete stable Build Tools >=35.0.1 missing", tools))
            if found is None))
    raise FileNotFoundError("Install Android SDK Platform 35 and Build Tools 35.0.1 or later; "
                            "set ANDROID_HOME to that SDK. Checked: " + "; ".join(failures))


def build_inputs(sources):
    # Bitmap/font/raw resources must be pinned just like XML. Re-enumerate at
    # final verification so added/removed resources also invalidate the build.
    resources = sorted(p for p in (APP / "android/res").rglob("*") if p.is_file())
    return sources + resources + [APP / "android/AndroidManifest.xml", Path(__file__).resolve(), NATIVE_LIBRARY] + [image_path(image) for image in FIRMWARE_IMAGES]


def release_apks():
    return sorted(set(DIST.glob("Elite-Restore-*.apk")) | set(DIST.glob("Controller-FW-Restore-*.apk")))


def run(args, *, capture=False):
    result = subprocess.run(list(map(str, args)), cwd=ROOT, check=True,
                            text=True, capture_output=capture)
    return result.stdout if capture else ""


def native_library():
    """Read the pinned extracted library; the original APK is provenance only."""
    data = NATIVE_LIBRARY.read_bytes()
    if len(data) != NATIVE_BYTES or hashlib.sha256(data).hexdigest() != NATIVE_HASH:
        raise ValueError("Native library mismatch")
    # ELF64, little endian, ET_DYN, EM_AARCH64. Preserve the 4 KiB vendor ELF.
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<HH", data, 16) != (3, 183):
        raise ValueError("Native library must be an ARM64 shared object")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phnum = struct.unpack_from("<HH", data, 54)
    if phsize != 56 or not phnum or phoff + phsize * phnum > len(data):
        raise ValueError("Invalid native ELF program table")
    aligns = [struct.unpack_from("<Q", data, phoff + i * phsize + 48)[0]
              for i in range(phnum) if struct.unpack_from("<I", data, phoff + i * phsize)[0] == 1]
    if not aligns:
        raise ValueError("Native ELF has no load segments")
    return data, {"source_file": str(NATIVE_LIBRARY.relative_to(ROOT)),
                  "source_apk": NATIVE_APK_ORIGIN, "source_apk_sha256": NATIVE_APK_HASH,
                  "source_package": "com.ayaneo.settings", "source_version": "1.1.114", "source_version_code": 149,
                  "apk_entry": NATIVE_ENTRY, "bytes": len(data), "sha256": NATIVE_HASH,
                  "abi": "arm64-v8a", "elf_load_segment_alignments": aligns,
                  "zip_page_alignment_bytes": 16384, "extract_native_libs": True,
                  "limit": "Unmodified vendor ELF has 4 KiB load alignment; 16 KiB ELF compatibility is not established."}


def add_native_library(apk, data):
    with zipfile.ZipFile(apk, "a") as z:
        if NATIVE_ENTRY in z.namelist():
            raise ValueError("Native library already packaged")
        info = zipfile.ZipInfo(NATIVE_ENTRY, date_time=(2026, 10, 8, 0, 0, 0))
        info.compress_type = zipfile.ZIP_STORED
        z.writestr(info, data)


def validate_archive(apk):
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate entries")
        if sorted(n for n in names if n.startswith("lib/")) != [NATIVE_ENTRY]:
            raise ValueError("Unexpected APK native libraries or ABI")
        info = z.getinfo(NATIVE_ENTRY)
        data = z.read(info)
        if info.compress_type != zipfile.ZIP_STORED or len(data) != NATIVE_BYTES or hashlib.sha256(data).hexdigest() != NATIVE_HASH:
            raise ValueError("Packaged native library mismatch or compressed library")
        with apk.open("rb") as f:
            f.seek(info.header_offset)
            header = f.read(30)
        if header[:4] != b"PK\x03\x04":
            raise ValueError("Invalid native ZIP header")
        name_len, extra_len = struct.unpack_from("<HH", header, 26)
        if (info.header_offset + 30 + name_len + extra_len) % 16384:
            raise ValueError("Native ZIP data is not 16 KiB aligned")
        if sorted(n for n in names if n.startswith("assets/")) != sorted("assets/" + image["asset"] for image in FIRMWARE_IMAGES):
            raise ValueError("Packaged firmware asset list mismatch")
        for image in FIRMWARE_IMAGES:
            data = z.read("assets/" + image["asset"])
            if len(data) != image["bytes"] or hashlib.sha256(data).hexdigest() != image["sha256"]:
                raise ValueError("Packaged firmware mismatch for v" + image["version"])
        if "classes.dex" not in names:
            raise ValueError("Packaged classes missing")


def signer_digest(verification):
    matches = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", verification, re.M)
    if len(matches) != 1:
        raise ValueError("Expected exactly one APK signer")
    return matches[0]


def main():
    for image in FIRMWARE_IMAGES:
        path = image_path(image)
        if path.stat().st_size != image["bytes"] or digest(path) != image["sha256"]:
            raise ValueError("Stock firmware mismatch for v" + image["version"])
    sdk, tools, android = resolve_android_sdk()
    sources = sorted((APP / "android/src").rglob("*.java")) + sorted((APP / "core/src").rglob("*.java"))
    inputs = build_inputs(sources)
    before = {str(p.relative_to(ROOT)): digest(p) for p in inputs}
    native, native_report = native_library()
    if BUILD.exists(): shutil.rmtree(BUILD)
    for sub in ("classes", "gen", "dex", "assets"):
        (BUILD / sub).mkdir(parents=True, exist_ok=True)
    DIST.mkdir(exist_ok=True)
    for image in FIRMWARE_IMAGES:
        shutil.copyfile(image_path(image), BUILD / "assets" / image["asset"])
    run([tools / "aapt2", "compile", "--dir", APP / "android/res", "-o", BUILD / "resources.zip"])
    run([tools / "aapt2", "link", "-I", android, "--manifest", APP / "android/AndroidManifest.xml",
         "--min-sdk-version", "31", "--target-sdk-version", "35", "--java", BUILD / "gen",
         "-A", BUILD / "assets", BUILD / "resources.zip", "-o", BUILD / "unsigned.apk"])
    generated = sorted((BUILD / "gen").rglob("*.java"))
    run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8",
         "-source", "8", "-target", "8", "-classpath", android, "-d", BUILD / "classes", *sources, *generated])
    with zipfile.ZipFile(BUILD / "classes.jar", "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted((BUILD / "classes").rglob("*.class")):
            info = zipfile.ZipInfo(str(p.relative_to(BUILD / "classes")), date_time=(2026, 10, 8, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, p.read_bytes())
    run(["java", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--release",
         "--min-api", "31", "--lib", android, "--output", BUILD / "dex", BUILD / "classes.jar"])
    with zipfile.ZipFile(BUILD / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as z:
        for p in sorted((BUILD / "dex").glob("*.dex")): z.write(p, p.name)
    add_native_library(BUILD / "unsigned.apk", native)
    run([tools / "zipalign", "-f", "-P", "16", "4", BUILD / "unsigned.apk", BUILD / "aligned.apk"])
    signing = ROOT / ".build/restore-app-signing"
    signing.mkdir(exist_ok=True, mode=0o700)
    password = signing / "password.txt"
    key = signing / "elite-restore.p12"
    if not key.exists():
        if release_apks():
            raise SystemExit("Existing release APK found but signing key is missing; restore the original private key")
        if password.exists(): raise SystemExit("Signing password exists without key; inspect before regenerating")
        password.write_text(secrets.token_urlsafe(32) + "\n"); password.chmod(0o600)
        run(["keytool", "-genkeypair", "-keystore", key, "-storetype", "PKCS12", "-storepass:file", password,
             "-keypass:file", password, "-alias", "elite-restore", "-keyalg", "RSA", "-keysize", "3072",
             "-validity", "10000", "-dname", "CN=Elite Restore local project build", "-noprompt"])
        key.chmod(0o600)
    assert password.exists(), "Signing key password missing"
    apk = BUILD / NAME  # Verify and detect concurrent edits before publishing.
    run(["java", "-jar", tools / "lib/apksigner.jar", "sign", "--ks", key, "--ks-key-alias", "elite-restore",
         "--ks-pass", "file:" + str(password), "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true", "--v4-signing-enabled", "false", "--out", apk, BUILD / "aligned.apk"])
    verification = run(["java", "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", apk], capture=True)
    signer = signer_digest(verification)
    for previous in release_apks():
        prior_verification = run(["java", "-jar", tools / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", previous], capture=True)
        if signer_digest(prior_verification) != signer:
            raise SystemExit("APK signing identity differs from existing release " + previous.name)
    run([tools / "zipalign", "-c", "-P", "16", "4", apk])
    badging = run([tools / "aapt2", "dump", "badging", apk], capture=True)
    manifest = run([tools / "aapt2", "dump", "xmltree", apk, "--file", "AndroidManifest.xml"], capture=True)
    assert "sharedUserId" not in manifest and "android.permission.INTERNET" not in manifest
    assert f"package: name='org.kpfe.restore' versionCode='{VERSION_CODE}' versionName='{VERSION}'" in badging
    assert "minSdkVersion:'31'" in badging and "targetSdkVersion:'35'" in badging
    assert "native-code: 'arm64-v8a'" in badging
    assert "application-label:'Controller FW Restore'" in badging
    assert "extractNativeLibs(0x010104ea)=true" in manifest, "Native extraction must be enabled"
    validate_archive(apk)
    after = {str(p.relative_to(ROOT)): digest(p) for p in build_inputs(sources)}
    if after != before: raise SystemExit("Sources changed while building; rebuild before distributing")
    published = DIST / NAME
    shutil.copyfile(apk, published.with_suffix(".apk.tmp"))
    os.replace(published.with_suffix(".apk.tmp"), published)
    apk = published
    (DIST / "SHA256SUMS").write_text("".join(digest(p) + "  " + p.name + "\n" for p in release_apks()))
    (BUILD / "signature-verification.txt").write_text(verification)
    (BUILD / "apk-badging.txt").write_text(badging)
    (BUILD / "apk-manifest.txt").write_text(manifest)
    report = {"apk": str(apk.relative_to(ROOT)), "bytes": apk.stat().st_size, "sha256": digest(apk),
              "package": "org.kpfe.restore", "display_name": "Controller FW Restore",
              "version": VERSION, "version_code": VERSION_CODE, "firmware_images": list(FIRMWARE_IMAGES),
              "native_library": native_report, "signer_certificate_sha256": signer,
              "min_sdk": 31, "target_sdk": 35,
              "sources": before, "java": run(["java", "--version"], capture=True),
              "android_sdk": str(sdk), "android_build_tools": str(tools),
              "android_jar_sha256": digest(android), "signature_verification": verification,
              "installed_on_device": False, "physical_restore_tested": False,
              "signing_key_directory": ".build/restore-app-signing (private; preserve for updates)"}
    latest_report = DIST / "build-report.json"
    if latest_report.exists():
        previous_report = json.loads(latest_report.read_text())
        previous_name = Path(previous_report["apk"]).stem + ".build-report.json"
        if not (DIST / previous_name).exists():
            shutil.copyfile(latest_report, DIST / previous_name)
    report_text = json.dumps(report, indent=2) + "\n"
    (DIST / (Path(NAME).stem + ".build-report.json")).write_text(report_text)
    latest_report.write_text(report_text)
    print(json.dumps({k: report[k] for k in ("apk", "bytes", "sha256", "version")}, indent=2))


if __name__ == "__main__":
    main()
