# DeepSeek Harness Android Client

极简原生 Android 客户端：纯 Java + Android 原生 SDK + 系统 WebView，零 Gradle、零 AndroidX、零第三方框架、不打包任何浏览器内核、DSH 前端静态资源或 Termux/Node 运行环境。

## 目录结构

```
dsh-android-client/
├── AndroidManifest.xml
├── build.sh                                   # 纯 Termux 原生环境可重复构建脚本
├── keystore/dsh-client-release.jks            # 持久化签名密钥（覆盖升级自动复用）
├── scripts/dsh-termux-ctl.sh                  # 独立 Termux 后端控制脚本（不修改现有 DSH）
├── res/                                       # 矢量图标、菜单、主题与本地明文安全配置
├── src/com/deepseek/dshclient/
│   ├── MainActivity.java                      # 单 Activity WebView 宿主、平板/外接屏适配、菜单与对话框
│   ├── AppConfig.java                         # SharedPreferences 配置管理
│   ├── TermuxController.java                  # Termux RUN_COMMAND Intent 调度与状态解析
│   ├── TermuxResultReceiver.java              # PendingIntent 异步结果回调（无轮询、无常驻服务）
│   ├── WebViewDownloadHelper.java             # localhost 带 Cookie 下载 + blob/data 导出保存
│   └── NativeClipboardBridge.java             # 本地可信域剪贴板兜底桥接
└── dist/dsh-android-client.apk                # 构建产物（同步复制到 /storage/emulated/0/Download/）
```

## 核心特性

- **极简原生架构**：直接复用系统 WebView 内核与系统菜单、对话框、文件选择器，APK 体积远低于 1MB。
- **通过 localhost 访问现有 DSH 服务**：默认连接 `http://127.0.0.1:3080`，支持配置主机、端口、工作目录和控制脚本路径。
- **启动令牌（`?token=`）自动交换 Cookie**：`dsh web` 的页面只接受携带本进程启动令牌的首次访问，否则返回 `dsh web authentication required`。客户端会在首次打开时通过控制脚本的 `url` 动作取出该令牌链接完成一次认证并保存 30 天 Cookie，之后直接进入页面；令牌失效时由 `onReceivedHttpError(401)` 自动重取（最多 3 次，不轮询）。若未授予 Termux 权限，可用菜单「粘贴认证链接」手动打开 `dsh web` 打印的链接。
- **Termux `RUN_COMMAND` 后端管理**：通过独立脚本 `~/.local/bin/dsh-termux-ctl` 实现 `start` / `stop` / `restart` / `status` / `logs` / `url`，使用显式 `PendingIntent` 回传结果，无需后台常驻服务或定时轮询；脚本按 `--port` 精确匹配实例，端口不一致时在状态中提示 `OTHER_PORTS`。
- **仅关闭前端、保留后端**：返回退出与「仅关闭前端（保留后端）」不会停止 Termux 中的 DSH 进程；如需彻底关闭，可手动选择「停止后端」或「停止后端并退出」。
- **完整 Web 交互支持**：
  - 文件上传：`ACTION_OPEN_DOCUMENT` 单选/多选 + MIME 自动映射。
  - 文件下载：支持 localhost 带 Cookie 的 HTTP 下载及前端 `blob:` / `data:` 导出（Android 10+ 免权限直接保存至 `Download`）。
  - 剪贴板：系统 WebView 原生选择复制 + 本地可信域 `navigator.clipboard` 原生兜底桥接。
  - Cookie 与 WebSocket：持久化 Cookie（`onPause`/`onStop` 自动 `flush`）与原生 `ws://127.0.0.1` 支持。
- **自适应应用图标**：矢量自适应图标直接使用 DSH 前端内置的官方 DeepSeek 鲸鱼路径（`dsh-web-frontend/dist/favicon.svg`），缩放到 66dp 安全圆内，无位图资源。
- **页面颜色与 DSH「外观」严格一致**：关闭 WebView 侧的 algorithmic darkening（API 33+）、`setForceDark(FORCE_DARK_OFF)`（API 29–32）与主题 `android:forceDarkAllowed=false`，避免系统深色把 DSH 的浅色/深色令牌二次变换导致颜色失真；「跟随系统」随系统深浅色切换时自动重渲染。
- **自定义沉浸式顶栏（不使用框架 ActionBar）**：
  - Android 15/16 对 `targetSdk 35` 强制边到边，框架 ActionBar 会被画到状态栏下面；因此应用自己绘制一条 48dp 顶栏，并显式 `setDecorFitsSystemWindows(false)` 后在所有 API 版本上统一处理状态栏、导航栏、刘海与输入法 `Insets`。
  - 顶栏内容：状态圆点（绿=已连接 / 橙=检测或认证中 / 红=未运行或异常）、页面标题、单行状态说明、右侧唯一一个 ☰ 菜单按钮，**不悬浮在页面上**，因此永不遮挡 DSH 自身右上角的按钮。
  - 「隐藏顶栏（沉浸模式）」只收起应用自己的顶栏，**系统状态栏（时钟那一列）始终保留**，页面同时也按状态栏高度下移，不会被时钟压住；返回键或菜单项即可恢复顶栏。
  - 顶栏与状态栏底色会跟随 DSH 页面实际的 `background-color`（`MutationObserver` 事件驱动，无轮询），因此 DSH 选「深色」而系统是浅色时，图标对比度、顶栏底色和状态栏底色依然一致。
- **平板横竖屏及外接显示器适配**：
  - 完整 `configChanges` 防止旋转/分屏/切外接屏时重建 WebView 断开 WebSocket。
  - Android 15/16 `WindowInsets`（状态栏、导航栏、挖孔、软键盘）适配。
  - 支持「切换全屏 / 顶栏」以及外接键盘快捷键（`F5`/`Ctrl+R` 刷新、`F11`/`Ctrl+Shift+F` 全屏、`Alt+←/→` 前进后退）。

## 重新编译与覆盖升级

```bash
bash /storage/emulated/0/dsh_project/dsh-android-client/build.sh
```

- 每次构建会自动复用 `keystore/dsh-client-release.jks`，确保后续版本可直接覆盖安装保留登录 Cookie 和配置。
- 构建完成后自动输出到 `/storage/emulated/0/Download/dsh-android-client.apk`。

## 使用前提

1. 已安装 Termux 且现有 DSH 正常运行。
2. `~/.termux/termux.properties` 中已启用 `allow-external-apps = true`（当前环境已启用）。
3. 首次打开或首次点击后端控制功能时，在系统弹窗中授予「允许在 Termux 环境中运行命令」权限（`com.termux.permission.RUN_COMMAND`）。授予后客户端会自动完成启动令牌认证。

## 认证排查

| 现象 | 处理 |
| --- | --- |
| 页面显示 `dsh web authentication required` | 客户端会自动调用 `dsh-termux-ctl url` 重新取令牌；仍失败时用菜单「粘贴认证链接」手动打开 `dsh web` 打印的链接 |
| 提示需要 Termux 权限 | 在系统权限中授予，或改用「粘贴认证链接」 |
| 后端在别的端口运行 | 菜单「后端状态」会显示 `其他端口上的实例`，在「连接与脚本配置」中改成该端口即可 |

手工验证脚本（Termux 内）：

```bash
~/.local/bin/dsh-termux-ctl status     # 状态/端口/日志/AUTH_URL
~/.local/bin/dsh-termux-ctl url        # 取带 ?token= 的认证链接
~/.local/bin/dsh-termux-ctl logs       # 最近日志
```
