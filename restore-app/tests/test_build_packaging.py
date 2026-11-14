"""Host-only APK resource/native packaging checks; no app compilation or device I/O."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

SPEC = importlib.util.spec_from_file_location("restore_build", Path(__file__).resolve().parents[1] / "build.py")
build = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build)


class PackagingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.native, cls.provenance = build.native_library()
        cls.sdk, cls.tools, cls.android = build.resolve_android_sdk()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)

    def sample(self, name="unsigned.apk", *, omit_version=None, swap=False, tamper_version=None):
        apk = self.path / name
        with zipfile.ZipFile(apk, "w") as z:
            for image in build.FIRMWARE_IMAGES:
                if image["version"] == omit_version:
                    continue
                source = next(i for i in build.FIRMWARE_IMAGES if i["version"] != image["version"]) if swap else image
                data = build.image_path(source).read_bytes()
                if image["version"] == tamper_version:
                    data = data[:-1] + bytes([data[-1] ^ 1])
                z.writestr("assets/" + image["asset"], data)
            z.writestr("classes.dex", b"host-only packaging fixture")
        build.add_native_library(apk, self.native)
        return apk

    def align(self, apk):
        aligned = apk.with_name("aligned-" + apk.name)
        build.run([self.tools / "zipalign", "-P", "16", "4", apk, aligned])
        return aligned

    def test_exact_vendor_bytes_and_provenance(self):
        self.assertEqual(len(self.native), 9992)
        self.assertEqual(hashlib.sha256(self.native).hexdigest(), build.NATIVE_HASH)
        self.assertEqual(self.provenance["elf_load_segment_alignments"], [4096, 4096])
        self.assertEqual(self.provenance["source_file"], "restore-app/vendor/arm64-v8a/libserial_port.so")
        build.validate_archive(self.align(self.sample()))

    def test_unaligned_library_rejected(self):
        with self.assertRaisesRegex(ValueError, "aligned"):
            build.validate_archive(self.sample())

    def test_missing_target_rejected(self):
        for version in ("8", "9"):
            with self.subTest(version=version):
                with self.assertRaisesRegex(ValueError, "asset list"):
                    build.validate_archive(self.align(self.sample(name="missing-" + version + ".apk", omit_version=version)))

    def test_swapped_target_payloads_rejected(self):
        with self.assertRaisesRegex(ValueError, "firmware mismatch"):
            build.validate_archive(self.align(self.sample(swap=True)))

    def test_each_tampered_target_rejected(self):
        for version in ("8", "9"):
            with self.subTest(version=version):
                with self.assertRaisesRegex(ValueError, "firmware mismatch"):
                    build.validate_archive(self.align(self.sample(name="tampered-" + version + ".apk", tamper_version=version)))

    def test_extra_abi_rejected(self):
        apk = self.sample()
        with zipfile.ZipFile(apk, "a") as z:
            z.writestr("lib/x86/libserial_port.so", self.native)
        with self.assertRaisesRegex(ValueError, "ABI"):
            build.validate_archive(self.align(apk))

    def test_tampered_library_rejected(self):
        changed = bytearray(self.native)
        changed[-1] ^= 1
        with zipfile.ZipFile(self.path / "changed.apk", "w") as z:
            z.writestr(build.NATIVE_ENTRY, changed)
        with self.assertRaisesRegex(ValueError, "mismatch"):
            build.validate_archive(self.align(self.path / "changed.apk"))

    def test_changed_vendor_library_rejected(self):
        source = self.path / "changed.so"
        source.write_bytes(self.native[:-1] + bytes([self.native[-1] ^ 1]))
        with mock.patch.object(build, "NATIVE_LIBRARY", source):
            with self.assertRaisesRegex(ValueError, "Native library mismatch"):
                build.native_library()

    def test_native_source_does_not_require_original_apk(self):
        with mock.patch.object(build.zipfile, "ZipFile", side_effect=AssertionError("No source APK needed")):
            native, _ = build.native_library()
        self.assertEqual(native, self.native)
        inputs = build.build_inputs([])
        self.assertIn(build.NATIVE_LIBRARY, inputs)
        self.assertNotIn(build.ROOT / build.NATIVE_APK_ORIGIN, inputs)

    def test_duplicate_add_rejected(self):
        with self.assertRaisesRegex(ValueError, "already packaged"):
            build.add_native_library(self.sample(), self.native)

    def test_compiled_manifest_version_sdk_and_native_extraction(self):
        resources = self.path / "resources.zip"
        apk = self.path / "manifest.apk"
        build.run([self.tools / "aapt2", "compile", "--dir", build.APP / "android/res", "-o", resources])
        build.run([self.tools / "aapt2", "link", "-I", self.android, "--manifest",
                   build.APP / "android/AndroidManifest.xml", resources, "-o", apk])
        build.add_native_library(apk, self.native)
        badging = build.run([self.tools / "aapt2", "dump", "badging", apk], capture=True)
        manifest = build.run([self.tools / "aapt2", "dump", "xmltree", apk, "--file", "AndroidManifest.xml"], capture=True)
        self.assertIn(f"package: name='org.kpfe.restore' versionCode='{build.VERSION_CODE}' versionName='{build.VERSION}'", badging)
        self.assertIn("minSdkVersion:'31'", badging)
        self.assertIn("targetSdkVersion:'35'", badging)
        self.assertIn("native-code: 'arm64-v8a'", badging)
        self.assertIn("application-label:'Controller FW Restore'", badging)
        self.assertIn("extractNativeLibs(0x010104ea)=true", manifest)
        self.assertNotIn("sharedUserId", manifest)


class SdkDiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)

    def sdk(self, path, *, version="35.0.1", legacy=False):
        android = path / ("android-35/android.jar" if legacy else "platforms/android-35/android.jar")
        android.parent.mkdir(parents=True, exist_ok=True)
        android.touch()
        tools = path / ("android-15" if legacy else "build-tools/" + version)
        for name in ("aapt2", "zipalign", "lib/d8.jar", "lib/apksigner.jar"):
            target = tools / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.touch()
        return path, tools, android

    def resolve(self, env):
        return build.resolve_android_sdk(env, self.path / "workspace", self.path / "home")

    def test_android_home_conventional_layout(self):
        expected = self.sdk(self.path / "sdk")
        self.assertEqual(self.resolve({"ANDROID_HOME": str(expected[0])}), expected)

    def test_sdk_root_and_matching_environment_supported(self):
        expected = self.sdk(self.path / "sdk")
        self.assertEqual(self.resolve({"ANDROID_SDK_ROOT": str(expected[0])}), expected)
        self.assertEqual(self.resolve({"ANDROID_HOME": str(expected[0]),
                                       "ANDROID_SDK_ROOT": str(expected[0])}), expected)

    def test_conflicting_environment_rejected(self):
        with self.assertRaisesRegex(ValueError, "same SDK"):
            self.resolve({"ANDROID_HOME": str(self.path / "one"),
                          "ANDROID_SDK_ROOT": str(self.path / "two")})

    def test_workspace_extracted_layout_supported(self):
        expected = self.sdk(self.path / "workspace/.tools/android-sdk", legacy=True)
        self.assertEqual(self.resolve({}), expected)

    def test_normal_home_sdk_fallback_supported(self):
        expected = self.sdk(self.path / "home/Android/Sdk")
        self.assertEqual(self.resolve({}), expected)

    def test_explicit_missing_sdk_does_not_silently_fallback(self):
        self.sdk(self.path / "workspace/.tools/android-sdk", legacy=True)
        with self.assertRaisesRegex(FileNotFoundError, "Install Android SDK Platform 35"):
            self.resolve({"ANDROID_HOME": str(self.path / "missing")})

    def test_highest_complete_stable_tools_selected_numerically(self):
        path = self.path / "sdk"
        self.sdk(path, version="35.0.1")
        self.sdk(path, version="35.9.0")
        expected = self.sdk(path, version="35.10.0")
        self.sdk(path, version="99.0.0-rc1")
        incomplete = self.sdk(path, version="36.0.0")
        (incomplete[1] / "lib/d8.jar").unlink()
        self.assertEqual(self.resolve({"ANDROID_HOME": str(path)}), expected)

    def test_api35_and_minimum_tools_required(self):
        path, tools, android = self.sdk(self.path / "sdk", version="34.0.0")
        with self.assertRaisesRegex(FileNotFoundError, "Build Tools"):
            self.resolve({"ANDROID_HOME": str(path)})
        self.sdk(path)
        android.unlink()
        with self.assertRaisesRegex(FileNotFoundError, "API 35 android.jar missing"):
            self.resolve({"ANDROID_HOME": str(path)})


if __name__ == "__main__":
    unittest.main()
