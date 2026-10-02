#!/bin/bash
#
# ScreenCapCoze 调试脚本
# 在 Android Studio Terminal 或独立终端中使用
#
# 使用方法:
#   ./debug.sh build     - 构建 debug APK
#   ./debug.sh install   - 安装到连接的设备
#   ./debug.sh run       - 构建 + 安装 + 启动 App
#   ./debug.sh log       - 查看 App 日志 (Ctrl+C 退出)
#   ./debug.sh log-cap   - 只看截图服务日志
#   ./debug.sh log-up    - 只看上传日志
#   ./debug.sh log-tile  - 只看 Tile 服务日志
#   ./debug.sh tile      - 模拟 Tile 点击 (触发截图流程)
#   ./debug.sh clear     - 清除 App 数据 (重置配置)
#   ./debug.sh uninstall - 卸载 App
#   ./debug.sh devices   - 查看连接的设备
#   ./debug.sh help      - 显示帮助

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.personal.screencapcoze"
MAIN_ACTIVITY="$PACKAGE.MainActivity"

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

cmd_build() {
    info "构建 debug APK..."
    cd "$PROJECT_DIR"
    ./gradlew assembleDebug
    if [ -f "$APK_PATH" ]; then
        info "APK 已生成: $APK_PATH"
        ls -lh "$APK_PATH"
    else
        error "APK 构建失败"
        exit 1
    fi
}

cmd_install() {
    if [ ! -f "$APK_PATH" ]; then
        warn "APK 不存在，先构建..."
        cmd_build
    fi
    info "检查连接设备..."
    adb devices
    info "安装 APK 到设备..."
    adb install -r "$APK_PATH"
    info "安装完成"
}

cmd_run() {
    cmd_build
    cmd_install
    info "启动 App..."
    adb shell am start -n "$PACKAGE/$MAIN_ACTIVITY"
    info "App 已启动"
}

cmd_log() {
    info "查看 App 全部日志 (Ctrl+C 退出)..."
    adb logcat -s \
        ScreenCapTile:D \
        ScreenCapture:D \
        CozeUploader:D \
        MainActivity:D
}

cmd_log_capture() {
    info "查看截图服务日志 (Ctrl+C 退出)..."
    adb logcat -s ScreenCapture:D
}

cmd_log_upload() {
    info "查看上传日志 (Ctrl+C 退出)..."
    adb logcat -s CozeUploader:D
}

cmd_log_tile() {
    info "查看 Tile 服务日志 (Ctrl+C 退出)..."
    adb logcat -s ScreenCapTile:D
}

cmd_log_all() {
    info "查看所有 App 日志 (Ctrl+C 退出)..."
    adb logcat --pid=$(adb shell pidof $PACKAGE 2>/dev/null || echo "0")
}

cmd_tile_click() {
    info "模拟 Tile 点击..."
    adb shell am start -n "$PACKAGE/$MAIN_ACTIVITY" -a "$PACKAGE.FROM_TILE"
    info "已触发截图流程，查看日志: ./debug.sh log"
}

cmd_clear() {
    warn "清除 App 数据 (配置会被重置)..."
    adb shell pm clear "$PACKAGE"
    info "数据已清除"
}

cmd_uninstall() {
    warn "卸载 App..."
    adb shell pm uninstall "$PACKAGE"
    info "App 已卸载"
}

cmd_devices() {
    info "连接的设备:"
    adb devices -l
}

cmd_help() {
    echo ""
    echo "ScreenCapCoze 调试脚本"
    echo "======================"
    echo ""
    echo "命令:"
    echo "  build      构建 debug APK"
    echo "  install    安装到连接的设备"
    echo "  run        构建 + 安装 + 启动 App"
    echo "  log        查看 App 日志 (Ctrl+C 退出)"
    echo "  log-cap    只看截图服务日志"
    echo "  log-up     只看上传日志"
    echo "  log-tile   只看 Tile 服务日志"
    echo "  log-all    查看 App 进程的所有日志"
    echo "  tile       模拟 Tile 点击 (触发截图流程)"
    echo "  clear      清除 App 数据 (重置配置)"
    echo "  uninstall  卸载 App"
    echo "  devices    查看连接的设备"
    echo "  help       显示此帮助"
    echo ""
    echo "典型调试流程:"
    echo "  1. ./debug.sh run       # 构建安装启动"
    echo "  2. 在 App 中配置 Coze 参数"
    echo "  3. ./debug.sh tile      # 模拟 Tile 点击"
    echo "  4. ./debug.sh log       # 观察日志输出"
    echo ""
}

# 主入口
case "${1:-help}" in
    build)      cmd_build ;;
    install)    cmd_install ;;
    run)        cmd_run ;;
    log)        cmd_log ;;
    log-cap)    cmd_log_capture ;;
    log-up)     cmd_log_upload ;;
    log-tile)   cmd_log_tile ;;
    log-all)    cmd_log_all ;;
    tile)       cmd_tile_click ;;
    clear)      cmd_clear ;;
    uninstall)  cmd_uninstall ;;
    devices)    cmd_devices ;;
    help)       cmd_help ;;
    *)
        error "未知命令: $1"
        cmd_help
        exit 1
        ;;
esac
