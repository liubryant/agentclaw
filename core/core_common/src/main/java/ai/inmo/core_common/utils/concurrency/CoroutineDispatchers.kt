package ai.inmo.core_common.utils.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 
 * Date: 2024/09/20 18:23
 * 
 */
class CoroutineDispatchers : ThreadDispatcher<CoroutineDispatcher> {
    override val io: CoroutineDispatcher
        get() = Dispatchers.IO
    override val computation: CoroutineDispatcher
        get() = Dispatchers.Default
    override val ui: CoroutineDispatcher
        get() = Dispatchers.Main
}