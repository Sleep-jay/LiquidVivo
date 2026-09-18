# LiquidVivo

LiquidVivo 是面向 vivo OriginOS 的 libxposed API 102 模块与 Miuix 管理器应用。

## 功能

- Miuix 风格管理器界面
- Xposed / libxposed 模块入口
- SystemUI、设置页和系统插件相关 Hook
- 主题、模糊效果、作用域和模块状态管理
- 中英文及多语言资源

## 项目结构

```text
LiquidVivo/
└── app/                 Android 应用与 Xposed 模块源码
```

## 当前版本

- 版本名：`1.7.3`
- versionCode：`49`
- libxposed API：`102`
- 最低 Android SDK：`28`

## 构建

在项目目录执行：

```powershell
cd LiquidVivo
./gradlew.bat :app:assembleDebug --no-daemon
```

Debug APK 输出到：

```text
LiquidVivo/app/build/outputs/apk/debug/
```

Release 构建需要在本地配置签名参数，签名文件和密码不会提交到仓库。

## 发布记录

参见 [`RELEASE_NOTES.md`](RELEASE_NOTES.md)。

## 许可证

当前仓库未指定开源许可证。
