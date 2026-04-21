package ai.inmo.core_common.utils.coroutine

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 
 * Date: 2025/07/12 14:37
 * 
 */


/**
 * 一个基于协程的、生命周期安全的倒计时工具类
 *
 * @param totalMillis 倒计时总时长，单位：毫秒
 * @param intervalMillis 倒计时间隔，单位：毫秒
 * @param scope 协程作用域，建议传入 viewModelScope 或 lifecycleScope，以实现生命周期安全
 */
class CoroutineCountdownTimer(
    private val totalMillis: Long,
    private val intervalMillis: Long,
    private val scope: CoroutineScope
) {

    // 私有的、可变的 StateFlow，用于内部更新剩余时间
    private val _remainingTime = MutableStateFlow(totalMillis)
    /**
     * 对外暴露的、只读的 StateFlow，UI层可以订阅此Flow来获取剩余时间的变化
     */
    val remainingTime = _remainingTime.asStateFlow()

    // 私有的、可变的 StateFlow，用于内部更新计时器是否结束的状态
    private val _isFinished = MutableStateFlow<Boolean?>(null)
    /**
     * 对外暴露的、只读的 StateFlow，用于通知UI层计时器已经结束
     */
    val isFinished = _isFinished.asStateFlow()


    private var job: Job? = null
    private var remainingMillis: Long = totalMillis

    /**
     * 启动倒计时。
     * 如果之前是暂停状态，则会从头开始。
     */
    fun start() {
        // 如果已有任务在运行，先取消
        job?.cancel()
        // 重置状态
        resetState()

        job = scope.launch(Dispatchers.Main) {
            _isFinished.value = false
            remainingMillis = totalMillis

            while (remainingMillis > 0 && isActive) {
                _remainingTime.value = remainingMillis
                delay(intervalMillis)
                remainingMillis -= intervalMillis
            }

            // 当循环结束时，如果不是因为协程被取消，则说明是自然结束
            if (isActive) {
                _remainingTime.value = 0
                _isFinished.value = true
            }
        }
    }

    /**
     * 暂停倒计时。
     * 状态会被保留，可以通过 resume() 恢复。
     */
    fun pause() {
        job?.cancel()
    }

    /**
     * 从上次暂停的位置恢复倒计时。
     * 如果倒计时从未开始或已被取消，则行为同 start()。
     */
    fun resume() {
        if (job?.isActive == true) return // 如果正在运行，则不执行任何操作

        job = scope.launch(Dispatchers.Main) {
            // 如果是已完成状态，则重新开始
            if (_isFinished.value == true) {
                resetState()
                remainingMillis = totalMillis
            }

            _isFinished.value = false

            while (remainingMillis > 0 && isActive) {
                _remainingTime.value = remainingMillis
                delay(intervalMillis)
                remainingMillis -= intervalMillis
            }

            if (isActive) {
                _remainingTime.value = 0
                _isFinished.value = true
            }
        }
    }

    /**
     * 取消倒计时。
     * 状态将被完全重置。
     */
    fun cancel() {
        job?.cancel()
        resetState()
    }

    private fun resetState() {
        remainingMillis = totalMillis
        _remainingTime.value = totalMillis
        _isFinished.value = false
        job = null
    }
}