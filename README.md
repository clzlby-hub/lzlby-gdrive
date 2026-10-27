# lzlby-gooog-photo

> 专为 **Android TV / Google TV / 索尼 BRAVIA** 打造的 Google Photos 与 Google Drive 大屏画廊及极速视频播放器。采用 Apple TV 灵动美学界面，配备定制级轻量流式视频管道与智能相册轮播。

---

## ✨ 核心特性

### 🍎 Apple TV 灵动美学界面
* **纯净毛玻璃质感**：沉浸式暗黑半透明毛玻璃 HUD，极简白色胶囊指示器，大屏视觉层次细腻。
* **呼吸式无干扰体验**：视频播放过程中无按键操作 4 秒后，HUD 与进度条自动平滑淡出；轻触遥控器方向键无缝唤醒。
* **100% TV 遥控器体验**：专为遥控器 D-Pad 优化的焦点系统，告别手机版移植 App 的操作尴尬。

### 🎬 定制级流式视频引擎
* **1 秒极速首帧起播**：基于 ExoPlayer 定制的轻量加载控制管道，无需等待长达数十秒的笨重预加载，点击即播。
* **1.07 秒即时跳转**：精准定位与硬件解码器即时刷新，左右键 10 秒跳跃响应迅捷，无拖泥带水。
* **0 电视闪存写入损耗**：彻底移除传统客户端几十 GB 的 eMMC 磁盘缓存写入，杜绝电视闪存磨损与 I/O 拥堵。
* **消除 GC 卡顿停顿**：内存占用低且平稳，避免 TV 芯片因频繁全量垃圾回收导致的 100ms+ 掉帧。
* **芯片级硬件解码**：直接对接 TV 芯片（如联发科 Pentonic / MT5895 `c2.mtk.hevc.decoder`），支持 HEVC/H.265 硬件解码。

### 🖼️ 沉浸式相册与屏保
* **无限层级与智能分类**：支持 Google Drive 原生目录树解析、全量视频一键汇总，以及 Google Photos 智能时间线。
* **平滑轮播与时间控制**：支持自定义相册轮播间隔时间，平滑过渡。
* **Daydream 系统屏保无缝集成**：内置 Android TV 系统屏保服务，闲置时电视自动化身为精美艺术电子相框。

### 📱 告别电视端复杂打字
* **手机扫码快速配对**：电视屏幕自动生成局域网配对二维码，手机扫码即可直接录入 Token / 凭据。
* **一键辅助脚本 (`auth_helper.py`)**：电脑端运行 Python 辅助脚本，浏览器点击授权后，自动通过 ADB 无线推送到电视并启动应用。

---

## 🛠️ 技术架构

* **UI 框架**：Kotlin, Jetpack Compose for TV (Material 3), AndroidX
* **视频播放引擎**：AndroidX Media3 / ExoPlayer (Custom DefaultLoadControl)
* **图片异步加载**：Coil 3 (支持动态高分辨率采样与预取)
* **网络与 API**：OkHttp 4, Google Drive REST API v3
* **系统最低要求**：Android TV 8.0 (API Level 26) 及以上，推荐 Android TV 10+

---

## 🚀 快速上手与使用指南

### 第一步：获取 Google Cloud OAuth 凭据

1. 登录 [Google Cloud Console](https://console.cloud.google.com/)。
2. 创建一个新项目（例如 `My-TV-Gallery`）。
3. 在 **API 和服务 → 库** 中，搜索并启用 **Google Drive API**（如需 Google Photos，同时启用 **Photos Library API**）。
4. 前往 **API 和服务 → OAuth 同意屏幕**：
   * 用户类型选择 **外部**，填写应用名称与开发者邮箱；
   * 在 **测试用户** 中添加您自己的 Google 账号邮箱。
5. 前往 **凭据 → 创建凭据 → OAuth 客户端 ID**：
   * 应用类型选择 **桌面应用** 或 **Web 应用**；
   * 记录生成的 `Client ID` 和 `Client Secret`。

---

### 第二步：编译与安装

#### 1. 编译 APK
确保本地已安装 JDK 17 及 Android SDK：
```bash
git clone https://github.com/clzlby-hub/lzlby-gooog-photo.git
cd lzlby-gooog-photo

# 编译 Debug / Release APK
./gradlew assembleDebug
```
编译生成的 APK 位于：`app/build/outputs/apk/debug/app-debug.apk`

#### 2. 无线 ADB 安装到电视
确认电视已开启「开发者选项」与「ADB 调试」并处于同一局域网：
```bash
# 连接电视（请替换为您电视的实际 IP 与端口）
adb connect 192.168.1.100:5555

# 安装 APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

### 第三步：登录与授权

本应用提供两种极其简便的登录方式：

#### 方案 A：一键 Python 脚本同步（推荐，最省心）
通过项目自带的 `auth_helper.py` 脚本直接完成电脑端授权与凭据同步：

```bash
# 运行辅助脚本（替换为您的 Client ID、Secret 及电视 IP）
python3 auth_helper.py \
  --client-id "YOUR_CLIENT_ID.apps.googleusercontent.com" \
  --client-secret "YOUR_CLIENT_SECRET" \
  --tv-ip "192.168.1.100" \
  --tv-port "5555"

# 如果处于网络受限地区，可追加代理参数：
# --proxy "http://127.0.0.1:7890"
```
脚本会自动在电脑默认浏览器打开 Google 授权页面，授权完成后秒级将 Access Token 和 Refresh Token 推送到电视，电视即刻进入主页！

#### 方案 B：手机局域网扫码
1. 打开电视 App，进入登录页面；
2. 电视屏幕将显示局域网配置二维码；
3. 确保手机与电视在同一局域网下，微信/系统相机扫码，在网页表单中填入 Client ID 与 Refresh Token，点击保存即可同步。

---

## 💡 最佳使用建议与注意事项

* **4K 原片高码率说明**：
  * 手机拍摄的 4K 60fps 原片码率普遍高达 $50 \sim 85\text{ Mbps}$。Google Drive API 单连接下载通常有限速（约 $15 \sim 25\text{ Mbps}$），当下载速度低于原片码率时，高码率 4K 可能出现周期性缓冲。
  * **建议**：家庭日常记录与相册录制，推荐使用 **1080P 60fps** 或 **4K 30fps**，画质在大屏上极其细腻生动，且能完全秒开零缓冲。超大型 4K 纪实原片建议通过局域网 NAS/SMB 串流或 U 盘直连播放。
* **网络环境**：
  * Android TV 访问 Google 服务需要网络环境支持（可通过路由器透明代理、Clash 等网络工具提供网络连接）。

---

## ⚠️ 免责声明 (Disclaimer)

1. **非官方声明**：本项目为个人基于开源兴趣开发的第三方 Android TV 客户端，**非 Google LLC 官方产品**，亦未受 Google 官方背书或与其有任何商业从属关系。"Google"、"Google Drive"、"Google Photos"、"Android TV" 等标识及商标权均归其各自所有者拥有。
2. **零数据留存与隐私保证**：本项目为**纯本地运行的无中转架构**。本应用及辅助脚本不设任何外部中转服务器，**绝不收集、记录、上传或分析**用户的任何个人数据、账号密码、OAuth 访问令牌 (Token)、照片或视频内容。所有凭据直接保存在电视本地的安全沙盒内，所有数据传输均在用户设备与 Google 官方服务器之间端到端直连加密通信。
3. **第三方服务变动**：本项目依赖 Google 官方开放的云端 API 及服务。如因 Google 服务条款变更、API 限流、接口调整或封停导致本软件部分或全部功能不可用，开发者不承担连带责任。
4. **使用风险与责任限制**：本软件按“现状”（AS-IS）提供，仅供个人技术研究、家庭娱乐及学习交流使用。因使用、安装或调试本软件而产生的任何直接或间接后果（包括但不限于设备故障、网络配额消耗、网盘数据异常或账号风控等），均由使用者自行承担，开发者不承担任何明示或暗示的法律责任。**用户下载、编译或使用本项目的任何部分，即表示已完全理解并接受本免责声明的所有条款。**

---

## 📄 开源协议

本项目采用 [MIT 许可证](LICENSE) 开源。欢迎提交 Issue 或 Pull Request！
