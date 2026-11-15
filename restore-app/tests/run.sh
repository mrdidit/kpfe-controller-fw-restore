#!/bin/sh
# Host-only tests; never connects to a device or opens a controller transport.
set -eu
test_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
app_dir=$(dirname -- "$test_dir")
workspace_dir=$(dirname -- "$app_dir")
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/kpfe-host-tests.XXXXXX")
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM

sh "$app_dir/core/test/run.sh"
set -- "$app_dir"/core/src/org/kpfe/restore/core/*.java \
  "$app_dir/android/src/org/kpfe/restore/RootPolicy.java" \
  "$app_dir/android/src/org/kpfe/restore/ControllerVersionReport.java" \
  "$app_dir/android/src/org/kpfe/restore/StockCommandGuard.java" \
  "$app_dir/android/src/org/kpfe/restore/SessionTrace.java" \
  "$app_dir/android/src/org/kpfe/restore/DeviceProfile.java" \
  "$app_dir/android/src/org/kpfe/restore/FirmwareTarget.java" \
  "$app_dir/android/src/org/kpfe/restore/FitProbePolicy.java" \
  "$app_dir/android/src/org/kpfe/restore/SupportArchive.java" \
  "$app_dir/android/src/org/kpfe/restore/SupportProcess.java" \
  "$app_dir/android/src/org/kpfe/restore/SupportFindings.java" \
  "$test_dir"/*Test.java
if command -v javac >/dev/null 2>&1; then
  javac -source 8 -target 8 -Xlint:all,-options -Werror -d "$build_dir/classes" "$@"
else
  java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options \
    -Werror -d "$build_dir/classes" "$@"
fi
java -cp "$build_dir/classes" org.kpfe.restore.RootPolicyTest
java -cp "$build_dir/classes" org.kpfe.restore.ControllerVersionReportTest
java -cp "$build_dir/classes" org.kpfe.restore.StockCommandGuardTest \
  "$workspace_dir/updater/KPFE-stock/KPFE_KR02_v8.bin" \
  "$workspace_dir/updater/KPFE-stock/KPFE_KR02_v9.bin"
java -cp "$build_dir/classes" org.kpfe.restore.SessionTraceTest
java -cp "$build_dir/classes" org.kpfe.restore.DeviceProfileTest
java -cp "$build_dir/classes" org.kpfe.restore.FirmwareTargetTest \
  "$workspace_dir/updater/Pocket-FIT/Pocket_FIT_supplied.bin" \
  "$workspace_dir/updater/KPFE-stock/KPFE_KR02_v8.bin" \
  "$workspace_dir/updater/KPFE-stock/KPFE_KR02_v9.bin"
java -cp "$build_dir/classes" org.kpfe.restore.FitProbePolicyTest
java -cp "$build_dir/classes" org.kpfe.restore.SupportCaptureTest
java -cp "$build_dir/classes" org.kpfe.restore.SupportFindingsTest
python3 -m unittest discover -s "$test_dir" -p 'test_build_packaging.py' -v
