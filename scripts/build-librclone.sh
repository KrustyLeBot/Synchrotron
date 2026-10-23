#!/usr/bin/env bash
# Builds app/libs/librclone.aar from rclone-bridge/ (rclone version pinned in rclone-bridge/go.mod).
# Needs: Go (see go.mod), Android NDK (ANDROID_NDK_HOME), gomobile.
set -euo pipefail
cd "$(dirname "$0")/../rclone-bridge"

MOBILE_VERSION="$(go list -m -f '{{.Version}}' golang.org/x/mobile)"
go install "golang.org/x/mobile/cmd/gomobile@${MOBILE_VERSION}"
go install "golang.org/x/mobile/cmd/gobind@${MOBILE_VERSION}"
export PATH="$(go env GOPATH)/bin:$PATH"
gomobile init

mkdir -p ../app/libs
gomobile bind -v \
  -target=android/arm64 -androidapi 26 \
  -javapkg=app.synchrotron.rclone \
  -trimpath -ldflags="-s -w" \
  -o ../app/libs/librclone.aar ./bridge
ls -lh ../app/libs/librclone.aar
