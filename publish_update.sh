#!/usr/bin/env bash
#
# 一键发布应用内更新
# 用法：./publish_update.sh "本次更新说明"
#
# 前置条件：
#   1. 安装 GitHub CLI：brew install gh
#   2. 登录：gh auth login   （选择账号 wys8129-cashii）
#   3. 仓库已存在且为 public（首次需手动建：gh repo create ScreenCapCoze --public）
#
# 脚本会：构建 release APK → 生成 version.json → 以 vX.Y 为标签创建 GitHub Release，
#         把 APK 和 version.json 作为附件上传。App 启动时会自动检测到此更新。
#
set -euo pipefail

OWNER="wys8129-cashii"
REPO="ScreenCapCoze"
DIST="dist"

NOTES="${1:-更新}"
VERSION=$(grep -m1 'versionName' app/build.gradle.kts | sed -E 's/.*"([0-9.]+)".*/\1/')
VERSION_CODE=$(grep -m1 'versionCode' app/build.gradle.kts | sed -E 's/[^0-9]//g')
TAG="v${VERSION}"
APK_NAME="ScreenCapCoze-${TAG}.apk"

echo "==> 版本: ${TAG} (versionCode=${VERSION_CODE})"

echo "==> 构建 release APK..."
./gradlew :app:assembleRelease --no-daemon

mkdir -p "${DIST}"
cp app/build/outputs/apk/release/app-release.apk "${DIST}/${APK_NAME}"

cat > "${DIST}/version.json" <<JSON
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION}",
  "apkUrl": "https://github.com/${OWNER}/${REPO}/releases/download/${TAG}/${APK_NAME}",
  "notes": "${NOTES}"
}
JSON

echo "==> 创建 GitHub Release ${TAG}..."
gh release create "${TAG}" \
  "${DIST}/${APK_NAME}" \
  "${DIST}/version.json" \
  --repo "${OWNER}/${REPO}" \
  --title "${TAG}" \
  --notes "${NOTES}"

echo "==> 发布完成: https://github.com/${OWNER}/${REPO}/releases/tag/${TAG}"
