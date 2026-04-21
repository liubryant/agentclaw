package ai.inmo.core_common.utils.concurrency

/**
 * 
 * Date: 2024/09/20 18:22
 * 
 */


/**
 * Provides an Thread to be used for Async work.
 * @param T Type of Thread. `Scheduler` for RxJava. `CoroutineDispatcher` for Coroutines
 */
interface ThreadDispatcher<T> {
    /**
     * Creates and returns a Thread intended for IO-bound work. The implementation is backed by an
     * Executor thread-pool that will grow as needed. This can be used for asynchronously performing
     * blocking IO. Do not perform computational work on this scheduler.
     * Use `ThreadDispatcher.computation` instead.
     *
     * @see [ThreadDispatcher.computation]
     * @return T
     */
    val io: T

    /**
     * Creates and returns a Thread intended for computational work. This can be used for event-loops,
     * processing callbacks and other computational work. Do not perform IO-bound work on this thread.
     * Use `ThreadDispatcher.io` instead.
     *
     * @see [ThreadDispatcher.io]
     * @return T
     */
    val computation: T

    /**
     * This is used to bring back the execution to the main thread so that UI modification can be made.
     * @return T
     */
    val ui: T
}