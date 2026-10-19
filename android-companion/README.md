# 自记 Android 自动记账 App

这个目录是可独立使用的 Android 自动记账 App。它不会读取微信、支付宝或银行 App 的内部数据，而是在你主动授权 Android 通知读取权限后监听支付通知，解析金额、商户、账户和分类，并直接保存到手机本地。电脑桥接接口 `/api/capture` 仅用于可选同步。

## 当前状态

- `NotificationMatcher`：纯 Java 通知过滤核心，可在电脑上直接测试。
- `NotificationTextBuilder`：合并通知标题、正文、多行文本和 ticker，减少金额或商户被拆到不同字段时的漏记。
- `NotificationDeduper`：Android 端短时去重，避免同一条通知在 2 分钟内重复发送。
- `NotificationCaptureProcessor`：统一执行通知过滤、去重、解析、本地入账和可选同步排队；真实通知服务与测试按钮共用同一路径。
- `CaptureHistory` / `CaptureHistoryStore`：仅保存最近 30 条支付相关处理状态，供手机内诊断通知格式，不记录普通通知。
- `TransactionParser`：在手机端解析金额、商户、账户、收支方向和分类。
- `LocalLedger` / `LocalLedgerStore`：保存最多 2000 笔本地账单，支持纠错和删除，电脑离线不影响记账。
- `LedgerCsvExporter`：保留为数据维护能力，不放在日常记账主页。
- `PendingCaptureQueue`：Android 端待发送队列，电脑桥接暂时不可达时最多暂存 50 条账单，后续补发。
- `PendingCaptureFlusher`：补发队列发送器，每成功发送 1 条就立刻保存队列，降低半路断网造成重复补发的概率。
- `BridgeSetupText`：从分享文本里识别桥接地址和 token，减少手机端手动输入配置。
- `BridgeClient`：把通知文本 POST 到自记本地服务。
- `ZijiNotificationListenerService`：Android 通知监听服务，匹配后先写入手机本地账本，再尝试可选同步。
- `ShareCaptureActivity`：Android 系统分享文本入口，用作通知自动捕获之外的兜底入账通道。
- `SettingsActivity`：手机账本主页，按“明细 / 自动记 / 统计 / 我的”组织内容；底部中央加号进入轻量手动记账，选择收支和分类图标后只需输入金额即可保存。
- `AndroidManifest.xml`：通知监听服务所需声明，并允许访问局域网 HTTP 桥接服务。
- `build.gradle` / `settings.gradle`：最小 Android Gradle 项目配置，可用 Android Studio 打开。
- `gradlew.bat`：随项目生成的 Gradle Wrapper，方便没有全局 `gradle` 的机器打包。

当前目录已经可以在本机完成测试和 APK 构建。项目根目录运行 `npm.cmd run android:build` 后，会在 `build/outputs/apk/debug/` 生成 debug APK。

## 打包 APK

先在项目根目录运行：

```powershell
npm.cmd run doctor
```

如果 `Android SDK` 显示 `MISSING`，需要先安装 Android Studio，或设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`。

也可以让项目把 Android command-line tools 和所需 SDK 包安装到当前目录的 `.android-sdk`：

```powershell
npm.cmd run android:setup-sdk -- -AcceptAndroidSdkLicense
```

这个命令会下载官方 Android command-line tools，并安装 `platform-tools`、`platforms;android-35` 和 `build-tools;35.0.0`。它需要你先确认接受 Google Android SDK License。

如果当前网络无法直接访问 `dl.google.com`，可以先用浏览器下载官方 command-line tools zip，然后运行：

```powershell
npm.cmd run android:setup-sdk -- -AcceptAndroidSdkLicense -CommandLineToolsZip C:\路径\commandlinetools-win-14742923_latest.zip
```

推荐方式：用 Android Studio 打开 `android-companion/` 目录，等待 Gradle Sync 完成，然后选择 `Build > Build APK(s)`。

命令行方式：

```powershell
npm.cmd run android:build
```

推荐从项目根目录运行这个命令。脚本会检查 Android SDK，进入 `android-companion/` 调用 `gradlew.bat assembleDebug`，成功后打印 APK 路径。

第一次运行 `gradlew.bat` 会下载 Gradle 8.13；如果当前网络无法访问 `services.gradle.org`，可以先用 Android Studio 打开项目让它同步依赖，或者换到网络可用的环境再构建。

打包成功后，APK 会出现在 `build/outputs/apk/debug/` 目录下。安装到自己的 Android 手机后，先打开 App 填写桥接地址和 token。

如果你的项目路径包含中文，`gradle.properties` 已经加入 `android.overridePathCheck=true`，避免 Android Gradle Plugin 在 Windows 上直接拒绝构建。

如果手机已经通过 USB 连接，并且开启了开发者选项和 USB 调试，可以在项目根目录直接运行：

```powershell
npm.cmd run android:deploy
```

这个命令会构建 debug APK、安装到当前连接的手机，并启动“自记通知伴侣”的设置页。只想安装已生成的 APK 时可以运行：

```powershell
npm.cmd run android:install
```

安装后可以检查设备连接、App 是否安装、通知读取权限是否开启：

```powershell
npm.cmd run android:status
```

排查真机通知时建议开一条日志窗口：

```powershell
npm.cmd run android:logs -- -Follow
```

## 使用思路

1. 电脑启动自记局域网服务：

```powershell
npm.cmd run start:lan
```

2. 手机和电脑连同一个 Wi-Fi。启动脚本会打印手机浏览器访问地址、Android 伴侣接口地址，以及本次临时 token。

可以先在电脑上检查桥接服务和 token 是否匹配：

```powershell
npm.cmd run bridge:preflight -- -Token <token>
```

3. Android 伴侣 App 设置页里配置桥接地址：

```text
http://电脑局域网IP:5188/api/capture
```

也可以只填 `电脑局域网IP:5188` 或 `http://电脑局域网IP:5188/`，App 保存时会自动补成 `/api/capture`。
如果把 PWA 的 `http://电脑局域网IP:5188/?bridgeToken=...` 链接粘进桥接地址，App 保存时也会自动转成 `/api/capture`，并在 token 输入框为空时自动提取 token。

4. 配置启动脚本打印的 token。如果你显式用 `-NoToken` 启动服务，这里才可以留空。

5. 先看“待补发账单”数量。如果大于 0，可以点“补发历史账单”手动重试。

6. 点“发送测试账单”。如果网页里出现“自记测试咖啡”，说明手机到电脑的桥接链路通了。

7. 在 Android 设置里给伴侣 App 开启“通知使用权/通知读取”权限。

之后，当微信支付、支付宝、银行卡通知出现时，伴侣 App 会合并通知标题、正文、多行文本和 ticker，解析成功后直接写入手机账本并同步给 PWA。银行卡如果主要通过短信提醒消费，短信 App 弹出的银行消费通知也会走这条链路；验证码、账单提醒和信用卡还款会被过滤，避免误记或重复记账。无法读到金额的通知不会生成空账单，可用手动记账补录。

如果某个通知暂时没被自动捕获，也可以从支付详情、短信或聊天里把账单文本分享到 Android 的“发送到自记”。这个入口会使用同一个待补发队列：桥接可达时立即发送，桥接不可达时先保存在手机上，后续可在设置页点击“补发历史账单”。

“发送到自记”也可以接收配置文本。如果分享内容里包含 `/api/capture` 地址、PWA 的 `?bridgeToken=...` 链接，或 `Bridge token: ...`，伴侣会优先保存桥接配置并打开设置页，而不是把它当账单入队。

Android 系统有时会对同一条通知触发多次回调。伴侣 App 会先在手机端对 2 分钟内的相同通知短时去重；自记本地桥接接口也会对尚未被网页领取的相同通知文本做兜底去重，避免同一条支付通知重复堆到账本队列里。

如果手机捕获到支付通知时电脑服务没开、电脑不在同一 Wi-Fi，或网络临时失败，伴侣 App 会把账单先保存在本机待发送队列里。队列最多保留 50 条，设置页会显示“待补发账单”数量；下一次通知触发、保存配置、点击“补发历史账单”或点击“发送测试账单”时会尝试补发。

补发时，伴侣 App 会在每一条账单成功发送后立刻更新本地队列。如果补发到一半网络断开，已经成功发送的账单会尽量从本机队列里移除，未发送的账单继续保留，下一次再补发。

## 验证过滤逻辑

在项目根目录运行：

```powershell
npm.cmd run test:android
```

这会在电脑上编译并测试纯 Java 的 `NotificationMatcher`、`NotificationTextBuilder`、`NotificationDeduper`、`PendingCaptureQueue` 和 `BridgeClient`，不需要 Android SDK。

如果想一次性确认本地代码、桥接模拟和环境状态：

```powershell
npm.cmd run check:ready
```

这个检查不需要真机，也不等于真实微信/支付宝/银行卡通知已经验证。真正完成自动记账前，还需要安装 APK、开启通知读取权限，并用真实消费通知确认 PWA 收到账单。
