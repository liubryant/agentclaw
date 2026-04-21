package ai.inmo.core_common.utils.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 
 * Date: 2024/11/18 10:19
 * 
 */
object CoroutineUtils {


    // 切换到 IO 线程执行代码块
    fun io(block: suspend CoroutineScope.() -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            block()
        }
    }

    // 切换到 Main 线程（UI 线程）执行代码块
    fun ui(block: suspend CoroutineScope.() -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            block()
        }
    }

    // 在 IO 线程执行代码块，完成后切换到 Main 线程执行回调
    fun <T> ioToUi(ioBlock: suspend CoroutineScope.() -> T, uiBlock: suspend CoroutineScope.(T) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                ioBlock()
            }
            uiBlock(result)
        }
    }

    // 在 Main 线程执行代码块，完成后切换到 IO 线程执行回调
    fun <T> uiToIo(uiBlock: suspend CoroutineScope.() -> T, ioBlock: suspend CoroutineScope.(T) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = uiBlock()
            withContext(Dispatchers.IO) {
                ioBlock(result)
            }
        }
    }

    suspend fun <T> runMain(block: suspend CoroutineScope.() -> T): T {
        return withContext(Dispatchers.Main, block = block)
    }

    suspend fun <T> runIO(block: suspend CoroutineScope.() -> T): T {
        return withContext(Dispatchers.IO, block = block)
    }
}