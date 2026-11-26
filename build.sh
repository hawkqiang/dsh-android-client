#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Enforce pure Termux native toolchain environment (never mix glibc binaries).
export PREFIX="/data/data/com.termux/files/usr"
export HOME="${HOME:-/data/data/com.termux/files/home}"
export PATH="$PREFIX/bin:/system/bin:/system/xbin"
unset LD_LIBRARY_PATH || true

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
MIN_SDK="${MIN_SDK:-26}"
TARGET_SDK="${TARGET_SDK:-35}"

BUILD_DIR="$SCRIPT_DIR/build"
GEN_DIR="$BUILD_DIR/gen"
OBJ_DIR="$BUILD_DIR/obj"
DEX_DIR="$BUILD_DIR/dex"
RES_FLAT_DIR="$BUILD_DIR/res-flat"
DIST_DIR="$SCRIPT_DIR/dist"

KEYSTORE_DIR="$SCRIPT_DIR/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/dsh-client-release.jks"
KEY_ALIAS="dsh-client"
KEY_PASS="${DSH_KEYSTORE_PASS:-dshclient123}"

APK_NAME="${APK_NAME:-dsh-android-client.apk}"
DOWNLOAD_DIR="${DOWNLOAD_DIR:-/storage/emulated/0/Download}"
COPY_TO_DOWNLOAD="${COPY_TO_DOWNLOAD:-1}"
INSTALL_CTL_SCRIPT="${INSTALL_CTL_SCRIPT:-1}"

log() {
  printf '[build] %s\n' "$*"
}

die() {
  printf '[build] ERROR: %s\n' "$*" >&2
  exit 1
}

for tool in aapt aapt2 javac d8 zipalign apksigner keytool; do
  command -v "$tool" >/dev/null 2>&1 || die "Required tool not found in native Termux PATH: $tool"
done
[ -f "$ANDROID_JAR" ] || die "android.jar not found: $ANDROID_JAR"

# Ensure persistent signing keystore exists (preserved across builds for in-place upgrades).
mkdir -p "$KEYSTORE_DIR"
if [ ! -f "$KEYSTORE_FILE" ]; then
  log "Generating persistent signing keystore: $KEYSTORE_FILE"
  keytool -genkeypair \
    -keystore "$KEYSTORE_FILE" \
    -storetype PKCS12 \
    -storepass "$KEY_PASS" \
    -keypass "$KEY_PASS" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=DeepSeek Harness Client, OU=Local, O=DSH, L=Local, ST=Local, C=CN"
else
  log "Reusing persistent signing keystore: $KEYSTORE_FILE"
fi

# Optionally install standalone Termux backend controller without touching existing DSH files.
if [ "$INSTALL_CTL_SCRIPT" = "1" ] && [ -f "$SCRIPT_DIR/scripts/dsh-termux-ctl.sh" ]; then
  mkdir -p "$HOME/.local/bin"
  install -m 0755 "$SCRIPT_DIR/scripts/dsh-termux-ctl.sh" "$HOME/.local/bin/dsh-termux-ctl"
  log "Installed standalone Termux controller: $HOME/.local/bin/dsh-termux-ctl"
fi

rm -rf "$BUILD_DIR"
mkdir -p "$GEN_DIR" "$OBJ_DIR" "$DEX_DIR" "$RES_FLAT_DIR" "$DIST_DIR"

# Step 1: Compile resources with aapt2.
FLAT_FILES=()
if [ -d "$SCRIPT_DIR/res" ] && [ -n "$(find "$SCRIPT_DIR/res" -type f -print -quit)" ]; then
  log "Compiling Android resources with aapt2..."
  aapt2 compile --dir "$SCRIPT_DIR/res" -o "$RES_FLAT_DIR/resources.zip"
  FLAT_FILES+=("$RES_FLAT_DIR/resources.zip")
fi

# Step 2: Link manifest + resources and generate R.java.
log "Linking manifest and resources with aapt2..."
UNALIGNED_APK="$BUILD_DIR/app-unaligned.apk"
LINK_ARGS=(
  -I "$ANDROID_JAR"
  --manifest "$SCRIPT_DIR/AndroidManifest.xml"
  --java "$GEN_DIR"
  --min-sdk-version "$MIN_SDK"
  --target-sdk-version "$TARGET_SDK"
  --auto-add-overlay
  -o "$UNALIGNED_APK"
)
if [ "${#FLAT_FILES[@]}" -gt 0 ]; then
  LINK_ARGS+=("${FLAT_FILES[@]}")
fi
aapt2 link "${LINK_ARGS[@]}"

# Step 3: Compile Java sources (app src + generated R.java) against android-35.
log "Compiling Java sources with javac..."
JAVA_FILES=()
while IFS= read -r -d '' f; do
  JAVA_FILES+=("$f")
done < <(find "$SCRIPT_DIR/src" "$GEN_DIR" -name '*.java' -print0)

[ "${#JAVA_FILES[@]}" -gt 0 ] || die "No Java source files found"

javac \
  -encoding UTF-8 \
  -source 8 \
  -target 8 \
  -Xlint:deprecation \
  -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$OBJ_DIR" \
  "${JAVA_FILES[@]}"

# Step 4: Compile class files to DEX bytecode with d8.
log "Compiling DEX bytecode with d8..."
CLASS_FILES=()
while IFS= read -r -d '' f; do
  CLASS_FILES+=("$f")
done < <(find "$OBJ_DIR" -name '*.class' -print0)

d8 \
  --release \
  --min-api "$MIN_SDK" \
  --lib "$ANDROID_JAR" \
  --output "$DEX_DIR" \
  "${CLASS_FILES[@]}"

# Step 5: Add classes.dex using native aapt (preserves uncompressed resources.arsc for Android 11+).
log "Packaging classes.dex into APK with aapt..."
(
  cd "$DEX_DIR"
  for dex in classes*.dex; do
    aapt add -f "$UNALIGNED_APK" "$dex" >/dev/null
  done
)

# Step 6: Align APK (4-byte alignment required by Android R+/15/16).
ALIGNED_APK="$BUILD_DIR/app-aligned.apk"
log "Aligning APK with zipalign..."
zipalign -f -p 4 "$UNALIGNED_APK" "$ALIGNED_APK"

# Step 7: Sign APK with persistent keystore (v1 + v2 + v3 signatures).
FINAL_APK="$DIST_DIR/$APK_NAME"
log "Signing APK with apksigner..."
apksigner sign \
  --ks "$KEYSTORE_FILE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$FINAL_APK" \
  "$ALIGNED_APK"

# Step 8: Verify alignment, signature, and APK size limit (< 1MB).
log "Verifying APK alignment and signature..."
zipalign -c -p 4 "$FINAL_APK"
apksigner verify --verbose --print-certs "$FINAL_APK"

APK_BYTES="$(wc -c < "$FINAL_APK" | tr -d '[:space:]')"
APK_KB="$(( (APK_BYTES + 1023) / 1024 ))"
log "Built APK: $FINAL_APK (${APK_BYTES} bytes / ~${APK_KB} KB)"

if [ "$APK_BYTES" -ge 1048576 ]; then
  die "APK exceeded 1MB target limit: ${APK_BYTES} bytes"
fi

if [ "$COPY_TO_DOWNLOAD" = "1" ] && [ -d "$DOWNLOAD_DIR" ]; then
  cp -f "$FINAL_APK" "$DOWNLOAD_DIR/$APK_NAME"
  log "Copied APK to: $DOWNLOAD_DIR/$APK_NAME"
fi

log "Build completed successfully."
