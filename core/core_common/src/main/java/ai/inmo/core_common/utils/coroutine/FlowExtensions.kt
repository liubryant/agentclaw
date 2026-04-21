package ai.inmo.core_common.utils.coroutine

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch


const val ANTI_SHAKE_THRESHOLD = 500L

fun View.clickFlow() = callbackFlow {
    setOnClickListener { this.trySend(Unit).isSuccess }
    awaitClose { setOnClickListener(null) }
}


/**
 *
 *
 * @param T
 * @param thresholdMillis   毫秒
 * @return
 */
fun <T> Flow<T>.throttleFirst(thresholdMillis: Long): Flow<T> = flow {
    var lastTime = 0L // 上次发射数据的时间
    //收集数据
    collect { upstream ->
        // 当前时间
        val currentTime = System.currentTimeMillis()
        // 时间差超过阈值则发送数据并记录时间
        if (currentTime - lastTime > thresholdMillis) {
            lastTime = currentTime
            emit(upstream)
        }
    }
}

/**
 * 简化 StateFlow 监听的扩展函数
 * 使用高阶函数来减少重复的模板代码
 * 
 * @param flow 要监听的 Flow
 * @param action 当值变化时执行的操作
 */
fun <T> LifecycleOwner.collectFlow(
    flow: Flow<T>,
    action: suspend (T) -> Unit
) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.CREATED) {
            flow.collect(action)
        }
    }
}

/**
 * 批量监听多个 Flow 的扩展函数
 * 
 * @param flows 要监听的 Flow 列表，每个 Flow 对应一个处理函数
 */
fun LifecycleOwner.collectFlows(
    vararg flows: Pair<Flow<*>, suspend (Any?) -> Unit>
) {
    flows.forEach { (flow, action) ->
        collectFlow(flow) { value ->
            action(value)
        }
    }
}

/**
 * 专门用于监听 Boolean 类型 StateFlow 的扩展函数
 * 常用于设置开关的监听
 */
fun LifecycleOwner.collectBooleanFlow(
    flow: Flow<Boolean>,
    action: (Boolean) -> Unit
) {
    collectFlow(flow) { value ->
        action(value)
    }
}
