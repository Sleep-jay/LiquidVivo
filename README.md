# LiquidVivo

LiquidVivo 是一个面向 vivo OriginOS 的 libxposed 模块和配套管理器。当前版本专注于 System UI 的模糊、透明度和液态玻璃效果调节。

## 当前版本

- 版本：`1.7.4`
- versionCode：`50`
- applicationId：`com.LiquidVivo`
- libxposed API：`102`
- 最低 Android 版本：Android 9（API 28）
- 适配目标：OriginOS 6 / SystemUI 16.0.7.x

## 当前功能

### System UI 模糊控制

模块覆盖以下进程和界面组件：

- `com.android.systemui`
- `com.vivo.systemuiplugin`
- `com.vivo.upslide`
- `com.vivo.card`

管理器中可以分别调整：

- 通知栏 / 控制中心
- 音量面板
- 锁屏和锁屏通知卡片
- 系统弹窗
- 原子岛胶囊及展开态
- 其他系统模糊材质
- 模糊半径、透明度和液态玻璃参数

每个场景独立保存设置，修改后重新展开对应界面即可看到效果。模块也提供 System UI 重启入口，用于在无需重启手机的情况下重新注入 Hook。

## 管理器功能

- 查看 libxposed / LSPosed 连接状态
- 申请和撤销模块作用域
- 查看目标进程适配状态
- 管理 System UI Hook 开关
- 明暗主题、Monet 配色和界面缩放
- 查看模块版本、设备信息和调试日志

## 项目结构

```text
LiquidVivo/
├── app/
│   └── src/main/java/com/LiquidVivo/
│       ├── ui/                 管理器界面与设置页
│       └── xposed/             libxposed 入口、作用域和 Hook 实现
├── version.properties          版本名与 versionCode
├── build.gradle.kts            根构建配置
└── settings.gradle.kts         项目配置
```

主要 Hook 实现位于：

```text
app/src/main/java/com/LiquidVivo/xposed/hooks/systemui/
```

## 构建

Windows PowerShell：

```powershell
cd LiquidVivo
./gradlew.bat :app:assembleDebug --no-daemon
```

Debug APK 输出目录：

```text
app/build/outputs/apk/debug/
```

Release 构建需要在本地配置签名参数：

```text
KEYSTORE_FILE
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD
```

签名文件、密码、构建缓存、日志和 APK 产物不会提交到仓库。

## 安装与启用

1. 构建并安装 APK。
2. 在 LSPosed 中启用 LiquidVivo 模块。
3. 为 System UI、vivo System UI Plugin、侧边栏和内容卡片授予作用域。
4. 打开 LiquidVivo，在 System UI 页面调整模糊和液态玻璃参数。
5. 如需立即重新注入，使用管理器中的 System UI 重启按钮。

## 发布记录

参见 [`RELEASE_NOTES.md`](RELEASE_NOTES.md)。

## 许可证

当前仓库尚未指定开源许可证。
