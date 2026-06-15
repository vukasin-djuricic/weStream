#!/usr/bin/env bash
#
# Validation cycle: compile everything (src + test) and run the regression checks.
# Single source of truth — run this after every change; CI can call the same thing.
# Exits non-zero if compilation or any check fails.
#
set -euo pipefail
cd "$(dirname "$0")"

OUT=out/test

echo "==> compiling (src + test)"
rm -rf "$OUT"
mkdir -p "$OUT"
javac -d "$OUT" $(find src test -name '*.java')

echo "==> running Kademlia regression checks"
java -cp "$OUT" core.kademlia.KademliaCheck

echo "==> running Transfer regression checks"
java -cp "$OUT" core.transfer.TransferCheck

echo "==> running API regression checks"
java -cp "$OUT" app.api.ApiCheck
