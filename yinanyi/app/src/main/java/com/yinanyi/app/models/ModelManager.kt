package com.yinanyi.app.models

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** 每个模型包当前的状态，首页和「离线模型」页读这里。 */
object ModelManager {
    sealed interface State {
        data object Missing : State
        data object Installed : State
        data class Downloading(val done: Long, val total: Long, val extracting: Boolean = false) : State
        data class Failed(val message: String) : State
    }

    private val state = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = state

    @Synchronized
    fun set(id: String, newState: State) {
        state.value = state.value + (id to newState)
    }

    /** 按磁盘上的文件刷新「已安装 / 未安装」。正在下载的包不动；失败的包没装上就保留失败信息。 */
    @Synchronized
    fun refresh(root: File) {
        val old = state.value
        state.value = ModelCatalog.all.associate { pack ->
            val current = old[pack.id]
            pack.id to when {
                current is State.Downloading -> current
                pack.isInstalled(root) -> State.Installed
                current is State.Failed -> current
                else -> State.Missing
            }
        }
    }

    fun isBusy(): Boolean = state.value.values.any { it is State.Downloading }
}
