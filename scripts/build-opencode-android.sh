#!/usr/bin/env bash
# Cross-compile Opencode Go -> Android arm64 (para Desktop WebView)
set -euo pipefail
OPENCODE_REPO="${OPENCODE_REPO:-https://github.com/sst/opencode}"
OPENCODE_DIR="${OPENCODE_DIR:-/tmp/opencode-src}"
OUTPUT_DIR="${OUTPUT_DIR:-$(pwd)/app/src/main/assets}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/android-ndk-r26d}"
API_LEVEL="${API_LEVEL:-23}"
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info(){ echo -e "${GREEN}[INFO]${NC} $*"; }
warn(){ echo -e "${YELLOW}[WARN]${NC} $*"; }
err(){ echo -e "${RED}[ERR]${NC} $*"; }
check_go(){ if ! command -v go &>/dev/null; then err "Go não encontrado"; exit 1; fi; info "Go $(go version)"; }
fetch_source(){
  if [[ -d "$OPENCODE_DIR/.git" ]]; then info "Atualizando $OPENCODE_DIR"; git -C "$OPENCODE_DIR" fetch --depth=1 origin main; git -C "$OPENCODE_DIR" reset --hard origin/main
  else info "Clonando $OPENCODE_REPO"; git clone --depth=1 "$OPENCODE_REPO" "$OPENCODE_DIR"; fi
  info "Commit $(git -C "$OPENCODE_DIR" rev-parse --short HEAD)"
}
build_arch(){
  local GOARCH=$1 CC_TARGET="" OUT=""
  case "$GOARCH" in
    arm64) CC_TARGET="aarch64-linux-android${API_LEVEL}-clang"; OUT="opencode-android-arm64" ;;
    arm) CC_TARGET="armv7a-linux-android${API_LEVEL}-clang"; OUT="opencode-android-arm" ;;
    amd64) CC_TARGET="x86_64-linux-android${API_LEVEL}-clang"; OUT="opencode-android-amd64" ;;
    386) CC_TARGET="i686-linux-android${API_LEVEL}-clang"; OUT="opencode-android-386" ;;
    *) err "arch $GOARCH"; return 1;;
  esac
  info "Build GOOS=android GOARCH=$GOARCH -> $OUT"
  pushd "$OPENCODE_DIR" >/dev/null
  if [[ -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin" ]]; then
    CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/$CC_TARGET"
    if [[ -x "$CC" ]]; then export CC; export CXX="${CC/clang/clang++}"; info "CC $CC"; fi
  fi
  set +e
  info "Tentando CGO_ENABLED=0..."
  env CGO_ENABLED=0 GOOS=android GOARCH="$GOARCH" go build -trimpath -ldflags="-s -w -extldflags=-static" -tags="noplugin,osusergo,netgo" -o "$OUTPUT_DIR/$OUT" ./...
  local EC=$?
  set -e
  if [[ $EC -ne 0 ]]; then
    warn "CGO=0 falhou, tentando CGO=1"
    env CGO_ENABLED=1 GOOS=android GOARCH="$GOARCH" CC="${CC:-}" go build -trimpath -ldflags="-s -w" -o "$OUTPUT_DIR/$OUT" ./...
  fi
  if [[ -f "$OUTPUT_DIR/$OUT" ]]; then info "✅ $OUT $(du -h "$OUTPUT_DIR/$OUT"|cut -f1)"; chmod +x "$OUTPUT_DIR/$OUT"
  else err "Falha $OUT"; popd >/dev/null; return 1; fi
  popd >/dev/null
}
main(){
  check_go; mkdir -p "$OUTPUT_DIR"
  if [[ "${1:-}" == "--local" && -n "${2:-}" ]]; then OPENCODE_DIR="$2"; info "LOCAL $OPENCODE_DIR"; else fetch_source; fi
  local ARCHS=("arm64")
  if [[ "${1:-}" == "--all" ]]; then ARCHS=("arm64" "arm" "amd64" "386"); fi
  if [[ -n "${TARGET_ARCHS:-}" ]]; then IFS=',' read -ra ARCHS <<< "$TARGET_ARCHS"; fi
  info "Output $OUTPUT_DIR Archs ${ARCHS[*]}"
  for a in "${ARCHS[@]}"; do build_arch "$a"; done
  info "✅ Concluído"; ls -lh "$OUTPUT_DIR"/opencode-android-* || true
  if [[ -f "$OUTPUT_DIR/opencode-android-arm64" && ! -f "$OUTPUT_DIR/opencode" ]]; then cp "$OUTPUT_DIR/opencode-android-arm64" "$OUTPUT_DIR/opencode"; info "Copiado como assets/opencode"; ls -lh "$OUTPUT_DIR/opencode"; fi
}
main "$@"
