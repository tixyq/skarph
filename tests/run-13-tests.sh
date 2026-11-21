#!/bin/sh
# Copyright (C) 2026 tixyq. MIT License, see LICENSE.
# Compiles the engine and runs every headless test: the network tests (HTTP, UDP client, server) and all the others.
# Usage: tests/run-13-tests.sh [OUT_DIR]     (default /tmp/skarph-out; run it from anywhere)
set -e
cd "$(dirname "$0")/.."
OUT=${1:-/tmp/skarph-out}
sh tests/run-12-tests.sh "$OUT"
javac -nowarn -cp "$OUT/core" -d "$OUT/tests" tests/NetTest.java tests/skarph/core/NetTestHook.java tests/HttpTest.java tests/ServerTest.java examples/RoomServer.java
java -cp "$OUT/core:$OUT/tests" HttpTest 2>&1 | grep -v "^PASS\|^Picked" | tee "$OUT/HttpTest.log"
grep -q "ALL HTTP TESTS PASSED" "$OUT/HttpTest.log" || { echo "FAILED: HttpTest"; exit 1; }
java -cp "$OUT/core:$OUT/tests" NetTest 2>&1 | grep -v "^PASS\|^Picked" | tee "$OUT/NetTest.log"
grep -q "ALL NET TESTS PASSED" "$OUT/NetTest.log" || { echo "FAILED: NetTest"; exit 1; }
java -cp "$OUT/core:$OUT/tests" ServerTest 2>&1 | grep -v "^PASS\|^Picked\|^room server\| joined \| left " | tee "$OUT/ServerTest.log"
grep -q "ALL SERVER TESTS PASSED" "$OUT/ServerTest.log" || { echo "FAILED: ServerTest"; exit 1; }
echo "ALL HEADLESS AND NETWORK TESTS PASSED"
