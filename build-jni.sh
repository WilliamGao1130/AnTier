#!/usr/bin/env bash
set -euo pipefail

# AnTier JNI 构建脚本
# 从 EasyTier main 分支源码构建 libeasytier_android_jni.so，并拷贝到 app/src/main/jniLibs/<abi>/。
#
# 用法:
#   ./build-jni.sh                                        # 默认只构建 arm64-v8a
#   ABIS="arm64-v8a armeabi-v7a x86 x86_64" ./build-jni.sh
#   EASYTIER_DIR=/path/to/EasyTier ./build-jni.sh         # 复用已有源码
#
# 前置要求: Rust 1.95 (rustup)、protoc、Android NDK、cargo-ndk（脚本自动安装）

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
ABIS="${ABIS:-arm64-v8a}"
EASYTIER_DIR="${EASYTIER_DIR:-$PROJECT_ROOT/.third_party/easytier}"

declare -A TARGET_MAP
TARGET_MAP["arm64-v8a"]="aarch64-linux-android"
TARGET_MAP["armeabi-v7a"]="armv7-linux-androideabi"
TARGET_MAP["x86"]="i686-linux-android"
TARGET_MAP["x86_64"]="x86_64-linux-android"

fail() {
    echo "错误: $*" >&2
    exit 1
}

command -v rustc >/dev/null 2>&1 || fail "未找到 rustc，请先安装 Rust"
command -v cargo >/dev/null 2>&1 || fail "未找到 cargo"
command -v protoc >/dev/null 2>&1 || fail "未找到 protoc（easytier-proto 构建需要）"
command -v git >/dev/null 2>&1 || fail "未找到 git"

if ! cargo ndk --version >/dev/null 2>&1; then
    echo "==> 安装 cargo-ndk..."
    cargo install cargo-ndk
fi

if [ ! -d "$EASYTIER_DIR/.git" ]; then
    mkdir -p "$(dirname "$EASYTIER_DIR")"
    echo "==> 克隆 EasyTier main 分支 -> $EASYTIER_DIR"
    git clone --depth 1 https://github.com/EasyTier/EasyTier.git "$EASYTIER_DIR"
fi

JNI_DIR="$EASYTIER_DIR/easytier-contrib/easytier-android-jni"
FFI_DIR="$EASYTIER_DIR/easytier-contrib/easytier-ffi"
[ -d "$JNI_DIR" ] || fail "easytier-android-jni 不存在: $JNI_DIR（请确认 EasyTier main 分支包含该模块）"

for abi in $ABIS; do
    rust_target="${TARGET_MAP[$abi]:-}"
    [ -n "$rust_target" ] || fail "未知 ABI: $abi（支持: arm64-v8a armeabi-v7a x86 x86_64）"

    rustup target list --installed | grep -q "$rust_target" || rustup target add "$rust_target"

    echo "==> 构建 $abi ($rust_target)"
    (cd "$FFI_DIR" && cargo ndk -t "$abi" build --release)
    (cd "$JNI_DIR" && cargo ndk -t "$abi" build --release)

    OUT_DIR="$PROJECT_ROOT/app/src/main/jniLibs/$abi"
    mkdir -p "$OUT_DIR"
    cp "$EASYTIER_DIR/target/$rust_target/release/libeasytier_android_jni.so" "$OUT_DIR/"
    echo "==> 已复制到 $OUT_DIR/"
done

echo "==> 完成。运行 ./gradlew assembleDebug 构建 APK"
