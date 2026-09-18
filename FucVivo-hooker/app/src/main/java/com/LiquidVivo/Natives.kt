package com.LiquidVivo

/**
 * 最小 Natives 存根：模块模板无原生层，统一返回安全默认值。
 * 仅保留状态查询（供 UI 状态卡 / 底栏徽标使用）。
 */
object Natives {
    const val MINIMAL_SUPPORTED_KERNEL = 32513

    // version = -1 表示内核未工作（非管理器环境）
    val version: Int get() = -1
    val isLkmMode: Boolean get() = false
    val isLateLoadMode: Boolean get() = false
    val isManager: Boolean get() = false

    val kernelUAPIVersion: Int get() = -1
    val managerUAPIVersion: Int get() = -1

    fun checkUAPIMismatch(): Boolean = kernelUAPIVersion != managerUAPIVersion

    fun requireNewKernel(): Boolean =
        (version != -1 && version < MINIMAL_SUPPORTED_KERNEL) || checkUAPIMismatch()
}
