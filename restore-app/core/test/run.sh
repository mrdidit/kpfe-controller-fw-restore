#!/bin/sh
set -eu
test_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
core_dir=$(dirname -- "$test_dir")
workspace_dir=$(dirname -- "$(dirname -- "$core_dir")")
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/kpfe-core-tests.XXXXXX")
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM
if command -v javac >/dev/null 2>&1; then
  javac -source 8 -target 8 -Xlint:all,-options -Werror -d "$build_dir/classes" \
    "$core_dir"/src/org/kpfe/restore/core/*.java "$test_dir"/org/kpfe/restore/core/*.java
else
  java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:all,-options -Werror \
    -d "$build_dir/classes" "$core_dir"/src/org/kpfe/restore/core/*.java \
    "$test_dir"/org/kpfe/restore/core/*.java
fi
java -cp "$build_dir/classes" org.kpfe.restore.core.CoreTests \
  "$workspace_dir/updater/KPFE-stock/KPFE_KR02_v8.bin"
java -cp "$build_dir/classes" org.kpfe.restore.core.DualImageTests \
  "$workspace_dir/updater/KPFE-stock/KPFE_KR02_v8.bin" \
  "$workspace_dir/updater/KPFE-stock/KPFE_KR02_v9.bin"
java -cp "$build_dir/classes" org.kpfe.restore.core.FitUsbTests \
  "$workspace_dir/updater/Pocket-FIT/Pocket_FIT_supplied.bin" \
  "$workspace_dir/updater/KPFE-stock/KPFE_KR02_v8.bin" \
  "$workspace_dir/updater/KPFE-stock/KPFE_KR02_v9.bin"
