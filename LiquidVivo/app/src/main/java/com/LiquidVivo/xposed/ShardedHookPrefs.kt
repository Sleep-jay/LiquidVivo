package com.LiquidVivo.xposed

import android.content.SharedPreferences

/**
 * hook 开关配置的分片路由（方案移植自 HyperIsland 的 32-shard 设计）：
 *
 * LSPosed RemotePreferences 打开一个分组时会把整组数据经 Binder 一次性传输，
 * 配置增多后单次事务可能超过缓冲区上限。因此按键哈希拆到 32 个分片分组，
 * 每个分组保持小体量。旧分组 [HookEntry.HOOK_PREFS_NAME] 仅保留为读回退
 * （兼容升级前的存量配置），新写入一律进分片。
 *
 * app 侧与 hook 侧必须使用同一套路由规则（本对象），保证读写落同一分片。
 */
object HookPrefsShards {

    const val SHARD_COUNT = 32

    fun shardName(index: Int): String = "liquidvivo_hooks_shard_$index"

    fun nameFor(key: String): String =
        shardName((key.hashCode() and Int.MAX_VALUE) % SHARD_COUNT)
}

/**
 * 跨分片的 SharedPreferences 门面：
 * - 读：先查键所在分片，未命中回落旧分组（迁移兼容）
 * - 写：Editor 按键路由到对应分片批量提交；remove 同时清理旧分组
 * - 监听：注册到全部分片 + 旧分组，回调统一以本门面对象回传
 *   （监听器内读取其它键也能正确跨分片路由）
 *
 * @param resolve 按分组名打开底层 SharedPreferences；返回 null 视为该分组不可用
 */
class ShardedSharedPreferences internal constructor(
    private val resolve: (String) -> SharedPreferences?,
) : SharedPreferences {

    private val cache = HashMap<String, SharedPreferences?>()
    private val listenerWrappers =
        HashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()

    private fun group(name: String): SharedPreferences? = synchronized(cache) {
        if (cache.containsKey(name)) {
            cache[name]
        } else {
            runCatching { resolve(name) }.getOrNull().also { cache[name] = it }
        }
    }

    private fun shard(key: String): SharedPreferences? = group(HookPrefsShards.nameFor(key))

    private fun legacy(): SharedPreferences? = group(HookEntry.HOOK_PREFS_NAME)

    private fun allGroups(): List<SharedPreferences> = buildList {
        legacy()?.let { add(it) }
        for (i in 0 until HookPrefsShards.SHARD_COUNT) {
            group(HookPrefsShards.shardName(i))?.let { add(it) }
        }
    }

    private fun <T> read(key: String, def: T, getter: (SharedPreferences) -> T?): T {
        val s = shard(key)
        if (s != null && runCatching { s.contains(key) }.getOrDefault(false)) {
            return runCatching { getter(s) }.getOrNull() ?: def
        }
        val l = legacy() ?: return def
        if (!runCatching { l.contains(key) }.getOrDefault(false)) return def
        return runCatching { getter(l) }.getOrNull() ?: def
    }

    override fun getAll(): Map<String, *> {
        val merged = HashMap<String, Any?>()
        runCatching { legacy()?.all?.let { merged.putAll(it) } }
        for (i in 0 until HookPrefsShards.SHARD_COUNT) {
            runCatching { group(HookPrefsShards.shardName(i))?.all?.let { merged.putAll(it) } }
        }
        return merged
    }

    override fun getString(key: String, defValue: String?): String? =
        read(key, defValue) { it.getString(key, null) }

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        read(key, defValues) { it.getStringSet(key, null) }

    override fun getInt(key: String, defValue: Int): Int =
        read(key, defValue) { it.getInt(key, defValue) }

    override fun getLong(key: String, defValue: Long): Long =
        read(key, defValue) { it.getLong(key, defValue) }

    override fun getFloat(key: String, defValue: Float): Float =
        read(key, defValue) { it.getFloat(key, defValue) }

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        read(key, defValue) { it.getBoolean(key, defValue) }

    override fun contains(key: String): Boolean {
        val inShard = shard(key)?.let { runCatching { it.contains(key) }.getOrDefault(false) } == true
        if (inShard) return true
        return legacy()?.let { runCatching { it.contains(key) }.getOrDefault(false) } == true
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        val wrapped = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            listener.onSharedPreferenceChanged(this, key)
        }
        synchronized(listenerWrappers) { listenerWrappers[listener] = wrapped }
        allGroups().forEach {
            runCatching { it.registerOnSharedPreferenceChangeListener(wrapped) }
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        val wrapped = synchronized(listenerWrappers) { listenerWrappers.remove(listener) } ?: return
        allGroups().forEach {
            runCatching { it.unregisterOnSharedPreferenceChangeListener(wrapped) }
        }
    }

    override fun edit(): SharedPreferences.Editor = ShardEditor()

    private inner class ShardEditor : SharedPreferences.Editor {

        private val puts = LinkedHashMap<String, Any?>()
        private val removes = LinkedHashSet<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { puts[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?) = apply {
            puts[key] = values?.let { LinkedHashSet(it) }
        }

        override fun putInt(key: String, value: Int) = apply { puts[key] = value }

        override fun putLong(key: String, value: Long) = apply { puts[key] = value }

        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }

        override fun remove(key: String) = apply { removes += key }

        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            if (clearAll) {
                val groups = allGroups()
                if (groups.isEmpty()) return false
                var ok = true
                for (g in groups) {
                    ok = runCatching { g.edit().clear().commit() }.getOrDefault(false) && ok
                }
                return ok
            }

            var ok = true
            var touched = false

            // 按分片分组批量写入
            val byGroup = LinkedHashMap<String, MutableList<Pair<String, Any?>>>()
            for ((k, v) in puts) {
                byGroup.getOrPut(HookPrefsShards.nameFor(k)) { mutableListOf() }.add(k to v)
            }
            for ((name, entries) in byGroup) {
                val g = group(name) ?: continue
                touched = true
                val e = g.edit()
                for ((k, v) in entries) writeEntry(e, k, v)
                ok = runCatching { e.commit() }.getOrDefault(false) && ok
            }

            // 删除：分片 + 旧分组都清（旧分组可能还留有迁移前的键）
            if (removes.isNotEmpty()) {
                for (name in removes.mapTo(HashSet()) { HookPrefsShards.nameFor(it) }) {
                    val g = group(name) ?: continue
                    touched = true
                    val e = g.edit()
                    for (k in removes) if (HookPrefsShards.nameFor(k) == name) e.remove(k)
                    ok = runCatching { e.commit() }.getOrDefault(false) && ok
                }
                legacy()?.let { l ->
                    touched = true
                    val e = l.edit()
                    removes.forEach { e.remove(it) }
                    ok = runCatching { e.commit() }.getOrDefault(false) && ok
                }
            }

            return if (touched) ok else false
        }

        override fun apply() {
            commit()
        }

        private fun writeEntry(e: SharedPreferences.Editor, k: String, v: Any?) {
            when (v) {
                null -> e.remove(k)
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    e.putStringSet(k, v as Set<String>)
                }
            }
        }
    }
}
