#!/usr/bin/env bash
# Prepare a release keystore.
# Priority:
#   1) Secrets: SIGNING_KEY (base64 jks), KEYSTORE_PASSWORD, ALIAS, KEY_PASSWORD
#   2) Generate an ephemeral CI keystore (cert will change every run)
set -euo pipefail

OUT_DIR="${1:-$RUNNER_TEMP/release-keystore}"
mkdir -p "$OUT_DIR"

if [[ -n "${SIGNING_KEY:-}" && -n "${KEYSTORE_PASSWORD:-}" && -n "${ALIAS:-}" && -n "${KEY_PASSWORD:-}" ]]; then
  echo "Using provided signing secrets"
  printf '%s' "$SIGNING_KEY" | base64 -d > "$OUT_DIR/release.jks"
  {
    echo "storeFile=$OUT_DIR/release.jks"
    echo "storePassword=$KEYSTORE_PASSWORD"
    echo "keyAlias=$ALIAS"
    echo "keyPassword=$KEY_PASSWORD"
  } > "$OUT_DIR/keystore.properties"
  if [[ -n "${GITHUB_ENV:-}" ]]; then
    {
      echo "KEYSTORE_PROPERTIES=$OUT_DIR/keystore.properties"
      # app/build.gradle.kts treats SIGNING_KEY as the keystore file path.
      echo "SIGNING_KEY=$OUT_DIR/release.jks"
      echo "KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD"
      echo "ALIAS=$ALIAS"
      echo "KEY_PASSWORD=$KEY_PASSWORD"
    } >> "$GITHUB_ENV"
  else
    echo "KEYSTORE_PROPERTIES=$OUT_DIR/keystore.properties"
  fi
  exit 0
fi

echo "No signing secrets found; generating an ephemeral CI keystore"
STORE_PASS="ci-android"
KEY_PASS="ci-android"
ALIAS_NAME="miuibackgesture"
keytool -genkeypair -v \
  -keystore "$OUT_DIR/release.jks" \
  -alias "$ALIAS_NAME" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
  -dname "CN=MiuiBackGestureHook CI, OU=CI, O=Local, L=Local, S=Local, C=CN"

{
  echo "storeFile=$OUT_DIR/release.jks"
  echo "storePassword=$STORE_PASS"
  echo "keyAlias=$ALIAS_NAME"
  echo "keyPassword=$KEY_PASS"
} > "$OUT_DIR/keystore.properties"

if [[ -n "${GITHUB_ENV:-}" ]]; then
  echo "KEYSTORE_PROPERTIES=$OUT_DIR/keystore.properties" >> "$GITHUB_ENV"
else
  echo "KEYSTORE_PROPERTIES=$OUT_DIR/keystore.properties"
fi
