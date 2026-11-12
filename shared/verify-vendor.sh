#!/bin/sh
# Fail if the vendored shared tree drifted from its pinned manifest.
# Usage: sh shared/verify-vendor.sh   (run from the nc-studio repo root)
set -eu
cd "$(dirname "$0")/.."
sha256sum -c shared/VENDOR_MANIFEST.sha256
echo "vendor OK (pin $(cat shared/VENDOR_SHA.txt))"
