package com.FucVivo.xposed.hooks.settings

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.FucVivo.xposed.HookEntry
import java.io.File
import java.io.FileOutputStream
import java.util.WeakHashMap

/**
 * OriginOS 6 关于手机页（VivoAboutPhoneFragment）自定义：
 * - 点击背景图：系统选图 → 拖动/双指缩放裁切（固定输出尺寸）→ 替换 origin_logo_image_bg 背景
 * - 点击设备信息行值：弹与系统"修改设备名称"一致的 VDialog 修改 summary 文本
 *
 * O6 页面重构为 Fragment + Controller 架构：行 = VivoDeviceInfoLayout（id: title/summary），
 * 按 view id 定位行（旧版直接字段已不存在）。背景卡 vv_origin_ocean_card_view 内
 * origin_logo_image（前景 logo）+ origin_logo_image_bg（背景图，替换目标）。
 * 弹窗库 VDialogBuilder、androidx Fragment.onActivityResult 保持不变。
 *
 * 所有资源/类均通过 Settings 进程自身加载（运行时 getIdentifier + 反射），
 * 保证弹窗与系统原生样式完全一致。
 */
object AboutPageCustomizer {

    private const val TAG = "FucVivoAbout"
    private const val PREFS = "fucvivo_about"
    private const val BG_FILE = "about_bg.png"
    const val REQ_PICK_BG = 0x3F21

    /** 可编辑文本组：key → (标题, O6 行 view id 资源名) */
    private class Group(val title: String, val viewIdName: String)

    private val TEXT_GROUPS = linkedMapOf(
        "title" to Group("设备型号", "product_model"),
        "cpu" to Group("处理器", "device_info_cpu"),
        "ram" to Group("运行内存", "device_info_ram"),
        "storage" to Group("手机存储", "device_info_storage"),
        "compress" to Group("存储压缩", "device_info_storage_compress"),
        "chip" to Group("芯片", "device_info_chip_v1"),
        "battery" to Group("电池容量", "device_info_battery"),
        "imaging" to Group("影像", "device_info_chip_image_v2"),
    )

    /** 原始文本备份（清空输入时恢复）：key → original text */
    private val originalTexts = HashMap<String, String>()

    /** 已 attach 的 fragment 实例（弱引用，View 重建后重新 attach） */
    private val attachedFragments = WeakHashMap<Any, Boolean>()

    /** 功能开关 prefs（hook 侧由 SettingsHooker 注入，经 getRemotePreferences 读取） */
    @Volatile
    var hookPrefs: android.content.SharedPreferences? = null

    /** 总开关"关于手机自定义"，默认开；prefs 不可用时也视为开 */
    fun customEnabled(): Boolean = hookPrefs?.getBoolean("enable_about", true) != false

    fun bgActive(fragment: Any): Boolean = customEnabled() && hasCustomBackground(fragment)

    // ---------------- 入口 ----------------

    fun attach(module: HookEntry, fragment: Any) {
        if (attachedFragments.containsKey(fragment)) return
        attachedFragments[fragment] = true
        module.logD(TAG, "attach ${fragment.javaClass.simpleName}")
        if (!customEnabled()) return
        val ctx = fragmentContext(fragment) ?: return
        val root = fragmentRootView(fragment) ?: return
        val pkg = ctx.packageName
        val res = ctx.resources

        // 文本行：找到行内 summary 装点击监听；先备份原文
        for ((key, group) in TEXT_GROUPS) {
            val summary = summaryView(root, res, pkg, group.viewIdName) ?: continue
            summary.setOnClickListener { showTextEditDialog(module, fragment, key) }
            if (originalTexts[key] == null) {
                summary.text?.toString()?.takeIf(String::isNotEmpty)?.let { originalTexts[key] = it }
            }
        }

        // 背景图：点击换图（O6 背景 = origin_logo_image_bg ImageView）
        val bgId = res.getIdentifier("origin_logo_image_bg", "id", pkg)
        val bgClick = View.OnClickListener { pickBackgroundImage(module, fragment) }
        (root.findViewById<View>(bgId) as? ImageView)?.setOnClickListener(bgClick)

        applyAll(fragment)
    }

    /** 行内 summary 值视图：按 view id 找到行，取行内 R.id.summary */
    private fun summaryView(root: View, res: android.content.res.Resources, pkg: String, viewIdName: String): TextView? {
        val rowId = res.getIdentifier(viewIdName, "id", pkg)
        val row = root.findViewById<View>(rowId) ?: return null
        val summaryId = res.getIdentifier("summary", "id", pkg)
        return row.findViewById<View>(summaryId) as? TextView
    }

    /** 取组的当前显示值：优先 prefs 覆盖，其次原文备份，最后行 summary 实时文本 */
    private fun currentValue(fragment: Any, ctx: Context, key: String): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("text_$key", null)?.let { return it }
        originalTexts[key]?.let { return it }
        return valueText(fragment, ctx, TEXT_GROUPS[key]) ?: ""
    }

    private fun valueText(fragment: Any, ctx: Context, group: Group?): String? {
        if (group == null) return null
        val root = fragmentRootView(fragment) ?: return null
        return summaryView(root, ctx.resources, ctx.packageName, group.viewIdName)
            ?.text?.toString()?.takeIf(String::isNotEmpty)
    }

    fun applyAll(fragment: Any) {
        if (!customEnabled()) return
        val ctx = fragmentContext(fragment) ?: return
        val root = fragmentRootView(fragment) ?: return
        val pkg = ctx.packageName
        val res = ctx.resources
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((key, group) in TEXT_GROUPS) {
            val value = prefs.getString("text_$key", null) ?: continue
            // 覆盖前先备份原文，保证"清空恢复默认"可用
            if (originalTexts[key] == null) valueText(fragment, ctx, group)?.let { originalTexts[key] = it }
            summaryView(root, res, pkg, group.viewIdName)?.text = value
        }
        if (bgActive(fragment)) applyCustomBackground(fragment)
    }

    fun hasCustomBackground(fragment: Any): Boolean {
        val ctx = fragmentContext(fragment) ?: return false
        return File(ctx.filesDir, BG_FILE).let { it.exists() && it.length() > 0 }
    }

    fun applyCustomBackground(fragment: Any) {
        val ctx = fragmentContext(fragment) ?: return
        val root = fragmentRootView(fragment) ?: return
        val pkg = ctx.packageName
        val file = File(ctx.filesDir, BG_FILE)
        val bm = BitmapFactory.decodeFile(file.absolutePath) ?: return
        // 圆角裁切（24dp，与卡片圆角一致）
        val radius = 24f * ctx.resources.displayMetrics.density
        val roundOutline = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        val bgId = ctx.resources.getIdentifier("origin_logo_image_bg", "id", pkg)
        (root.findViewById<View>(bgId) as? ImageView)?.let { bg ->
            bg.setImageBitmap(bm)
            bg.scaleType = ImageView.ScaleType.CENTER_CROP
            bg.visibility = View.VISIBLE
            bg.outlineProvider = roundOutline
            bg.clipToOutline = true
        }
    }

    // ---------------- 文本编辑弹窗（复刻系统"修改设备名称"弹窗） ----------------

    private fun showTextEditDialog(module: HookEntry, fragment: Any, key: String) {
        val ctx = fragmentContext(fragment) ?: return
        val group = TEXT_GROUPS[key] ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = ctx.packageName
        val res = ctx.resources

        runCatching {
            val cl = ctx.classLoader
            val builderCls = cl.loadClass("com.originui.widget.dialog.VDialogBuilder")

            // 与系统弹窗一致：VDialogBuilder(context, -2)
            val builder = builderCls.getConstructor(Context::class.java, Int::class.javaPrimitiveType)
                .newInstance(ctx, -2)

            // 复用系统布局 vivo_device_name_settings_dialog_layout
            val layoutId = res.getIdentifier("vivo_device_name_settings_dialog_layout", "layout", pkg)
            val content = LayoutInflater.from(ctx).inflate(layoutId, null)
            val editId = res.getIdentifier("device_name_edit_text", "id", pkg)
            val warnId = res.getIdentifier("warning_text", "id", pkg)
            val progressId = res.getIdentifier("progress_bar", "id", pkg)
            val edit = content.findViewById<View>(editId) as? EditText
            // 顶部系统说明（"使用蓝牙、WLAN 直连…"）隐藏
            val tvTitleId = res.getIdentifier("tv_title", "id", pkg)
            content.findViewById<View>(tvTitleId)?.visibility = View.GONE
            // 提示文案改为"清空恢复默认"，居中
            (content.findViewById<View>(warnId) as? TextView)?.let { w ->
                w.text = "清空恢复默认"
                w.gravity = android.view.Gravity.CENTER
                w.visibility = View.VISIBLE
            }
            content.findViewById<View>(progressId)?.visibility = View.GONE
            // 编辑框右侧清空按钮
            val clearId = res.getIdentifier("btn_clear", "id", pkg)
            content.findViewById<View>(clearId)?.setOnClickListener { edit?.setText("") }

            // 无覆盖值时补捕原文（异步数据晚到的兜底）
            if (originalTexts[key] == null && prefs.getString("text_$key", null) == null) {
                valueText(fragment, ctx, group)?.let { originalTexts[key] = it }
            }

            val current = currentValue(fragment, ctx, key)
            edit?.setText(current)
            edit?.setSelection(current.length)
            edit?.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(64))
            edit?.hint = group.title

            builderCls.getMethod("setView", View::class.java).invoke(builder, content)
            // 标题：优先 CharSequence 重载，退回 int 重载
            runCatching {
                builderCls.getMethod("setTitle", CharSequence::class.java).invoke(builder, group.title)
            }.onFailure {
                val titleId = res.getIdentifier("vivo_device_modify_dialog_title", "string", pkg)
                builderCls.getMethod("setTitle", Int::class.javaPrimitiveType).invoke(builder, titleId)
            }

            val okId = res.getIdentifier("ok", "string", pkg)
            val cancelId = res.getIdentifier("cancel", "string", pkg)
            builderCls.getMethod("setPositiveButton", Int::class.javaPrimitiveType,
                android.content.DialogInterface.OnClickListener::class.java)
                .invoke(builder, okId, android.content.DialogInterface.OnClickListener { _, _ ->
                    val input = edit?.text?.toString()?.trim().orEmpty()
                    if (input.isEmpty()) {
                        prefs.edit().remove("text_$key").apply()
                        originalTexts[key]?.let { orig -> setGroupText(fragment, ctx, key, orig) }
                    } else {
                        prefs.edit().putString("text_$key", input).apply()
                        setGroupText(fragment, ctx, key, input)
                    }
                })
            builderCls.getMethod("setNegativeButton", Int::class.javaPrimitiveType,
                android.content.DialogInterface.OnClickListener::class.java)
                .invoke(builder, cancelId, android.content.DialogInterface.OnClickListener { d, _ -> d.dismiss() })

            val dialog = builderCls.getMethod("create").invoke(builder) as Dialog
            dialog.show()
            module.logD(TAG, "text dialog shown: key=$key")
        }.onFailure {
            log(module, "text dialog failed: $it")
            Toast.makeText(ctx, "弹窗创建失败: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 只改写行 summary 值视图，标题等保持原样 */
    private fun setGroupText(fragment: Any, ctx: Context, key: String, value: String) {
        val root = fragmentRootView(fragment) ?: return
        summaryView(root, ctx.resources, ctx.packageName, TEXT_GROUPS[key]?.viewIdName ?: return)
            ?.text = value
    }

    // ---------------- 背景图：选图 + 裁切 ----------------

    fun pickBackgroundImage(module: HookEntry, fragment: Any) {
        val ctx = fragmentContext(fragment) ?: return
        runCatching {
            // 走图库的相册选择器（可按文件夹分类），与 vivo 头像选图一致
            val intent = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            (fragment.javaClass.getMethod("startActivityForResult", Intent::class.java, Int::class.javaPrimitiveType)
                .invoke(fragment, intent, REQ_PICK_BG))
        }.onFailure {
            log(module, "pick bg failed: $it")
            Toast.makeText(ctx, "无法打开图片选择器: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 由 SettingsHooker 中 hook 的 Fragment.onActivityResult 转发 */
    fun onPickResult(module: HookEntry, fragment: Any, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_BG) return
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        module.logD(TAG, "pick result: $uri")
        val ctx = fragmentContext(fragment) ?: return
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        showCropDialog(module, fragment, uri)
    }

    private fun showCropDialog(module: HookEntry, fragment: Any, uri: android.net.Uri) {
        val ctx = fragmentContext(fragment) ?: return
        val cl = ctx.classLoader
        val res = ctx.resources
        val pkg = ctx.packageName

        // 输出尺寸 = 背景视图实际尺寸，兜底 1080x620
        val bg = fragmentRootView(fragment)
            ?.findViewById<View>(res.getIdentifier("origin_logo_image_bg", "id", pkg))
        val outW = bg?.width?.takeIf { it > 0 } ?: 1080
        val outH = bg?.height?.takeIf { it > 0 } ?: 620

        val src = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (src == null) {
            Toast.makeText(ctx, "无法读取所选图片", Toast.LENGTH_SHORT).show()
            return
        }
        val cropView = CropView(ctx, src, outW, outH)

        runCatching {
            val builderCls = cl.loadClass("com.originui.widget.dialog.VDialogBuilder")
            val builder = builderCls.getConstructor(Context::class.java, Int::class.javaPrimitiveType)
                .newInstance(ctx, -2)
            builderCls.getMethod("setView", View::class.java).invoke(builder, cropView)
            runCatching {
                builderCls.getMethod("setTitle", CharSequence::class.java).invoke(builder, "调整背景图片")
            }
            val okId = res.getIdentifier("ok", "string", pkg)
            val cancelId = res.getIdentifier("cancel", "string", pkg)
            builderCls.getMethod("setPositiveButton", Int::class.javaPrimitiveType,
                android.content.DialogInterface.OnClickListener::class.java)
                .invoke(builder, okId, android.content.DialogInterface.OnClickListener { _, _ ->
                    val cropped = cropView.crop()
                    saveBackground(ctx, cropped)
                    applyCustomBackground(fragment)
                })
            builderCls.getMethod("setNegativeButton", Int::class.javaPrimitiveType,
                android.content.DialogInterface.OnClickListener::class.java)
                .invoke(builder, cancelId, android.content.DialogInterface.OnClickListener { d, _ -> d.dismiss() })
            (builderCls.getMethod("create").invoke(builder) as Dialog).show()
        }.onFailure {
            log(module, "crop dialog failed: $it")
            Toast.makeText(ctx, "裁切弹窗创建失败: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBackground(ctx: Context, bitmap: Bitmap) {
        runCatching {
            FileOutputStream(File(ctx.filesDir, BG_FILE)).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    // ---------------- 裁切视图：拖动 + 双指缩放，固定输出尺寸 ----------------

    private class CropView(
        context: Context,
        private val src: Bitmap,
        private val outW: Int,
        private val outH: Int,
    ) : FrameLayout(context) {

        private val image: ImageView
        private val matrix = Matrix()
        private val detector: ScaleGestureDetector
        private var lastX = 0f
        private var lastY = 0f
        private var scale = 1f
        private var baseScale = 1f

        init {
            image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.MATRIX
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            addView(image)
            detector = ScaleGestureDetector(context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(d: ScaleGestureDetector): Boolean {
                        scale = (scale * d.scaleFactor).coerceIn(1f, 8f)
                        updateMatrix()
                        return true
                    }
                })
            image.post {
                // 初始：等比缩放到覆盖裁切框（center-crop）
                val vw = image.width.toFloat()
                val vh = image.height.toFloat()
                baseScale = maxOf(vw / src.width, vh / src.height)
                scale = 1f
                updateMatrix()
            }
        }

        /** 预览区强制与输出同比例（outW:outH），保证"所见即所得"且裁切不拉伸 */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = (w.toFloat() * outH / outW).toInt()
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        }

        private fun updateMatrix() {
            val vw = image.width.toFloat()
            val vh = image.height.toFloat()
            val s = baseScale * scale
            matrix.reset()
            matrix.setScale(s, s)
            // 平移限制：图片始终覆盖裁切框
            val dw = src.width * s
            val dh = src.height * s
            val values = FloatArray(9).also { matrix.getValues(it) }
            val tx = values[Matrix.MTRANS_X].coerceIn(vw - dw, 0f)
            val ty = values[Matrix.MTRANS_Y].coerceIn(vh - dh, 0f)
            matrix.postTranslate(tx, ty)
            image.imageMatrix = matrix
            image.setImageBitmap(src)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) {
                    matrix.postTranslate(event.x - lastX, event.y - lastY)
                    lastX = event.x; lastY = event.y
                    clampAndApply()
                }
            }
            return true
        }

        private fun clampAndApply() {
            val vw = image.width.toFloat()
            val vh = image.height.toFloat()
            val s = baseScale * scale
            val dw = src.width * s
            val dh = src.height * s
            val values = FloatArray(9).also { matrix.getValues(it) }
            values[Matrix.MTRANS_X] = values[Matrix.MTRANS_X].coerceIn(vw - dw, 0f)
            values[Matrix.MTRANS_Y] = values[Matrix.MTRANS_Y].coerceIn(vh - dh, 0f)
            matrix.setValues(values)
            image.imageMatrix = matrix
        }

        /** 按当前 matrix 把可视区域裁出，输出固定 outW x outH */
        fun crop(): Bitmap {
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val vw = image.width.toFloat()
            val drawMatrix = Matrix(matrix)
            // 可视区坐标 → 输出坐标
            drawMatrix.postScale(outW / vw, outH / image.height.toFloat())
            canvas.drawBitmap(src, drawMatrix, null)
            return out
        }
    }

    // ---------------- 反射工具 ----------------

    private fun fragmentRootView(fragment: Any): View? =
        runCatching { fragment.javaClass.getMethod("getView").invoke(fragment) as? View }.getOrNull()

    private fun fragmentContext(fragment: Any): Context? =
        runCatching { fragment.javaClass.getMethod("getContext").invoke(fragment) as? Context }.getOrNull()
            ?: runCatching { fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Context }.getOrNull()

    private fun log(module: HookEntry, msg: String) {
        module.log(6, TAG, msg)
    }
}
