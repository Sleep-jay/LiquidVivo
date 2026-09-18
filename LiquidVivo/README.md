# Xposed 模块模板（Miuix 管理器）

当前正式版本：**LiquidVivo 1.7.3（versionCode 49）**。发布记录见仓库根目录的 `RELEASE_NOTES.md`。

基于 libxposed API 102 的 Xposed 模块模板：APK 既是 Xposed 模块（被框架加载执行 Hook），又自带 Miuix 风格管理器界面（作用域管理 / 主题设置 / Hook 管理页）。

- 包名：`com.LiquidVivo`（fork 自 KernelSU 管理器 UI 骨架，已清除全部 KSU 逻辑，仅保留 Miuix 界面层）
- UI：Miuix KMP 0.9.3（miuix-ui / icons / preference / blur / navigation3）
- Hook 框架：libxposed API 102（`api` compileOnly 由框架注入，`service` implementation 打进 APK 用于作用域申请）
- 导航：androidx navigation3 + Miuix 集成

---

## 一、目录结构规则

```
app/src/main/java/com/LiquidVivo/
├── MainActivity.kt            # 唯一 Activity：NavDisplay 路由表 + 三标签底栏宿主
├── Natives.kt                 # 原生能力存根（最小集，禁止再长回 KSU 形态）
├── data/
│   ├── model/                 # 数据模型
│   └── repository/            # SettingsRepository 接口 + Impl（仅外观/偏好配置，禁止功能型配置）
├── profile/                   # （保留）配置相关
├── xposed/
│   ├── HookEntry.kt           # 模块入口（java_init.list 指向它，混淆后须保留）
│   ├── XposedState.kt         # 框架状态/作用域：rememberXposedState、requestScope/removeScope
│   ├── AppHooker.kt           # 目标应用 Hook 接口
│   ├── HookerRegistry.kt      # 全部 Hooker 注册表（find/hookers/packageNames）
│   └── hooks/                 # 每个目标应用一个 *Hooker.kt
└── ui/
    ├── navigation3/           # Route 定义 / Navigator / LocalNavigator / IntentDispatcher
    ├── screen/<feature>/      # 每个页面一个目录（见"页面分层规则"）
    ├── component/             # 跨页面复用组件（Gate、SendLogDialog、bottombar、miuix/…）
    ├── util/                  # BlurExt(BlurredBar)、RootStub、blur backdrop 等
    ├── theme/                 # MiuixTheme 封装
    └── viewmodel/             # 仅仍被引用的 ViewModel；新增页面状态优先放页面目录内
```

**新增代码的归属判断**：页面私有 → `screen/<feature>/`；跨页复用 → `component/` 或 `util/`；Hook 逻辑 → `xposed/`；持久化配置 → `data/repository/`。

## 二、页面分层规则（所有页面统一）

每个页面目录固定三层，禁止合并或跳层：

```
ui/screen/<feature>/
├── <Feature>Screen.kt        # 壳：取 navigator / ViewModel / 数据，组装回调，只做"胶水"
└── <Feature>ScreenMiuix.kt   # 实现：纯 UI（Scaffold + 顶栏 + LazyColumn）
```

实现层骨架（与 ColorPaletteScreenMiuix / AppHookScreenMiuix 一致）：

```kotlin
val scrollBehavior = MiuixScrollBehavior()
val backdrop = LocalBlurBackdrop.current          // 模糊背景（主题设置-模糊联动）
val blurActive = backdrop != null
val barColor = if (blurActive) Color.Transparent else colorScheme.surface

Scaffold(
    topBar = {
        BlurredBar(backdrop) {
            TopAppBar(
                color = barColor,               // 模糊开=透明，模糊关=纯色，禁止自造第三条路径
                title = ..., scrollBehavior = scrollBehavior,
                navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, ...) } },
            )
        }
    },
    popupHost = { },
    contentWindowInsets = WindowInsets(0,0,0,0).only(Horizontal),
) { innerPadding ->
    Box(Modifier.then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) { /* 卡片项 */ }
    }
}
```

**硬规则**：
1. 有真 `TopAppBar` 才挂 `MiuixScrollBehavior` 的 `nestedScrollConnection`；自定义顶栏挂 nestedScroll 会吞掉滚动（应用页曾踩坑）。自定义顶栏时 `LazyColumn` 不挂 nestedScroll，且顶栏要 `.statusBarsPadding()`。
2. 顶栏颜色一律走 `blurActive ? Transparent : surface` 两条路径，与"主题设置-模糊"开关行为统一。
3. 滚动三件套 `scrollEndHaptic + overScrollVertical + nestedScroll` 与 `overscrollEffect = null` 不可缺，保证各页手感一致。

## 三、路由规则

- 所有路由在 `ui/navigation3/Routes.kt` 的 `sealed class Route` 内声明，`@Parcelize @Serializable`，实现 `NavKey`。
- 无参页面用 `data object`，带参页面用 `data class`（如 `AppHook(packageName)`）。
- 在 `MainActivity` 的 `entry<Route.X> { ... }` 注册；跳转用 `navigator.push(Route.X)`，返回用 `navigator.pop()`。
- 组合期间禁止直接 `pop()`，需 `LaunchedEffect` 包裹（见 AppHookScreen 的兜底返回）。

## 四、新增目标应用（Hooker）步骤

1. `xposed/hooks/` 新建 `XxxHooker.kt`，实现 `AppHooker`：`packageName` / `appNameRes` / `featureSummaryRes` / `supportedVersions` / `handleLoadPackage`。
2. 在 `HookerRegistry.hookers` 注册——注册后应用页卡片与 Hook 管理页**自动出现**，无需改 UI。
3. `appNameRes` / `featureSummaryRes` 字符串同时补 `values/strings.xml` 与 `values-zh-rCN/strings.xml`（其余语言回退英文）。
4. `resources/META-INF/xposed/scope.list` 追加包名（建议作用域）。
5. Hook 逻辑内禁止访问 Android API 23+ 以外/管理器侧状态；作用域状态读取只走 `XposedState`。

## 五、资源与文案规则

- 所有用户可见文案必须走 strings.xml，中英双语同步添加；禁止硬编码中文。
- 内部标识（包名、路由名、类名）允许保留历史命名（如 `superuser` 包 / `Route.SuperUser`），但**用户可见文案**必须用产品文案（如"作用域"）。
- `about_project_intro` 可保留 fork 归属说明，其余位置禁止出现 "KernelSU/ksu" 字样。

## 六、Xposed 模块清单规则（resources/META-INF/xposed/）

| 文件 | 内容 |
|---|---|
| `java_init.list` | 入口类全限定名，单行（当前 `com.LiquidVivo.xposed.HookEntry`） |
| `scope.list` | 建议作用域包名，一行一个 |
| `module.prop` | 模块元数据（名称/版本/API 级别） |

- 入口类必须在 `proguard-rules.pro` 中 keep，混淆后 `java_init.list` 同步更新。
- `libxposed:api` 只许 `compileOnly`（框架注入，APK 内不得包含）；`libxposed:service` 用 `implementation`。

## 七、依赖与构建规则

- 新增依赖统一进 `gradle/libs.versions.toml` 版本目录，禁止在 build.gradle 写死版本号。
- Miuix 版本升级须全组（ui/icons/preference/blur/navigation3）同步，不可单独升某一个。
- 构建环境（Minis musl 沙箱）：musl JDK21 + musl SDK/NDK，`gradle.properties` 的 `aapt2FromMavenOverride` 指向 musl aapt2；签名用工程根 keystore（见 sign.example.properties）。
- 发布构建必须 `assembleRelease` + apksigner V2/V3 签名后才可安装分发。
- Release 启用「咕嘎」重度混淆：`app/obfuscation/` 三份字典（class/member/package）供 R8 重命名，仅 `HookEntry` 与 libxposed API 保名；改 keep 规则前先确认无反射按名引用。

## 八、禁止事项（踩坑沉淀）

1. 禁止重新引入任何 KernelSU 功能代码/依赖（su、umount、selinux、sulog、adb_root、刷机 core 包等已全部清除）。
2. 禁止给无 TopAppBar 的自定义顶栏挂 scrollBehavior（吞滚动）。
3. 禁止在组合中直接调用 `navigator.pop()`。
4. 禁止在 Hook 进程路径里使用管理器 UI 状态（XposedState 的部分 API 仅在管理器进程可用）。
5. 禁止绕过版本目录直接写依赖坐标。
