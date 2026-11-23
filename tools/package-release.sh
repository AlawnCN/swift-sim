#!/usr/bin/env bash
# Build a deployment tar.gz on a machine that has Maven and JDK.
# The machine that later runs the tar.gz does not need Maven, Git, or a source checkout.
#
#   tools/package-release.sh
#       linux x86_64 only: download Temurin 21 JRE and produce
#       dist/swift-sim-<version>-linux-x86_64.tar.gz
#       (bundled runtime; this is the standalone package)
#
#   tools/package-release.sh --slim
#       dist/swift-sim-<version>-slim.tar.gz
#       no Java inside. Requires Java 21 on the target. Not a standalone package.
#
#   tools/package-release.sh --runtime-dir /path/to/extracted-jre
#       bundle that JRE instead of downloading one. Use this for linux aarch64 or a JRE
#       you unpacked yourself. The script records os/arch from `uname` of THIS builder
#       only when it downloads; with --runtime-dir it records the java binary's reported
#       version and you must pass --os and --arch to label the archive.
#
#   --os linux --arch aarch64     labels used with --runtime-dir (default: this machine)
#   --skip-build                  use the existing target/swift-sim-*.jar
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

SLIM=0
SKIP_BUILD=0
RUNTIME_DIR=""
OS_NAME=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH_NAME=$(uname -m)
case "$ARCH_NAME" in
  x86_64|amd64) ARCH_NAME=x86_64 ;;
  aarch64|arm64) ARCH_NAME=aarch64 ;;
esac

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slim) SLIM=1; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --runtime-dir) RUNTIME_DIR=${2:?}; shift 2 ;;
    --os) OS_NAME=${2:?}; shift 2 ;;
    --arch) ARCH_NAME=${2:?}; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ "$SLIM" -eq 1 && -n "$RUNTIME_DIR" ]]; then
  echo "--slim and --runtime-dir cannot be used together" >&2
  exit 2
fi

VERSION=$(awk '
  /<artifactId>swift-sim<\/artifactId>/ { found=1; next }
  found && /<version>/ {
    gsub(/[[:space:]]*<version>|<\/version>.*/, "", $0)
    print
    exit
  }
' pom.xml)
[[ -n "$VERSION" ]] || { echo "cannot read version from pom.xml" >&2; exit 1; }

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  mvn -B -DskipTests package
fi
JAR=$(ls -1 target/swift-sim-*.jar | grep -v '\.original$' | head -n 1)
[[ -n "$JAR" && -f "$JAR" ]] || { echo "missing target/swift-sim-*.jar" >&2; exit 1; }

STAGE=$(mktemp -d "${TMPDIR:-/tmp}/swift-sim-stage.XXXXXX")
cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT
DEST="$STAGE/swift-sim"
mkdir -p "$DEST/lib" "$DEST/config" "$DEST/samples" "$DEST/docs"

cp "$JAR" "$DEST/lib/swift-sim.jar"
cp "$ROOT/run.sh" "$ROOT/stop.sh" "$DEST/"
chmod 755 "$DEST/run.sh" "$DEST/stop.sh"
cp "$ROOT/packaging/使用说明.txt" "$DEST/使用说明.txt"
# Runtime config and samples. Never copy secrets, data, logs, or a local database.
tar -C "$ROOT" -cf - \
  --exclude='config/secrets' \
  --exclude='config/secrets/**' \
  config samples docs/file-transport-zh.md docs/ai \
  | tar -C "$DEST" -xf -
rm -rf "$DEST/config/secrets"

JAVA_MODE=requires-java-21
JAVA_VERSION=""
LABEL_OS=$OS_NAME
LABEL_ARCH=$ARCH_NAME
NAME_SUFFIX="slim"

if [[ "$SLIM" -eq 0 ]]; then
  JRE_SRC=""
  if [[ -n "$RUNTIME_DIR" ]]; then
    [[ -x "$RUNTIME_DIR/bin/java" ]] || { echo "--runtime-dir has no bin/java: $RUNTIME_DIR" >&2; exit 1; }
    JRE_SRC=$RUNTIME_DIR
  else
    if [[ "$OS_NAME" != "linux" || "$ARCH_NAME" != "x86_64" ]]; then
      echo "Refusing to download a runtime for $OS_NAME/$ARCH_NAME from this builder." >&2
      echo "The automatic download is only the Linux x86_64 Temurin 21 JRE, so the archive matches a linux x86_64 server." >&2
      echo "For linux aarch64 or macOS, unpack a Java 21 JRE for that OS and rerun with:" >&2
      echo "  tools/package-release.sh --runtime-dir /path/to/jre --os <os> --arch <arch>" >&2
      echo "Or build the explicitly slim package (needs Java 21 on the target): tools/package-release.sh --slim" >&2
      exit 1
    fi
    mkdir -p "$ROOT/build/jre-cache"
    CACHE="$ROOT/build/jre-cache/temurin-21-jre-linux-x64.tar.gz"
    if [[ ! -s "$CACHE" ]]; then
      URL="https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse?project=jdk"
      echo "downloading Temurin 21 JRE (linux x64) ..."
      curl -fL --retry 3 -o "$CACHE.partial" "$URL"
      mv "$CACHE.partial" "$CACHE"
    fi
    JRE_UNPACK=$(mktemp -d "${TMPDIR:-/tmp}/swift-sim-jre.XXXXXX")
    tar -C "$JRE_UNPACK" -xzf "$CACHE"
    JRE_SRC=$(find "$JRE_UNPACK" -type f -path '*/bin/java' -print | head -n 1)
    JRE_SRC=$(dirname "$(dirname "$JRE_SRC")")
  fi
  mkdir -p "$DEST/runtime"
  cp -a "$JRE_SRC"/. "$DEST/runtime/"
  if [[ -n "${JRE_UNPACK:-}" ]]; then rm -rf "$JRE_UNPACK"; fi
  [[ -x "$DEST/runtime/bin/java" ]] || { echo "bundled runtime has no bin/java" >&2; exit 1; }
  # Drop a foreign-arch binary if the provided runtime cannot even start here.
  # When cross-packaging (--runtime-dir for another arch) java -version will fail; that is expected
  # only if the user labeled a different --os/--arch. We still require the binary to be present.
  if JAVA_VERSION_OUT=$("$DEST/runtime/bin/java" -version 2>&1); then
    JAVA_VERSION=$(printf '%s\n' "$JAVA_VERSION_OUT" | awk -F\" '/version/ { print $2; exit }')
    case "$JAVA_VERSION" in
      21.*) ;;
      *) echo "runtime is Java $JAVA_VERSION, need 21" >&2; exit 1 ;;
    esac
  else
    if [[ -z "$RUNTIME_DIR" ]]; then
      echo "downloaded runtime failed to execute on this machine:" >&2
      printf '%s\n' "$JAVA_VERSION_OUT" >&2
      exit 1
    fi
    echo "warning: bundled java did not run on this builder; archive is labeled $LABEL_OS/$LABEL_ARCH from your flags" >&2
    JAVA_VERSION=21-unverified-on-builder
  fi
  JAVA_MODE=bundled
  NAME_SUFFIX="${LABEL_OS}-${LABEL_ARCH}"
fi

cat > "$DEST/PACKAGE.txt" <<EOF
product=swift-sim
version=$VERSION
os=$LABEL_OS
arch=$LABEL_ARCH
java=$JAVA_MODE
java-version=${JAVA_VERSION:-external}
bind-default=0.0.0.0
EOF

if [[ -e "$DEST/config/secrets" ]] || [[ -e "$DEST/data" ]] || [[ -e "$DEST/target" ]]; then
  echo "refusing to ship secrets, data, or build output inside the archive" >&2
  exit 1
fi
mkdir -p "$ROOT/dist"
OUT="$ROOT/dist/swift-sim-${VERSION}-${NAME_SUFFIX}.tar.gz"
tar -C "$STAGE" -czf "$OUT" swift-sim
(
  cd "$ROOT/dist"
  sha256sum "$(basename "$OUT")" | tee "$(basename "$OUT").sha256"
)
echo "wrote $OUT"
if [[ "$JAVA_MODE" == "bundled" ]]; then
  echo "standalone package: $LABEL_OS $LABEL_ARCH, Temurin/Java ${JAVA_VERSION}"
else
  echo "SLIM package: Java 21 is NOT included. Do not describe it as standalone."
fi
