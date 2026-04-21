package ai.inmo.core_common.utils.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * @author 0xm1nam0
 * @date 2025/10/13 11:50
 */
object ThreadScopeExtensions {
    private val ioCoroutineScope: CoroutineScope by lazy {
        CoroutineScope(Dispatchers.IO)
    }
    private val mainCoroutineScope: CoroutineScope by lazy {
        CoroutineScope(Dispatchers.Main)
    }
    private val singleThreadCoroutineScope: CoroutineScope by lazy {
        CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    }

    fun runOnUI(block: suspend CoroutineScope.() -> Unit): Job = mainCoroutineScope.launch { block() }

    fun runOnIO(block: suspend CoroutineScope.() -> Unit): Job = ioCoroutineScope.launch { block() }

    fun runOnSingleThread(block: suspend CoroutineScope.() -> Unit): Job = singleThreadCoroutineScope.launch { block() }
}