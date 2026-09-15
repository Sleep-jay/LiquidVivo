package com.FucVivo.xposed.hooks

import android.content.SharedPreferences
import com.FucVivo.R
import com.FucVivo.xposed.AppHooker
import com.FucVivo.xposed.HookEntry
import com.FucVivo.xposed.HookFeature
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * vivo 系统升级（com.bbk.updater 4.9.20.3）去打扰：
 * 目标类均未混淆，按类名+方法名反射挂载（签名校验防版本漂移）。
 *
 *  - nocheck  断自动检查：UpdateCheckStrategy.startAutoCheck(Z,CheckTrigeType)V no-op
 *             （只断自动触发路径，手动"检查更新"不受影响；闹钟仍触发但空调用）
 *  - noinduce 断诱导弹窗/通知：InduceUtils.allowToInduceTimeZones()Z → false
 *             （下载/安装/重启三类诱导的总时间窗闸门）
 *  - nosmart  断智能安装：SmartInstallStrategy.onAppFeaterSetUp()Z → false
 *             （策略不初始化，夜间自动重启安装不会注册；基类默认实现即返回 false）
 *  - nored    去升级红点：RedNoticeStrategy.onAppFeaterSetUp()Z → false（断未来状态机）
 *             + CommonUtils.setRedNotice(Context,I)V 参数强制 0（红点是持久化在
 *             Settings.System bbk_update_notice 的数据，拦策略不清历史状态，必须拦写入点）
 *             + AppFeature.onCreate 后主动调 setRedNotice(ctx,0) 清存量红点
 *  - noself   断应用自升级：SelfUpgradeStrategy.onAppFeaterSetUp()Z → false
 *  - copylink "下载并安装"改为复制下载链接：DownloadInfoManager(g0/a).t(ArrayList,ArrayList)V
 *             是新下载总入口（元素 e3/a 包对象）。redir.do 是一次性签名地址，外部请求 500，
 *             故复刻 g0/a$e.run 的解析链：getUrlFromGet→getDlRequestParams→getResponse(POST)
 *             →getDlRedir 解出真实 CDN 直链再复制。解析失败回退原始地址/Toast 提示。
 *             （g0/a 为混淆名，挂载时按方法签名校验，失配则放弃该功能）
 */
object UpdaterHooker : AppHooker {

    override val packageName: String = "com.bbk.updater"
    override val appNameRes: Int = R.string.updater_app_name
    override val featureSummaryRes: Int = R.string.updater_app_feature
    override val adaptedVersion: String = "4.9.28.3"

    override val hookFeatureTitleRes: Int = R.string.updater_feature_title
    override val hookFeatures: List<HookFeature> = listOf(
        HookFeature("nocheck", "禁止自动检查更新", defaultOn = true),
        HookFeature("noinduce", "禁止升级诱导弹窗/通知", defaultOn = true),
        HookFeature("nosmart", "禁止智能安装（夜间自动重启）", defaultOn = true),
        HookFeature("nored", "禁止升级红点提醒", defaultOn = true),
        HookFeature("noself", "禁止系统升级应用自升级", defaultOn = true),
        HookFeature("copylink", "「下载并安装」改为复制下载链接", defaultOn = false),
        HookFeature("fullpkg", "检查更新请求全量包（配合复制链接拿全量直链）", defaultOn = false),
    )

    private const val TAG = "FucVivoUpdater"

    private const val CLS_CHECK_STRATEGY = "com.bbk.updater.check.strategy.UpdateCheckStrategy"
    private const val CLS_INDUCE_UTILS = "com.bbk.updater.utils.InduceUtils"
    private const val CLS_SMART_INSTALL = "com.bbk.updater.install.strategy.SmartInstallStrategy"
    private const val CLS_RED_NOTICE = "com.bbk.updater.strategy.RedNoticeStrategy"
    private const val CLS_SELF_UPGRADE = "com.bbk.updater.selfupgrade.SelfUpgradeStrategy"
    private const val CLS_COMMON_UTILS = "com.bbk.updater.utils.CommonUtils"
    private const val CLS_APP_FEATURE = "com.bbk.updater.AppFeature"

    /** DownloadInfoManager（4.9.28.3 混淆名 e0.a；旧版 4.9.20.3 为 g0.a），挂载时按签名校验 */
    private const val CLS_DOWNLOAD_MGR = "e0.a"

    private var prefs: SharedPreferences? = null

    /** updater 进程的 Application context（AppFeature.onCreate 捕获，供 Toast/剪贴板用） */
    @Volatile
    private var appCtx: android.content.Context? = null

    override fun onPackageLoaded(module: HookEntry, param: XposedModuleInterface.PackageLoadedParam) {
        if (!param.isFirstPackage) return
        prefs = module.hookedPrefs
        val cl = param.defaultClassLoader

        if (enabled("nocheck")) {
            runCatching {
                val m = findMethod(cl, CLS_CHECK_STRATEGY, "startAutoCheck", 2, static = false)
                interceptNoOp(module, m, "startAutoCheck")
            }.onFailure { module.logE(TAG, "nocheck hook failed: $it") }
        }

        if (enabled("noinduce")) {
            runCatching {
                val m = findMethod(cl, CLS_INDUCE_UTILS, "allowToInduceTimeZones", 0, static = null)
                interceptReturn(module, m, false, "allowToInduceTimeZones")
            }.onFailure { module.logE(TAG, "noinduce hook failed: $it") }
        }

        // 三个策略类统一在 onAppFeaterSetUp 闸掉（基类默认实现即 return false，
        // 不 proceed 等价于策略从未注册，语义与基类一致）
        for ((key, cls) in mapOf(
            "nosmart" to CLS_SMART_INSTALL,
            "nored" to CLS_RED_NOTICE,
            "noself" to CLS_SELF_UPGRADE,
        )) {
            if (!enabled(key)) continue
            runCatching {
                val m = findMethod(cl, cls, "onAppFeaterSetUp", 0, static = false)
                interceptReturn(module, m, false, "$cls.onAppFeaterSetUp")
            }.onFailure { module.logE(TAG, "$key hook failed: $it") }
        }

        // 红点是持久化数据（Settings.System bbk_update_notice），只拦策略不清存量：
        // 1) 拦截写入点 setRedNotice(Context,I)，参数强制 0（任何路径想再点亮都会被改写成熄灭）
        // 2) AppFeature.onCreate 后主动调一次 setRedNotice(ctx, 0) 清掉历史红点
        if (enabled("nored")) {
            runCatching {
                val cls = cl.loadClass(CLS_COMMON_UTILS)
                val m = cls.getDeclaredMethod(
                    "setRedNotice",
                    android.content.Context::class.java,
                    Int::class.javaPrimitiveType,
                )
                interceptCoerceRedNotice(module, m)
            }.onFailure { module.logE(TAG, "nored setRedNotice hook failed: $it") }
        }

        // AppFeature.onCreate 常驻挂载：捕获 appCtx（Toast/剪贴板用），并按需清红点
        runCatching {
            val cls = cl.loadClass(CLS_APP_FEATURE)
            // getMethod 含继承：AppFeature 未覆写时挂到 android.app.Application.onCreate 也安全（本 hook 仅作用于 updater 进程）
            val m = cls.getMethod("onCreate")
            hookAppCreate(module, m, cl)
        }.onFailure { module.logE(TAG, "AppFeature hook failed: $it") }

        if (enabled("copylink")) {
            runCatching {
                val cls = cl.loadClass(CLS_DOWNLOAD_MGR)
                // 新下载入口（按钮/自动下载）：t(active, passive)，元素为 e3/a 包对象
                val t = cls.getDeclaredMethod("t", java.util.ArrayList::class.java, java.util.ArrayList::class.java)
                interceptCopyLinkList(module, t)
            }.onFailure { module.logE(TAG, "copylink hook failed (DownloadInfoManager 混淆名漂移?): $it") }
        }

        // fullpkg：检查请求 isFull 强制 true（HttpUtils 未混淆；args[2]=isFull，签名 (Context,Z isMan,Z isFull,Trige,Z,Z)）
        // 服务器对 isFull=1 返回全量包信息；OTA 只提供最新版本，旧版本无法指定
        runCatching {
            val cls = cl.loadClass("com.bbk.updater.utils.HttpUtils")
            val m = cls.getDeclaredMethod(
                "getUpdateRequestParams",
                android.content.Context::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                cl.loadClass("com.vivo.updaterbaseframe.utils.BaseFrameUtils\$CheckTrigeType"),
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            )
            module.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // chain.args 直接赋值不生效，必须 proceed(新数组)（同 setRedNotice 强制 0 的写法）
                    if (enabled("fullpkg") && chain.args.size > 2) {
                        module.logD(TAG, "forced isFull=true")
                        val n = chain.args.size
                        val newArgs = Array<Any?>(n) { i -> if (i == 2) java.lang.Boolean.TRUE else chain.args[i] }
                        return chain.proceed(newArgs)
                    }
                    return chain.proceed()
                }
            })
        }.onFailure { module.logE(TAG, "fullpkg hook failed: $it") }
    }

    /**
     * 按类名+方法名+参数个数找方法。
     * static=true 只要静态，false 只要实例，null 不限。
     */
    private fun findMethod(
        cl: ClassLoader, className: String, methodName: String,
        paramCount: Int, static: Boolean?,
    ): Method {
        val cls = cl.loadClass(className)
        val candidates = cls.declaredMethods.filter { m ->
            m.name == methodName && m.parameterCount == paramCount &&
                (static == null || Modifier.isStatic(m.modifiers) == static)
        }
        if (candidates.isEmpty()) {
            throw NoSuchMethodException("$className.$methodName/$paramCount (Updater updated?)")
        }
        return candidates[0]
    }

    /** void 方法：跳过原实现 */
    private fun interceptNoOp(module: HookEntry, method: Method, label: String) {
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (enabled(featureOf(label))) {
                    module.logD(TAG, "blocked $label")
                    return null
                }
                return chain.proceed()
            }
        })
    }

    /** 返回固定值，跳过原实现 */
    private fun interceptReturn(module: HookEntry, method: Method, value: Any?, label: String) {
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (enabled(featureOf(label))) {
                    module.logD(TAG, "forced $label -> $value")
                    return value
                }
                return chain.proceed()
            }
        })
    }

    /** setRedNotice(Context,I)：开启 nored 时把状态参数改写为 0 再走原逻辑（顺带熄灭存量） */
    private fun interceptCoerceRedNotice(module: HookEntry, method: Method) {
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (enabled("nored") && chain.args.size == 2 && chain.args[1] is Int && chain.args[1] != 0) {
                    module.logD(TAG, "coerce setRedNotice(${chain.args[1]}) -> 0")
                    return chain.proceed(arrayOf(chain.args[0], 0))
                }
                return chain.proceed()
            }
        })
    }

    /** AppFeature.onCreate：常驻捕获 appCtx；nored 开启时主动清一次红点（写 0 走 setRedNotice 原逻辑，含 Settings.System + provider/广播推送） */
    private fun hookAppCreate(module: HookEntry, method: Method, cl: ClassLoader) {
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val r = chain.proceed()
                val ctx = chain.thisObject as? android.content.Context
                if (ctx != null) appCtx = ctx.applicationContext
                if (ctx != null && enabled("nored")) {
                    runCatching {
                        val m = cl.loadClass(CLS_COMMON_UTILS).getDeclaredMethod(
                            "setRedNotice",
                            android.content.Context::class.java,
                            Int::class.javaPrimitiveType,
                        )
                        m.isAccessible = true
                        m.invoke(null, ctx.applicationContext, 0)
                        module.logD(TAG, "cleared red notice on AppFeature.onCreate")
                    }.onFailure { module.logE(TAG, "clear red notice failed: $it") }
                }
                return r
            }
        })
    }

    /** DownloadInfoManager.t(active, passive)：元素为 e3/a 包对象；收集后解析真实链接复制，不走真实下载 */
    private fun interceptCopyLinkList(module: HookEntry, method: Method) {
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (!enabled("copylink")) return chain.proceed()
                val items = mutableListOf<Any>()
                for (arg in chain.args) {
                    val list = arg as? java.util.ArrayList<*> ?: continue
                    for (item in list) if (item != null) items.add(item)
                }
                if (items.isEmpty()) return chain.proceed()
                if (!copyResolvedUrls(module, items)) return chain.proceed()
                return null
            }
        })
    }

    /**
     * 后台解析各包真实 CDN 链接 → 剪贴板 + 主线程 Toast；失败返回 false（调用方兜底走原逻辑）。
     * redir.do 是一次性签名地址（外部直接请求 500）。真实链路（g0/a$e.run 同款）：
     *   1. url  = HttpUtils.getUrlFromGet(info.getDownloadURL())
     *   2. par  = HttpUtils.getDlRequestParams(ctx, dlUrl, version, !resumed, dtype, active, elapsed, trial, timeStamp)
     *   3. body = HttpUtils.getResponse(ctx, url, par, "POST", true, 10000)
     *   4. real = HttpUtils.getDlRedir(ctx, body)   // 解密响应体取 JSON.data
     */
    private fun copyResolvedUrls(module: HookEntry, items: List<Any>): Boolean {
        val ctx = appCtx ?: return false
        Thread {
            val resolved = items.mapNotNull { resolveRealUrl(module, ctx, it) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val text = resolved.joinToString("\n")
                    if (text.isEmpty()) {
                        android.widget.Toast.makeText(ctx, "解析下载链接失败", android.widget.Toast.LENGTH_LONG).show()
                        return@post
                    }
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("OTA 下载链接", text))
                    android.widget.Toast.makeText(ctx, "已复制下载链接", android.widget.Toast.LENGTH_LONG).show()
                    module.logD(TAG, "copied OTA url: ${text.take(80)}")
                }.onFailure { module.logE(TAG, "clipboard/toast failed: $it") }
            }
        }.start()
        return true
    }

    /** 复刻下载任务的 redir 解析；失败回退原始 getDownloadURL() */
    private fun resolveRealUrl(module: HookEntry, ctx: android.content.Context, item: Any): String? {
        return runCatching {
            val info = item.javaClass.getMethod("a").invoke(item) ?: return@runCatching null
            val dlUrl = info.javaClass.getMethod("getDownloadURL").invoke(info) as? String
            if (dlUrl.isNullOrEmpty()) return@runCatching null
            val http = ctx.classLoader.loadClass("com.bbk.updater.utils.HttpUtils")
            val version = (info.javaClass.getMethod("getVersion").invoke(info) as? String) ?: ""
            val dtype = (item.javaClass.getMethod("c").invoke(item) as? String) ?: ""
            val resumed = (item.javaClass.getMethod("g").invoke(item) as? Boolean) ?: false
            val active = (info.javaClass.getMethod("isActivePackage").invoke(info) as? Boolean) ?: true
            val trial = (info.javaClass.getMethod("isTrialVersion").invoke(info) as? Boolean) ?: false
            val ts = runCatching { info.javaClass.getMethod("getTimeStamp").invoke(info) as String }.getOrDefault("0")
            val url = http.getDeclaredMethod("getUrlFromGet", String::class.java).invoke(null, dlUrl) as? String
            val params = http.getDeclaredMethod(
                "getDlRequestParams",
                android.content.Context::class.java, String::class.java, String::class.java,
                Boolean::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
            ).invoke(null, ctx, dlUrl, version, !resumed, dtype, active, 0L, trial, ts) as? String
            val body = http.getDeclaredMethod(
                "getResponse",
                android.content.Context::class.java, String::class.java, String::class.java,
                String::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            ).invoke(null, ctx, url, params, "POST", true, 10000L) as? String
            val real = http.getDeclaredMethod("getDlRedir", android.content.Context::class.java, String::class.java)
                .invoke(null, ctx, body) as? String
            module.logD(TAG, "redir resolved: ${real?.take(80)}")
            when {
                !real.isNullOrEmpty() && real.startsWith("http") -> real
                else -> dlUrl
            }
        }.getOrElse {
            module.logE(TAG, "resolve real url failed: $it")
            null
        }
    }

    /** label → feature key（用于 hook 内实时读取开关，支持注入后改开关） */
    private fun featureOf(label: String): String = when {
        label.startsWith("startAutoCheck") -> "nocheck"
        label.startsWith("allowToInduce") -> "noinduce"
        label.startsWith(CLS_SMART_INSTALL) -> "nosmart"
        label.startsWith(CLS_RED_NOTICE) -> "nored"
        else -> "noself"
    }

    private fun enabled(key: String): Boolean =
        runCatching { prefs?.getBoolean("enable_$key", true) }.getOrNull() != false
}
