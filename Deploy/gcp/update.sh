#!/usr/bin/env bash
# Updates the server to the latest ConnectQuick code and re-applies the configuration.
#   cd ~/ConnectQuick && sudo bash Deploy/gcp/update.sh
set -euo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
git -C "$REPO_DIR" pull --ff-only
exec bash "$REPO_DIR/Deploy/gcp/setup.sh"
