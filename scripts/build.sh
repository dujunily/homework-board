#!/usr/bin/env bash
# 作业板 · 一键构建（macOS / Linux）
#
# 做的事：找到 JDK 17+ 和 Android SDK → 写 local.properties → 打两个 release 包
#         → 按版本号改名放进 dist/；带 web 参数时顺带把网页端打成一个可直接托管的目录。
#
# 用法：
#   ./scripts/build.sh            # 电视端 + 手机端 + 网页端
#   ./scripts/build.sh tv         # 只打电视端
#   ./scripts/build.sh phone      # 只打手机端
#   ./scripts/build.sh web        # 只打包网页端（不需要 JDK/SDK）
#   ./scripts/build.sh apk        # 只打两个 APK，不碰网页端
#
# Windows 用 scripts\build.ps1（或直接双击 scripts\build.cmd）。

set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
DIST="$ROOT/dist"

TARGET="${1:-all}"

say() { printf '%s\n' "$*"; }
die() { printf '\n✗ %s\n' "$*" >&2; exit 1; }
hr() { printf '%s\n' "────────────────────────────────────────────"; }

# ---------- 版本号：从各模块的 build.gradle.kts 里读，避免手抄 ----------
module_version() {
  sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' "$1/build.gradle.kts" 2>/dev/null | head -1
}

# ---------- JDK ----------
# 只认主版本号 ≥ 17 的（AGP 8.x 要求）；shell 里 JAVA_HOME 常常还指着 JDK 8
java_major() {
  local v
  v=$("$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')
  [ -z "$v" ] && { echo 0; return; }
  case "$v" in
    1.*) v=${v#1.} ;;
  esac
  echo "${v%%[!0-9]*}"
}

# Gradle 8.10 跑不动 JDK 24+，所以不是「越大越好」，按稳的顺序挑
jdk_rank() {
  case "$1" in
    21)         echo 100 ;;
    17)         echo 95 ;;
    22)         echo 80 ;;
    23)         echo 70 ;;
    18|19|20)   echo 60 ;;
    *)          echo 10 ;;
  esac
}

find_jdk() {
  local cand java major rank best="" best_rank=-1 best_major=0

  for cand in \
    "${JAVA_HOME:-}" \
    $( [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 21 2>/dev/null ) \
    $( [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 17 2>/dev/null ) \
    /Library/Java/JavaVirtualMachines/*/Contents/Home \
    /Applications/Android\ Studio.app/Contents/jbr/Contents/Home \
    "$HOME"/.sdkman/candidates/java/* \
    /usr/lib/jvm/* \
    /opt/java/* ; do
    [ -n "$cand" ] && [ -d "$cand" ] || continue
    java="$cand/bin/java"
    [ -x "$java" ] || continue
    major=$(java_major "$java")
    [ "$major" -ge 17 ] 2>/dev/null || continue
    rank=$(jdk_rank "$major")
    if [ "$rank" -gt "$best_rank" ] || { [ "$rank" -eq "$best_rank" ] && [ "$major" -gt "$best_major" ]; }; then
      best_rank=$rank
      best_major=$major
      best=$cand
    fi
  done

  [ -n "$best" ] && printf '%s\n' "$best"
}

# ---------- Android SDK ----------
find_sdk() {
  local cand
  for cand in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk" \
    "$HOME/android-sdk" \
    /usr/local/share/android-sdk \
    /usr/lib/android-sdk \
    /opt/android-sdk ; do
    [ -n "$cand" ] && [ -d "$cand/platforms" ] && printf '%s\n' "$cand" && return 0
  done

  # 已经有 local.properties 就认它写的那个
  if [ -f "$ROOT/local.properties" ]; then
    cand=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1)
    [ -n "$cand" ] && [ -d "$cand/platforms" ] && printf '%s\n' "$cand" && return 0
  fi

  return 1
}

prepare_env() {
  local jdk sdk

  jdk=$(find_jdk)
  [ -z "$jdk" ] && die "没找到 JDK 17 以上。
  macOS：brew install --cask temurin@21   或装 Android Studio（自带 JBR）
  Linux：sudo apt install openjdk-17-jdk
  也可以用 JAVA_HOME 指过去：export JAVA_HOME=/path/to/jdk-21"
  export JAVA_HOME="$jdk"

  sdk=$(find_sdk)
  [ -z "$sdk" ] && die "没找到 Android SDK（目录下要有 platforms/）。
  装 Android Studio 后在 SDK Manager 里勾一个 Android 15 (API 35)。
  或者手动指：export ANDROID_HOME=/path/to/Android/Sdk"
  export ANDROID_HOME="$sdk"
  export ANDROID_SDK_ROOT="$sdk"

  # local.properties 不进仓库，每人本地一份；内容变了就重写
  local want="sdk.dir=$sdk"
  if [ "$(cat "$ROOT/local.properties" 2>/dev/null)" != "$want" ]; then
    printf '%s\n' "$want" > "$ROOT/local.properties"
  fi

  say "  JDK   $JAVA_HOME ($(java_major "$JAVA_HOME/bin/java"))"
  say "  SDK   $ANDROID_HOME"
}

# ---------- 构建 ----------
gradle() {
  if [ -x "$ROOT/gradlew" ]; then
    ( cd "$ROOT" && ./gradlew "$@" )
  else
    ( cd "$ROOT" && sh gradlew "$@" )
  fi
}

build_apks() {
  local modules=()
  case "$TARGET" in
    tv)    modules=(tvapp) ;;
    phone) modules=(phoneapp) ;;
    *)     modules=(tvapp phoneapp) ;;
  esac

  hr
  say " 构建 APK：${modules[*]}"
  prepare_env

  local tasks=()
  local m
  for m in "${modules[@]}"; do tasks+=(":$m:assembleRelease"); done

  hr
  gradle "${tasks[@]}" || die "Gradle 构建失败，上面的报错就是原因。"

  mkdir -p "$DIST"
  local label ver apk target
  for m in "${modules[@]}"; do
    case "$m" in
      tvapp)    label="TV" ;;
      phoneapp) label="手机" ;;
    esac
    ver=$(module_version "$ROOT/$m")
    [ -z "$ver" ] && ver="unknown"

    apk="$ROOT/$m/build/outputs/apk/release/$m-release.apk"
    if [ ! -f "$apk" ]; then
      apk=$(find "$ROOT/$m/build/outputs/apk/release" -name '*.apk' 2>/dev/null | head -1)
    fi
    [ -f "$apk" ] || die "构建完成了但没找到 $m 的 APK，去 $m/build/outputs/apk/ 看看。"

    target="$DIST/作业板-$label-$ver.apk"
    cp -f "$apk" "$target"
    say "  ✓ $target  ($(ls -lh "$target" | awk '{print $5}'))"
  done
}

package_web() {
  hr
  say " 打包网页端"
  local out="$DIST/网页版"
  rm -rf "$out"
  mkdir -p "$out"

  cp "$ROOT/webapp/index.html" "$out/"
  cp "$ROOT/webapp/serve.py" "$out/"
  cp "$ROOT/webapp/serve.ps1" "$out/"
  cp "$ROOT/webapp/start.sh" "$out/"
  cp "$ROOT/webapp/启动-网页版.command" "$out/"
  cp "$ROOT/webapp/启动-网页版.bat" "$out/"
  cp "$ROOT/webapp/README.md" "$out/使用说明.md" 2>/dev/null || true

  chmod +x "$out/start.sh" "$out/启动-网页版.command" 2>/dev/null || true

  say "  ✓ $out/"

  # 用 python3 打 zip：中文名会带上 UTF-8 标志位（Windows 解压不乱码），
  # 同时保留可执行位（macOS 解压后 .command 还能双击）。
  local zipfile="$DIST/作业板-网页版.zip"
  rm -f "$zipfile"
  if command -v python3 >/dev/null 2>&1; then
    ( cd "$DIST" && python3 - "$zipfile" <<'PY'
import os, sys, zipfile
out, root = sys.argv[1], "网页版"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr(root + "/", b"")
    for dirpath, _, files in os.walk(root):
        for f in sorted(files):
            full = os.path.join(dirpath, f)
            z.write(full, full.replace(os.sep, "/"))
print("  ✓ %s" % out)
PY
    )
  elif command -v zip >/dev/null 2>&1; then
    ( cd "$DIST" && zip -qr "作业板-网页版.zip" "网页版" ) && say "  ✓ $zipfile"
  else
    say "  （没有 python3 也没有 zip，跳过压缩，直接拷 dist/网页版/ 就行）"
  fi
}

# ---------- 入口 ----------
case "$TARGET" in
  all)   build_apks; package_web ;;
  apk)   build_apks ;;
  tv|phone) build_apks ;;
  web)   package_web ;;
  -h|--help|help)
    cat <<'EOF'
用法：./scripts/build.sh [all|apk|tv|phone|web]

  all    电视端 + 手机端 + 网页端（默认）
  apk    只打两个 release APK
  tv     只打电视端
  phone  只打手机端
  web    只打包网页端（不需要 JDK / Android SDK）

产物统一放在 dist/。
EOF
    exit 0 ;;
  *)
    die "不认识的参数：$TARGET（可用：all / apk / tv / phone / web）" ;;
esac

hr
say " 完成。产物在 $DIST/"
hr
