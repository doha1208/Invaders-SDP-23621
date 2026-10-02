#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_build_dir=$(mktemp -d "${TMPDIR:-/tmp}/item-manager-tests.XXXXXX")
trap 'rm -rf "$test_build_dir"' EXIT
find src test -name '*.java' -print > "$test_build_dir/sources.txt"
javac --release 17 -encoding UTF-8 -Xlint:all -d "$test_build_dir" @"$test_build_dir/sources.txt"
java -Djava.awt.headless=true -cp "$test_build_dir" integration.ItemManagerLifecycleTest
java -Djava.awt.headless=true -cp "$test_build_dir" integration.ItemManagerIntegrationTest
