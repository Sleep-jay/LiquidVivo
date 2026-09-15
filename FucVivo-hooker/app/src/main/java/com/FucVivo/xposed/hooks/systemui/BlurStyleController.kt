package com.FucVivo.xposed.hooks.systemui

import android.content.SharedPreferences
import android.view.View
import com.FucVivo.xposed.HookEntry
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SystemUI / SystemUIPlugin 模糊与透明度自定义控制器。
 *
 * 挂载依据（OriginOS 15 实机 ROM 验证：AAX/rom/framework/framework.jar dexdump
 * + vivo-framework.jar + rom-decomp，详见 shared/md/AAX/rom/ 01/03/04 文档）：
 *
 * android.view.View（framework.jar）声明的是材质【门面】方法（隐藏 API，PUBLIC）：
 *   setMaterial(II)Z / setMaterial(III)Z（O6 主入口，三参带 materialPositionType）/
 *   setMaterialForce(II/III)Z / setMaterialAlpha(F) / setMaterialBlurRadius(F) /
 *   setMaterialCustomized(FFIFIIFIFI / FFIFIIFIIFIFI / FFIFIIFIIFIIFII / Bundle) /
 *   setMaterialMaskFusion(FIFIIFIFI / FIFIIFIIFIIFI)（O6 新增 12 参带第三遮罩层）/
 *   setMaterialDarkenPercent(F) ...
 * 底层 setBlurRadius/setBlurAlpha/setBlurCustomized/setWindowBlur* 不在 View 上，
 * 在 android.view.VivoViewImpl（vivo-framework.jar，bootclasspath），门面内部委托过去。
 * SystemUI/Plugin 的所有调用（originui VBlurUtils 反射、VivoMaterialBlurDelegator 等）
 * 都落在 View 门面方法上 —— 因此只挂门面，天然全覆盖且无二次缩放（不再挂底层委托目标）。
 * 门面缺失的 ROM 回退挂底层（View + VivoViewImpl 双探，O6 回退覆盖 10/13/14 参）。
 *
 * 其余挂面：
 *  - 材质 token：android.view.blur.BlurMaterial.resolveBlurGeometryByStyle(BlurGeometry, style)
 *    静态解析 76 个材质常量的几何参数。已验证（framework.jar View.java +
 *    vivo-framework VivoViewImpl）：View.setMaterial(II)/setMaterialForce(II) →
 *    VivoViewImpl.setMaterialForce → setWindowBlurEffect/setBackdropBlurEffect，
 *    解析全部发生在同一调用栈内 —— 这两个门面挂点用 ThreadLocal 记录接收者场景，
 *    解析时读取归场景；栈外调用（无法归因）回落「其他表面」。
 *  - SystemUI 私有 Drawable 栈（不走 View 扩展）：
 *    O6 起 VivoBackgroundBlurDrawable 的 setBlurRadius(int)/setColor/setFallbackColor 已删除，
 *    改由 BlurDrawableController.getDrawable() 创建时直接写字段（mBlurRadius=300、
 *    mPaint/mDisableBlurPaint 取资源色）。挂点改为 after-hook getDrawable 缩放 mBlurRadius
 *    与 Paint alpha（存原始值幂等），配置变更时遍历 Aggregator.mDrawables 重放。
 *    该栈是下拉面板/锁屏的窗口级遮罩（NotificationShadeWindowView / KeyguardHostContainer
 *    共用），主用户是通知中心面板 → 归「通知中心」子项。
 *
 * ---------- 场景模型 ----------
 * 半径/不透明度按生效场景拆分为独立子项，键名 = 前缀 + 场景后缀：
 *   blur_radius_scale_shade / blur_radius_scale_volume / blur_radius_scale_keyguard /
 *   blur_radius_scale_island / blur_radius_scale_other
 *   blur_alpha_scale_shade / blur_alpha_scale_volume / blur_alpha_scale_keyguard /
 *   blur_alpha_scale_popup / blur_alpha_scale_island / blur_alpha_scale_other
 *
 * 场景识别（1.5.0 v2，实机回归后修订）：
 *  1. 类名锚点：沿接收者 View 自身类 + 父链（≤24 级）按 simpleName 匹配，
 *     优先级 原子岛 > 音量 > 锁屏 > 通知中心。锚点依据（rom-decomp 源码核对）：
 *       音量：VivoVolumeSeekbar（材质主体）
 *       锁屏：Keyguard* 前缀 + BouncerBlurView / BaseRealtimeBlurView / ChargeBlurView 等
 *       通知中心：NotificationStackScrollLayout / PhoneStatusBarView / VivoPanelViewHolder
 *         + QS 容器（QSPanel / QSContainerImpl / VivoQSCenter 等）——实机验证控制中心
 *         与通知中心共用同一面板背景表面，无法分别调节，并入通知中心子项
 *       原子岛：DynamicCapsuleContainer（com.vivo.island.view，展开/胶囊态材质载体）
 *       系统弹窗：VCustomRoundRectLayout（originui dialog/sheet 圆角容器）/
 *         VListPopupWindow / VSpinner
 *  2. 窗口类型兜底（优先于弹窗锚点与未匹配结论）：接收者所在窗口类型为
 *     TYPE_VOLUME_OVERLAY(2020) → 音量面板（音量对话框内部视图无类名锚点，
 *     实测窗口类型是可靠信号；VivoVolumeNewImpl 即 setType(2020)）
 *  3. 通知卡片兜底：识别为通知卡片表面（v3）且无锚点命中时归通知中心
 *     （悬浮/锁屏通知行可能未挂入带锚点的层级即被应用材质）
 *  4. 其余 → 其他表面（含 token 解析的栈外调用）
 *
 * 迁移兼容：旧版全局键 blur_radius_scale / blur_alpha_scale 保留为回退值——
 * 场景键未单独设置时沿用全局键（存量用户配置不丢失）；两者皆无 → 100%。
 * 1.5.0(35) 曾短暂引入的 blur_*_scale_qs 键已废弃（控制中心并入通知中心）。
 *
 * remote prefs 键（32 分片路由，见 ShardedHookPrefs）：
 *  - enable_blur       总开关（默认 true）
 *  - 场景键如上（0-200，默认 100）
 *
 * 诊断：安装结果用 android.util.Log 直写 logcat（tag [TAG]），
 * 不依赖 libxposed 服务侧日志路由，logcat -s FucVivoBlurStyle 即可查。
 *
 * v3 修复（通知卡片防透）：悬浮通知/锁屏通知卡片走 VivoNotificationBlurController →
 * NotificationBackgroundView/NotificationHeaderView，其 setMaterialAlpha 传的是行
 * 动画 alpha（非设计不透明度），遮罩不透明度本就低（亮色 0.2/0.4、暗色 0.25/0.6），
 * 两处同时 ×alphaScale 造成二次缩放（有效值 ≈ scale²），且缩放后的值会触发 ROM
 * 「alpha<0.1 禁用模糊/兜底色」门控 → 通知卡片近乎全透明。
 * 处理：通知卡片表面跳过 setMaterialAlpha 类缩放；setMaterialCustomized 的遮罩
 * 不透明度改温和映射（0.5+0.5×场景缩放，最低保留 50% 设计值），finalOpacity 不缩放。
 *
 * v2 增补（实机回归）：
 *  - setMaterialMaskFusion(F,I,F,I,I,F,I,F,I) 挂面：面板/锁屏遮罩不透明度与
 *    最终不透明度（[2]/[5]/[7]）此前无挂点 → 通知中心/锁屏的「不透明度」不生效；
 *    [0] 语义不明（疑似 fusion 参数，非模糊半径）不缩放。通知卡片无此调用路径，
 *    不做温和映射。
 *  - setMaterial(II)/setMaterialForce(II) 挂点：token 型材质（音量面板、系统弹窗、
 *    部分通知表面走 VBlurUtils.setBlurEffect → setMaterial 模式）经 ThreadLocal
 *    归场景后再进 token 解析。
 */
object BlurStyleController {

    private const val TAG = "FucVivoBlurStyle"

    const val KEY_ENABLE = "enable_blur"

    /** 旧版全局键（1.4.x）：场景键缺省时的回退值，勿删 */
    const val KEY_RADIUS_SCALE = "blur_radius_scale"
    const val KEY_ALPHA_SCALE = "blur_alpha_scale"

    const val KEY_RADIUS_PREFIX = "blur_radius_scale_"
    const val KEY_ALPHA_PREFIX = "blur_alpha_scale_"

    const val DEFAULT_SCALE = 100
    const val MIN_SCALE = 0
    const val MAX_SCALE = 200

    const val PKG_SYSTEMUI = "com.android.systemui"
    const val PKG_SYSTEMUI_PLUGIN = "com.vivo.systemuiplugin"

    /** WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY（音量对话框窗口） */
    private const val WINDOW_TYPE_VOLUME_OVERLAY = 2020

    /** 场景父链探测深度上限（vivo 通知行嵌套较深，12 级实测不够） */
    private const val MAX_PARENT_DEPTH = 24

    /**
     * 生效场景。suffix 参与组键；hasRadius/hasAlpha 标记该场景暴露哪些调节子项
     * （POPUP 的模糊半径由 ROM 设计值决定、无独立子项，但 hook 侧仍按场景解析——
     * 其半径回退到全局/默认值，与未拆分前行为一致）。
     * 控制中心与通知中心实机验证共用同一面板背景表面 → 不设独立场景。
     * SHADE 不透明度：面板遮罩浓度（setMaterialMaskFusion 参数）由 ROM 在面板
     * 初始化时烘焙一次，下拉过程不重放，滑条无法实时生效 → 实机回归后砍掉子项
     * （挂面保留，走旧版全局键回退，语义与 1.4.x 一致）。
     * KEYGUARD 不透明度：锁屏实时模糊（BaseRealtimeBlurView）遮罩 opacity 恒为 0，
     * 观感浓度走 setMaterialDarkenPercent(进度×0.45)——动画驱动值，缩放会破坏
     * 渐变（同 v3 通知卡片教训）→ 实机回归后砍掉子项。
     */
    enum class BlurScene(val suffix: String, val hasRadius: Boolean, val hasAlpha: Boolean) {
        SHADE("shade", true, false),
        VOLUME("volume", true, true),
        KEYGUARD("keyguard", true, false),
        POPUP("popup", false, true),
        ISLAND("island", true, true),
        OTHER("other", true, true),
    }

    fun radiusKey(scene: BlurScene): String = KEY_RADIUS_PREFIX + scene.suffix
    fun alphaKey(scene: BlurScene): String = KEY_ALPHA_PREFIX + scene.suffix

    @Volatile
    private var enabled = true

    /** 场景 → 缩放系数（0-2），下标用 ordinal */
    private val radiusScales = FloatArray(BlurScene.entries.size) { 1f }
    private val alphaScales = FloatArray(BlurScene.entries.size) { 1f }

    /** 总开关状态（供同域控制器（如原子岛胶囊统一）做联合门控） */
    val featureActive: Boolean
        get() = enabled

    /** listener 可能被弱引用，强引用保活 */
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    // BlurGeometry 字段反射缓存（android.view.blur.IVivoBlurRender$BlurGeometry）
    private val geometryFields = HashMap<String, Field?>()

    /** setMaterial(II)/setMaterialForce(II) 调用栈内的接收者场景（供 token 解析归因） */
    private val threadScene = ThreadLocal<BlurScene>()

    /** 诊断：按 key 限流打印（前 8 次 + 每 40 次），定位材质模糊数据流。
     *  vivo user 版静音了 SystemUI 的 logcat，同步落盘到自身外部 files 目录供 adb pull */
    private val diagCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun diag(key: String, msg: String) {
        val n = (diagCounts[key] ?: 0) + 1
        diagCounts[key] = n
        if (n <= 8 || n % 40 == 0) {
            android.util.Log.i(TAG, "[diag#$n] $key $msg")
            runCatching {
                val at = Class.forName("android.app.ActivityThread")
                val app = at.getMethod("currentApplication").invoke(null) as? android.app.Application
                    ?: return@runCatching
                val dir = app.getExternalFilesDir(null) ?: return@runCatching
                val f = java.io.File(dir, "fucvivo_diag.log")
                f.appendText("${android.os.SystemClock.uptimeMillis()} #$n $key $msg\n")
            }
        }
    }

    /**
     * 视图资源条目名 → 场景 缓存（含未命中结论，null 值）。
     * 实机回归：音量对话框背景（mDialogView，id=volume_dialog，已验证插件资源表
     * 条目名未混淆）的材质在视图未附着窗口时即被应用，窗口类型/父链锚点都够不着
     * → 以接收者自身资源条目名兜底归类。用视图自己的 resources 解析，
     * 与所在进程/类加载器/附着状态均无关。
     */
    private val idNameSceneCache =
        java.util.Collections.synchronizedMap(HashMap<Int, BlurScene?>())

    fun install(module: HookEntry, cl: ClassLoader, packageName: String) {
        android.util.Log.i(TAG, "install start pkg=$packageName")
        val prefs = module.hookedPrefs
        reload(prefs, verbose = true)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == KEY_ENABLE ||
                key?.startsWith(KEY_RADIUS_PREFIX) == true ||
                key?.startsWith(KEY_ALPHA_PREFIX) == true ||
                key == KEY_RADIUS_SCALE || key == KEY_ALPHA_SCALE
            ) {
                reload(p)
                if (packageName == PKG_SYSTEMUI) reapplyDrawableStack()
            }
        }
        listeners += listener
        runCatching { prefs.registerOnSharedPreferenceChangeListener(listener) }

        val mounted = ArrayList<String>()
        hookViewSurface(module, cl, mounted)
        if (hookBlurMaterialTokens(module, cl)) mounted += "BlurMaterial.token"
        if (packageName == PKG_SYSTEMUI) {
            hookBlurDrawableStack(module, cl, mounted)
        }
        android.util.Log.i(TAG, "installed in $packageName: ${mounted.size} surfaces $mounted")
        module.logD(TAG, "installed in $packageName: ${mounted.size} surfaces $mounted")
    }

    private fun reload(prefs: SharedPreferences, verbose: Boolean = false) {
        enabled = prefs.getBoolean(KEY_ENABLE, true)
        for (scene in BlurScene.entries) {
            radiusScales[scene.ordinal] =
                clampPercent(resolve(prefs, radiusKey(scene), KEY_RADIUS_SCALE)) / 100f
            alphaScales[scene.ordinal] =
                clampPercent(resolve(prefs, alphaKey(scene), KEY_ALPHA_SCALE)) / 100f
        }
        if (verbose) {
            android.util.Log.i(
                TAG,
                "config: enable=$enabled radius=${scaleDump(radiusScales)} alpha=${scaleDump(alphaScales)}",
            )
        }
    }

    /** 场景键 → 旧版全局键 → 默认值 三级回退 */
    private fun resolve(prefs: SharedPreferences, sceneKey: String, legacyKey: String): Int =
        if (prefs.contains(sceneKey)) prefs.getInt(sceneKey, DEFAULT_SCALE)
        else if (prefs.contains(legacyKey)) prefs.getInt(legacyKey, DEFAULT_SCALE)
        else DEFAULT_SCALE

    private fun scaleDump(scales: FloatArray): String =
        BlurScene.entries.joinToString { "${it.suffix}=${scales[it.ordinal]}x" }

    private fun clampPercent(v: Int): Int = v.coerceIn(MIN_SCALE, MAX_SCALE)

    private fun radiusScaleOf(scene: BlurScene): Float = radiusScales[scene.ordinal]
    private fun alphaScaleOf(scene: BlurScene): Float = alphaScales[scene.ordinal]

    // ---------- 场景识别 ----------

    /** 通知中心锚点（含 QS 容器：实机验证控制中心与通知中心共用面板背景表面） */
    private val SHADE_ANCHORS = setOf(
        "NotificationShade", "ShadeLayout", "NotificationStackScrollLayout",
        "PhoneStatusBarView", "VivoPanelViewHolder", "GlanceableHubContainer",
        "QSPanel", "QSContainerImpl", "QsFrame", "VivoQSCenter", "VivoQsStatusContainer",
        "QsPagedPage", "NonInterceptingScrollView", "PageIndicator",
        "QuickStatusBarHeader", "VivoQuickStatusBarHeader",
    )
    private val POPUP_ANCHORS = setOf(
        "VCustomRoundRectLayout", "VListPopupWindow", "VSpinner",
    )

    /** 类 → 场景锚点 缓存（ConcurrentHashMap 不存 null：只缓存命中类；
     *  非锚点类每次重新做字符串比较，开销为常数级可忽略） */
    private val anchorClassCache = ConcurrentHashMap<Class<*>, BlurScene>()

    /**
     * 场景判定：
     *  1. 类名锚点（自身类 + 父链，岛>音量>锁屏>通知中心/弹窗按匹配顺序）
     *  2. 弹窗锚点命中或无命中时，窗口类型兜底：音量窗口(2020) 优先归音量
     *  3. 通知卡片表面无锚点时归通知中心
     *  4. 其余 → 其他表面
     */
    private fun classifyScene(receiver: Any?): BlurScene {
        if (receiver !is View) return BlurScene.OTHER
        var classHit: BlurScene? = null
        var v: View? = receiver
        var depth = 0
        while (v != null && depth < MAX_PARENT_DEPTH) {
            matchAnchor(v.javaClass)?.let {
                classHit = it
                break
            }
            v = v.parent as? View
            depth++
        }
        when (classHit) {
            BlurScene.ISLAND, BlurScene.VOLUME, BlurScene.KEYGUARD, BlurScene.SHADE ->
                return classHit
            else -> { /* POPUP 或 null：进入兜底判定 */ }
        }
        if (windowTypeOf(receiver) == WINDOW_TYPE_VOLUME_OVERLAY) return BlurScene.VOLUME
        if (idSceneOf(receiver) == BlurScene.VOLUME) return BlurScene.VOLUME
        if (classHit == BlurScene.POPUP) return BlurScene.POPUP
        if (isNotificationSurface(receiver)) return BlurScene.SHADE
        return BlurScene.OTHER
    }

    /** 接收者自身资源条目名归场景（音量对话框背景 id=volume_dialog，与附着状态无关） */
    private fun idSceneOf(view: View): BlurScene? {
        val id = view.id
        if (id == 0 || id == View.NO_ID) return null
        if (idNameSceneCache.containsKey(id)) return idNameSceneCache[id]
        val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
        val hit = when (name) {
            "volume_dialog" -> BlurScene.VOLUME
            else -> null
        }
        idNameSceneCache[id] = hit
        return hit
    }

    private fun matchAnchor(cls: Class<*>): BlurScene? {
        anchorClassCache[cls]?.let { return it }
        val simple = cls.simpleName
        val hit = when {
            simple == "DynamicCapsuleContainer" -> BlurScene.ISLAND
            simple == "VivoVolumeSeekbar" || simple.startsWith("VolumeDialog") -> BlurScene.VOLUME
            simple.startsWith("Keyguard") || simple == "BouncerBlurView" ||
                simple == "ChargeBlurView" || simple == "BaseRealtimeBlurView" ||
                simple == "BlurRenderView" -> BlurScene.KEYGUARD
            simple in SHADE_ANCHORS -> BlurScene.SHADE
            simple in POPUP_ANCHORS -> BlurScene.POPUP
            else -> null
        }
        // 框架通用容器（android.* / androidx.*）可能出现在任意场景的层级中，不缓存
        val name = cls.name
        if (!name.startsWith("android.") && !name.startsWith("androidx.") && !name.startsWith("java.")) {
            hit?.let { anchorClassCache[cls] = it }
        }
        return hit
    }

    // ---------- 窗口类型识别（音量面板兜底） ----------

    private var getViewRootImplMethod: Method? = null
    private var windowAttributesField: Field? = null
    @Volatile
    private var windowTypeProbeFailed = false

    /**
     * 接收者所在窗口类型；反射失败一次后永久放弃（不影响其它判定路径）。
     * View.getViewRootImpl()（public @hide）+ ViewRootImpl.mWindowAttributes
     * （public final 字段，framework.jar 验证）。
     */
    private fun windowTypeOf(view: View): Int? {
        if (windowTypeProbeFailed) return null
        return runCatching {
            val m1 = getViewRootImplMethod
                ?: view.javaClass.getMethod("getViewRootImpl").also {
                    it.isAccessible = true
                    getViewRootImplMethod = it
                }
            val root = m1.invoke(view) ?: return null
            val f = windowAttributesField
                ?: root.javaClass.getField("mWindowAttributes").also {
                    windowAttributesField = it
                }
            (f.get(root) as? android.view.WindowManager.LayoutParams)?.type
        }.onFailure {
            windowTypeProbeFailed = true
            android.util.Log.i(TAG, "window type probe unavailable: $it")
        }.getOrNull()
    }

    // ---------- 通知卡片表面识别（v3 防透修复） ----------

    /** 类 → 是否通知卡片表面 缓存（同一 Class 只走一次继承链探测） */
    private val surfaceClassCache = java.util.Collections.synchronizedMap(HashMap<Class<*>, Boolean>())

    /**
     * 悬浮通知 / 锁屏通知 / 通知栏行 的材质载体：
     *   com.android.systemui.statusbar.notification.row.NotificationBackgroundView
     *   android.view.NotificationHeaderView（分组头）
     *   com.vivo.systemui.statusbar.notification.row.VivoNotificationBlockMenu
     * 按类名（含继承链）识别，不硬依赖 SystemUI 类。
     */
    private fun isNotificationSurface(receiver: Any?): Boolean {
        if (receiver == null) return false
        val cls = receiver.javaClass
        surfaceClassCache[cls]?.let { return it }
        var c: Class<*>? = cls
        var hit = false
        while (c != null && c != Any::class.java) {
            when (c.simpleName) {
                "NotificationBackgroundView", "NotificationHeaderView", "VivoNotificationBlockMenu" -> {
                    hit = true
                    break
                }
            }
            c = c.superclass
        }
        surfaceClassCache[cls] = hit
        return hit
    }

    /** 通知卡片遮罩不透明度的温和映射：滑块 0% 也保留 50% 设计浓度，防不可读 */
    private fun gentleAlphaScale(scene: BlurScene): Float = 0.5f + 0.5f * alphaScaleOf(scene)

    // ---------- android.view.View 材质门面（主挂面） ----------

    private fun hookViewSurface(module: HookEntry, cl: ClassLoader, mounted: MutableList<String>) {
        val viewClass = runCatching { cl.loadClass("android.view.View") }.getOrElse {
            android.util.Log.e(TAG, "load android.view.View failed: $it")
            return
        }

        // 门面：setMaterialAlpha / setMaterialBlurRadius / setMaterialCustomized(10/13/14)
        val facadeAlpha = hookFloat(module, viewClass, "setMaterialAlpha") { v, scene ->
            (v * alphaScaleOf(scene)).coerceIn(0f, 1f)
        }
        if (facadeAlpha) mounted += "View.setMaterialAlpha"

        val facadeRadius = hookFloat(module, viewClass, "setMaterialBlurRadius") { v, scene ->
            v * radiusScaleOf(scene)
        }
        if (facadeRadius) mounted += "View.setMaterialBlurRadius"

        val facadeC10 = hookCustomized(module, viewClass, "setMaterialCustomized", argCount = 10)
        if (facadeC10) mounted += "View.setMaterialCustomized(10)"
        val facadeC13 = hookCustomized(module, viewClass, "setMaterialCustomized", argCount = 13)
        if (facadeC13) mounted += "View.setMaterialCustomized(13)"
        val facadeC14 = hookCustomized(module, viewClass, "setMaterialCustomized", argCount = 14)
        if (facadeC14) mounted += "View.setMaterialCustomized(14)"

        // 遮罩融合：面板/锁屏遮罩不透明度（通知中心/锁屏「不透明度」的落点）
        // O6 新增带第三遮罩层（maskColor3/maskOpacity3/maskFusion3）的 12 参重载
        if (hookMaskFusion(module, viewClass, argCount = 9)) mounted += "View.setMaterialMaskFusion(9)"
        if (hookMaskFusion(module, viewClass, argCount = 12)) mounted += "View.setMaterialMaskFusion(12)"

        // token 型材质入口：记录接收者场景供 BlurMaterial 解析归因。
        // O6 起 setMaterial/setMaterialForce 新增 (style,contentType,materialPositionType) 三参主入口，
        // 两参/三参均挂（三参为实际主入口，两参委派带 0）
        if (hookMaterialTagger(module, viewClass, "setMaterial", argCount = 2)) mounted += "View.setMaterial(II)"
        if (hookMaterialTagger(module, viewClass, "setMaterial", argCount = 3)) mounted += "View.setMaterial(III)"
        if (hookMaterialTagger(module, viewClass, "setMaterialForce", argCount = 2)) mounted += "View.setMaterialForce(II)"
        if (hookMaterialTagger(module, viewClass, "setMaterialForce", argCount = 3)) mounted += "View.setMaterialForce(III)"

        // 回退：门面缺失的 ROM 改挂底层（View 与 VivoViewImpl 双探）。
        // 门面存在时不挂底层——门面内部委托底层，同时挂会二次缩放。
        val candidates = buildList {
            add(viewClass)
            runCatching { cl.loadClass("android.view.VivoViewImpl") }.getOrNull()?.let { add(it) }
        }
        if (!facadeAlpha) {
            for (c in candidates) {
                if (hookFloat(module, c, "setBlurAlpha") { v, scene ->
                        (v * alphaScaleOf(scene)).coerceIn(0f, 1f)
                    }) {
                    mounted += "${c.simpleName}.setBlurAlpha"
                }
                if (hookFloat(module, c, "setWindowBlurAlpha") { v, scene ->
                        (v * alphaScaleOf(scene)).coerceIn(0f, 1f)
                    }) {
                    mounted += "${c.simpleName}.setWindowBlurAlpha"
                }
            }
        }
        if (!facadeRadius) {
            for (c in candidates) {
                if (hookFloat(module, c, "setBlurRadius") { v, scene -> v * radiusScaleOf(scene) }) {
                    mounted += "${c.simpleName}.setBlurRadius"
                }
                if (hookFloat(module, c, "setWindowBlurRadius") { v, scene -> v * radiusScaleOf(scene) }) {
                    mounted += "${c.simpleName}.setWindowBlurRadius"
                }
            }
        }
        if (!facadeC10 && !facadeC13 && !facadeC14) {
            for (c in candidates) {
                for (name in arrayOf("setBlurCustomized", "setWindowBlurCustomized")) {
                    for (n in intArrayOf(10, 13, 14)) {
                        if (hookCustomized(module, c, name, argCount = n)) {
                            mounted += "${c.simpleName}.$name($n)"
                        }
                    }
                }
            }
        }
    }

    private fun hookFloat(
        module: HookEntry,
        cls: Class<*>,
        name: String,
        transform: (Float, BlurScene) -> Float,
    ): Boolean = runCatching {
        val method = cls.getDeclaredMethod(name, java.lang.Float.TYPE)
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (!enabled) return chain.proceed()
                // 通知卡片表面：此处传的是行动画 alpha（VivoNotificationBlurController
                // .updateBackgroundAlpha），缩放会破坏淡入淡出并触发 ROM 的
                // 「<=0.1 禁用模糊/兜底色」门控 → 悬浮/锁屏通知近乎全透明，跳过
                if (isNotificationSurface(chain.thisObject)) return chain.proceed()
                val args = chain.args.toTypedArray()
                if (args.isNotEmpty()) {
                    val before = args[0]
                    (args[0] as? Float)?.let {
                        args[0] = transform(it, classifyScene(chain.thisObject))
                    }
                    diag("float-$name", "cls=${(chain.thisObject as? View)?.javaClass?.simpleName} v=$before->${args[0]}")
                }
                return chain.proceed(args)
            }
        })
        true
    }.getOrElse {
        android.util.Log.i(TAG, "skip ${cls.name}.$name: $it")
        false
    }

    /**
     * setMaterialCustomized / setBlurCustomized / setWindowBlurCustomized：
     * 10 参 = (radius, saturation, c1, o1, f1, c2, o2, f2, finalOpacity, finalFusion)
     * 13 参 = 10 参在 finalOpacity 前插入 (c3, o3, f3)，finalOpacity 移到 [11]
     * 14 参（O6 新增）= 13 参 + 末尾 materialPositionType
     */
    private fun hookCustomized(
        module: HookEntry,
        cls: Class<*>,
        name: String,
        argCount: Int,
    ): Boolean = runCatching {
        val base = arrayOf<Class<*>>(
            java.lang.Float.TYPE, java.lang.Float.TYPE, Integer.TYPE,
            java.lang.Float.TYPE, Integer.TYPE, Integer.TYPE,
            java.lang.Float.TYPE, Integer.TYPE, java.lang.Float.TYPE, Integer.TYPE,
        )
        val params = when (argCount) {
            10 -> base
            13 -> base.copyOfRange(0, 8) + arrayOf<Class<*>>(
                Integer.TYPE, java.lang.Float.TYPE, Integer.TYPE,
                java.lang.Float.TYPE, Integer.TYPE,
            )
            else -> base.copyOfRange(0, 8) + arrayOf<Class<*>>(
                Integer.TYPE, java.lang.Float.TYPE, Integer.TYPE,
                java.lang.Float.TYPE, Integer.TYPE, Integer.TYPE,
            )
        }
        val method = cls.getDeclaredMethod(name, *params)
        val finalOpacityIndex = if (argCount == 10) 8 else 11
        val opacityIndexes = if (argCount == 10) intArrayOf(3, 6) else intArrayOf(3, 6, 9)
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (!enabled) return chain.proceed()
                val notif = isNotificationSurface(chain.thisObject)
                val scene = classifyScene(chain.thisObject)
                val args = chain.args.toTypedArray()
                (args[0] as? Float)?.let { args[0] = it * radiusScaleOf(scene) }
                for (i in opacityIndexes) {
                    (args[i] as? Float)?.let { v ->
                        // 通知卡片遮罩本就很淡（0.2/0.4/0.25/0.6），直接 ×scale 会
                        // 与行动画二次叠加导致不可读 → 温和映射，最低留 50% 设计值
                        args[i] = if (notif) {
                            (v * gentleAlphaScale(scene)).coerceIn(0f, 1f)
                        } else {
                            (v * alphaScaleOf(scene)).coerceIn(0f, 1f)
                        }
                    }
                }
                (args[finalOpacityIndex] as? Float)?.let {
                    // 通知卡片 finalOpacity 恒为 1.0，缩放即整卡半透明 → 不缩放
                    args[finalOpacityIndex] = if (notif) it else (it * alphaScaleOf(scene)).coerceIn(0f, 1f)
                }
                diag(
                    "custom$argCount",
                    "cls=${(chain.thisObject as? View)?.javaClass?.simpleName} scene=$scene notif=$notif " +
                        "r=${args[0]} sat=${args[1]} ops=${opacityIndexes.joinToString(",") { args[it].toString() }} " +
                        "fin=${args[finalOpacityIndex]}",
                )
                return chain.proceed(args)
            }
        })
        true
    }.getOrElse {
        android.util.Log.i(TAG, "skip ${cls.name}.$name($argCount): $it")
        false
    }

    /**
     * setMaterialMaskFusion(F,I,F,I,I,F,I,F,I)：
     * 对照 framework.jar View.java 与 customized(10)，即去掉 blurRadius 的 customized：
     *   [0]=saturation（10.0/20.0 等，不缩放）
     *   [1]=c1 [2]=o1 [3]=f1 [4]=c2 [5]=o2 [6]=f2 [7]=finalOpacity [8]=finalFusion
     * 遮罩不透明度（[2]/[5]）与最终不透明度（[7]）归入场景「不透明度」。
     * 实机调用点：VivoDynamicSurfaceBlurBg（面板背景遮罩 0.6/0.24 + finalOpacity 1.0，
     * 通知中心/锁屏面板「不透明度」此前无挂点不生效的根因）、
     * BaseRealtimeBlurView/BouncerBlurView（锁屏/解锁面板，finalOpacity 1.0）。
     * 通知卡片无此调用路径（VivoNotificationBlurController 仅用 Force/Customized），
     * 不做温和映射。Bundle 重载极少用，不挂。
     */
    /**
     * setMaterialMaskFusion：
     * 9 参 = (saturation, c1, o1, f1, c2, o2, f2, finalOpacity, finalFusion)
     * 12 参（O6 新增）= 9 参在 finalOpacity 前插入 (c3, o3, f3)
     * 缩放遮罩不透明度：9 参 [2][5][7]，12 参 [2][5][8][10]。
     */
    private fun hookMaskFusion(module: HookEntry, cls: Class<*>, argCount: Int): Boolean = runCatching {
        val base = arrayOf<Class<*>>(
            java.lang.Float.TYPE, Integer.TYPE, java.lang.Float.TYPE,
            Integer.TYPE, Integer.TYPE, java.lang.Float.TYPE,
            Integer.TYPE,
        )
        val params = if (argCount == 9) {
            base + arrayOf<Class<*>>(java.lang.Float.TYPE, Integer.TYPE)
        } else {
            base + arrayOf<Class<*>>(
                Integer.TYPE, java.lang.Float.TYPE, Integer.TYPE,
                java.lang.Float.TYPE, Integer.TYPE,
            )
        }
        val method = cls.getDeclaredMethod("setMaterialMaskFusion", *params)
        val opacityIndexes = if (argCount == 9) intArrayOf(2, 5, 7) else intArrayOf(2, 5, 8, 10)
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (!enabled) return chain.proceed()
                val scene = classifyScene(chain.thisObject)
                val scale = alphaScaleOf(scene)
                val args = chain.args.toTypedArray()
                for (i in opacityIndexes) {
                    (args[i] as? Float)?.let { args[i] = (it * scale).coerceIn(0f, 1f) }
                }
                diag(
                    "maskfusion$argCount",
                    "cls=${(chain.thisObject as? View)?.javaClass?.simpleName} scene=$scene " +
                        "sat=${args[0]} ops=${opacityIndexes.joinToString(",") { args[it].toString() }}",
                )
                return chain.proceed(args)
            }
        })
        true
    }.getOrElse {
        android.util.Log.i(TAG, "skip View.setMaterialMaskFusion($argCount): $it")
        false
    }

    /** setMaterial/setMaterialForce：token 型材质入口，记录接收者场景（O6 主入口为三参） */
    private fun hookMaterialTagger(module: HookEntry, cls: Class<*>, name: String, argCount: Int): Boolean = runCatching {
        val params = Array(argCount) { Integer.TYPE }
        val method = cls.getDeclaredMethod(name, *params)
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val scene = if (enabled) classifyScene(chain.thisObject) else null
                if (scene != null) threadScene.set(scene)
                return try {
                    chain.proceed()
                } finally {
                    threadScene.remove()
                }
            }
        })
        true
    }.getOrElse {
        android.util.Log.i(TAG, "skip ${cls.name}.$name: $it")
        false
    }

    // ---------- 材质 token 解析（BlurMaterial 76 常量） ----------

    private fun hookBlurMaterialTokens(module: HookEntry, cl: ClassLoader): Boolean = runCatching {
        val geometryClass = cl.loadClass("android.view.blur.IVivoBlurRender\$BlurGeometry")
        val blurMaterial = cl.loadClass("android.view.blur.BlurMaterial")
        val resolve: Method = blurMaterial.getDeclaredMethod(
            "resolveBlurGeometryByStyle", geometryClass, Integer.TYPE
        )
        module.hook(resolve).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (enabled) {
                    // setMaterial(II)/setMaterialForce(II) 调用栈内携带场景；
                    // 栈外解析（无法归因）回落「其他表面」
                    val scene = threadScene.get() ?: BlurScene.OTHER
                    val geometry = chain.args.getOrNull(0) ?: return result
                    diag("token", "scene=$scene before.r=${runCatching { geometry.javaClass.getField("mBlurRadius").getFloat(geometry) }.getOrNull()}")
                    scaleFloatField(geometry, "mBlurRadius", radiusScaleOf(scene), clamp01 = false)
                    scaleFloatField(geometry, "mMaskOpacity_1", alphaScaleOf(scene), clamp01 = true)
                    scaleFloatField(geometry, "mMaskOpacity_2", alphaScaleOf(scene), clamp01 = true)
                    // O6 新增第三遮罩层不透明度（BlurGeometry.mMaskOpacity_3）
                    scaleFloatField(geometry, "mMaskOpacity_3", alphaScaleOf(scene), clamp01 = true)
                    scaleFloatField(geometry, "mFinalOpacity", alphaScaleOf(scene), clamp01 = true)
                }
                return result
            }
        })
        true
    }.getOrElse {
        android.util.Log.i(TAG, "skip BlurMaterial token hook: $it")
        false
    }

    private fun scaleFloatField(target: Any, name: String, scale: Float, clamp01: Boolean) {
        runCatching {
            val field = synchronized(geometryFields) {
                if (geometryFields.containsKey(name)) geometryFields[name]
                else runCatching { target.javaClass.getField(name) }.getOrNull()
                    .also { geometryFields[name] = it }
            } ?: return
            val v = field.getFloat(target)
            val scaled = v * scale
            field.setFloat(target, if (clamp01) scaled.coerceIn(0f, 1f) else scaled)
        }
    }

    // ---------- SystemUI 私有 Drawable 模糊栈（O6：字段直写） ----------

    /** 每实例原始值（mBlurRadius 与 Paint alpha），保证幂等重放 */
    private data class DrawableOriginal(
        val blurRadius: Int,
        val paintAlphas: Map<String, Int>,
    )

    private val drawableOriginals =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, DrawableOriginal>())
    private val aggregatorRef = java.util.concurrent.atomic.AtomicReference<Any?>()

    /** VivoBackgroundBlurDrawable 的三个 Paint（public final，alpha 可变） */
    private val PAINT_FIELDS = arrayOf("mPaint", "mDisableBlurPaint", "mFallbackPaint")

    /**
     * O6 Drawable 栈：VivoBackgroundBlurDrawable 的 setBlurRadius(int)/setColor/setFallbackColor
     * 已删除，改由 BlurDrawableController.getDrawable(Context) 创建时直接写字段
     * （mBlurRadius=300、mPaint/mDisableBlurPaint 取资源色）。每次 getDrawable 都是新建
     * drawable → after-hook 缩放即幂等。窗口级遮罩无视图层级可归类 → 「通知中心」子项。
     */
    private fun hookBlurDrawableStack(module: HookEntry, cl: ClassLoader, mounted: MutableList<String>) {
        val controller = runCatching { cl.loadClass("com.vivo.blur.BlurDrawableController") }
            .getOrElse {
                android.util.Log.i(TAG, "skip drawable stack: $it")
                return
            }
        val getDrawable = runCatching {
            controller.getDeclaredMethod("getDrawable", android.content.Context::class.java)
        }.getOrElse {
            android.util.Log.i(TAG, "skip BlurDrawableController.getDrawable: $it")
            return
        }
        module.hook(getDrawable).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (enabled && result != null) {
                    scaleDrawable(result)
                    aggregatorRef.set(
                        runCatching { controller.getField("blurRegionAggregator").get(null) }.getOrNull()
                    )
                }
                return result
            }
        })
        mounted += "Drawable.getDrawable"
    }

    /** 配置变更时对 Aggregator 内已创建的 drawable 重放缩放（幂等） */
    private fun reapplyDrawableStack() {
        if (!enabled) return
        val aggregator = aggregatorRef.get() ?: return
        runCatching {
            val drawables =
                aggregator.javaClass.getField("mDrawables").get(aggregator) as? Collection<*> ?: return
            for (d in drawables) if (d != null) scaleDrawable(d)
        }
    }

    /** 缩放单个 drawable：mBlurRadius × 半径缩放；Paint alpha × 不透明度缩放 */
    private fun scaleDrawable(drawable: Any) {
        if (radiusScaleOf(BlurScene.SHADE) == 1f && alphaScaleOf(BlurScene.SHADE) == 1f) return
        val cls = drawable.javaClass
        val originals = drawableOriginals.getOrPut(drawable) {
            DrawableOriginal(
                blurRadius = runCatching { cls.getField("mBlurRadius").getInt(drawable) }.getOrDefault(0),
                paintAlphas = PAINT_FIELDS.associateWith { name ->
                    runCatching {
                        (cls.getField(name).get(drawable) as? android.graphics.Paint)?.alpha ?: -1
                    }.getOrDefault(-1)
                },
            )
        }
        runCatching {
            val newR = (originals.blurRadius * radiusScaleOf(BlurScene.SHADE)).toInt().coerceAtLeast(0)
            cls.getField("mBlurRadius").setInt(drawable, newR)
            diag("drawable", "cls=${cls.simpleName} r=${originals.blurRadius}->$newR")
        }
        val alphaScale = alphaScaleOf(BlurScene.SHADE)
        for ((name, origAlpha) in originals.paintAlphas) {
            if (origAlpha < 0) continue
            runCatching {
                val paint = cls.getField(name).get(drawable) as? android.graphics.Paint ?: return@runCatching
                paint.alpha = (origAlpha * alphaScale).toInt().coerceIn(0, 255)
            }
        }
    }
}
