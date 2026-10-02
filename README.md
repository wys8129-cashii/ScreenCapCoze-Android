<<<<<<< HEAD
# ScreenCapCoze - 截图上传到 Coze

一个轻量级 Android App，在下拉控制中心添加快捷按钮，点击后自动截图并发送到 Coze API。

## 功能

- 📸 **快捷截图**：下拉控制中心一键截图，无需打开 App
- 🚀 **自动上传**：截图自动上传到 Coze Bot，触发 AI 分析
- 🔧 **简单配置**：只需填写 Coze Bot ID 和 Access Token

## 架构

| 组件 | 作用 |
|------|------|
| `ScreenCapTileService` | Quick Settings Tile（下拉控制中心按钮） |
| `ScreenCaptureService` | 前台服务，使用 MediaProjection 截图 |
| `CozeUploader` | OkHttp 上传图片到 Coze API |
| `MainActivity` | 首次授权 + 状态展示 |
| `SettingsActivity` | Coze API 参数配置 |

## 构建与安装

### 前置条件

- Android Studio (推荐最新版)
- Android SDK 35 (compileSdk)
- JDK 17
- ADB (Android Debug Bridge，Android Studio 已自带)

### 方式一：Android Studio 图形界面

1. **打开项目**：`File → Open → 选择 ScreenCapCoze 目录`
2. **构建**：等待 Gradle sync → `Build → Build APK(s)`
3. **安装**：USB 连接手机 → `Run → Run 'app'`

### 方式二：命令行（推荐调试用）

在 Android Studio 底部的 **Terminal** 面板中直接运行：

```bash
# 一键构建+安装+启动
./debug.sh run

# 或分步执行
./debug.sh build      # 构建 APK
./debug.sh install    # 安装到设备
./debug.sh devices    # 查看连接的设备
```

## 命令行调试

项目内置了 `debug.sh` 调试脚本，在 Android Studio Terminal 或独立终端中使用：

```bash
# 构建安装启动（最常用）
./debug.sh run

# 查看日志（Ctrl+C 退出）
./debug.sh log          # 全部 App 日志
./debug.sh log-cap      # 只看截图服务日志
./debug.sh log-up       # 只看上传日志
./debug.sh log-tile     # 只看 Tile 服务日志

# 模拟 Tile 点击（不用下拉控制中心）
./debug.sh tile

# 管理命令
./debug.sh clear        # 清除 App 数据（重置 Coze 配置）
./debug.sh uninstall    # 卸载 App
./debug.sh devices      # 查看连接设备

# 查看帮助
./debug.sh help
```

### 典型调试流程

```
1. ./debug.sh run        # 构建安装启动
2. 在 App 中配置 Coze 参数
3. ./debug.sh tile       # 模拟 Tile 点击触发截图
4. ./debug.sh log        # 观察日志输出
```

### 直接 ADB 命令

不用 debug.sh 也可以直接用 ADB：

```bash
# 查看连接设备
adb devices

# 构建 APK（项目根目录下）
./gradlew assembleDebug

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启动 App
adb shell am start -n com.personal.screencapcoze/.MainActivity

# 模拟 Tile 点击
adb shell am start -n com.personal.screencapcoze/.MainActivity \
    -a com.personal.screencapcoze.FROM_TILE

# 查看日志
adb logcat -s ScreenCapTile ScreenCapture CozeUploader MainActivity

# 清除 App 数据
adb shell pm clear com.personal.screencapcoze
```

## 使用流程

### 第一步：配置 Coze API

打开 App → 点击「打开设置」→ 填写：

- **API 地址**：`https://api.coze.cn`（国内）或 `https://api.coze.com`（海外）
- **Bot ID**：在 Coze 平台创建/选择 Bot 后获取
- **Access Token**：在 Coze 个人设置中生成 PAT

获取 Coze 参数的具体步骤：
1. 登录 [coze.cn](https://www.coze.cn) 或 [coze.com](https://www.coze.com)
2. 创建或选择一个 Bot
3. 在 Bot 设置页面复制 **Bot ID**
4. 在「个人设置 → API」中生成 **Personal Access Token (PAT)**

### 第二步：授权截图权限

打开 App → 点击「授权截图权限」→ 系统弹窗确认 → 点击「立即开始」

### 第三步：添加快捷按钮

1. 下拉控制中心
2. 点击编辑按钮（铅笔图标）
3. 找到「截图上传」Tile 并拖到快捷面板中

### 第四步：使用

下拉控制中心 → 点击「截图上传」按钮 → 自动截图 → 自动上传到 Coze

> 每次截图会弹出系统授权确认框（Android 安全机制，无法绕过）

## API 流程说明

App 调用 Coze API 的流程：

```
1. 上传文件 → POST /v1/files/upload → 获得 file_id
2. 发送消息 → POST /v3/chat (附带 file_id) → Bot 接收到截图并开始处理
```

如果你的 Coze Bot 配置了特定的分析/识别能力，它会自动对截图进行处理。

## 自定义提示词

默认发送给 Bot 的消息是 `"请分析这张截图"`。如果你想修改提示词，编辑 `CozeUploader.kt` 中的 `sendMessage` 方法：

```kotlin
val messageContent = """{
    "role": "user",
    "content": "你的自定义提示词",
    "content_type": "text",
    "file_ids": ["$fileId"]
}"""
```

## 注意事项

- **MediaProjection 授权**：Android 要求每次截图前用户确认授权弹窗，这是系统安全机制
- **前台服务通知**：截图过程中会显示通知栏提示，这是 Android 对前台服务的要求
- **仅个人使用**：此 App 不考虑上架 Play Store，未做隐私政策等合规处理

## 依赖

- OkHttp 4.12.0（HTTP 请求）
- Kotlin Coroutines（异步处理）
- AndroidX Preference（设置存储）

## License

个人使用项目，无特殊 License 要求。
=======
# ScreenCapCoze-Android
安卓设备截屏采集 App
>>>>>>> 894b3a850f7e4a64d0f372c7f2822aff1ad8eeb1
