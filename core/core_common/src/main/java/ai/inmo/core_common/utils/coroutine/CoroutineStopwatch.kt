package ai.inmo.core_common.utils.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 
 * Date: 2025/12/26 10:19
 * 
 */

/**
 * 基于协程的正向计时器
 */
class CoroutineStopwatch {
    private var job: Job? = null

    // 记录计时开始的时间点
    private var startTime: Long = 0L

    // 记录暂停前已经累计的时间
    private var accumulatedTime: Long = 0L

    // 运行状态标记
    private val _isRunning = AtomicBoolean(false)
    val isRunning: Boolean get() = _isRunning.get()

    /**
     * 开始/继续 计时
     * @param scope 协程作用域 (ViewModelScope / LifecycleScope)
     * @param tickIntervalMillis 回调频率，默认 1000ms
     * @param onTick 回调当前累计的时间(毫秒)
     */
    fun start(
        scope: CoroutineScope,
        tickIntervalMillis: Long = 1000L,
        onTick: (Long) -> Unit
    ) {
        if (_isRunning.get()) return // 防止重复启动

        _isRunning.set(true)

        // 核心逻辑：当前时间 - (过去已流逝的时间) = 本次“新的”起始锚点
        // 这样无论暂停多少次，计算公式始终是：Now - StartTime
        startTime = System.currentTimeMillis() - accumulatedTime

        job = scope.launch(Dispatchers.Main) {
            while (isActive && _isRunning.get()) {
                // 计算当前总时长
                val now = System.currentTimeMillis()
                accumulatedTime = now - startTime

                // 回调给 UI
                onTick(accumulatedTime)

                // 挂起一段时间。注意：这里只是控制刷新频率，不影响计时准确性
                // 即使 delay 不准，下一次循环也是根据 System 时间重新计算的
                delay(tickIntervalMillis)
            }
        }
    }

    /**
     * 暂停计时
     * 暂停后，accumulatedTime 会保留当前时长，job 被取消
     */
    fun pause() {
        if (_isRunning.get()) {
            _isRunning.set(false)
            job?.cancel()
            // 此时 accumulatedTime 已经在循环中更新到了最新值
        }
    }

    /**
     * 重置计时器
     * 归零并停止
     */
    fun stop() {
        _isRunning.set(false)
        job?.cancel()
        accumulatedTime = 0L
        startTime = 0L
    }

    /**
     * 获取当前瞬间的记录时间（不依赖回调，用于获取最终结果）
     */
    fun getCurrentTime(): Long {
        return if (_isRunning.get()) {
            System.currentTimeMillis() - startTime
        } else {
            accumulatedTime
        }
    }
}