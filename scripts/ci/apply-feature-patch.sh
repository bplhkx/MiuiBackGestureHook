#!/usr/bin/env bash
# Apply the local feature patches on top of an upstream base.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PATCH_FILE="${1:-$REPO_ROOT/patches/aosp-gesture-restoration-switch.patch}"

if [[ ! -f "$PATCH_FILE" ]]; then
  echo "Missing patch: $PATCH_FILE" >&2
  exit 1
fi

if git -C "$REPO_ROOT" apply --check "$PATCH_FILE"; then
  git -C "$REPO_ROOT" apply "$PATCH_FILE"
  echo "Applied feature patch: $PATCH_FILE"
  exit 0
fi

if git -C "$REPO_ROOT" apply --check --3way "$PATCH_FILE"; then
  git -C "$REPO_ROOT" apply --3way "$PATCH_FILE"
  echo "Applied feature patch with 3-way merge: $PATCH_FILE"
  exit 0
fi

echo "Feature patch failed to apply. Resolve conflicts manually." >&2
echo "  patch: $PATCH_FILE" >&2
exit 1
