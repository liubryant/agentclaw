package ai.inmo.core_common.ui.viewModel

import ai.inmo.core_common.utils.concurrency.CoroutineDispatchers
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * 
 * Date: 2024/09/20 18:12
 * 
 */
open class BaseViewModel :
    ViewModel(),
    CoroutineScope, IBaseViewModel {

    private val mJob = SupervisorJob()

    val coroutineDispatchers = CoroutineDispatchers()

    override val coroutineContext: CoroutineContext
        get() = coroutineDispatchers.ui + mJob

    override fun onAny(owner: LifecycleOwner?, event: Lifecycle.Event?) {

    }

    override fun onCreate() {

    }

    override fun onStart() {

    }

    override fun onResume() {

    }

    override fun onPause() {

    }

    override fun onStop() {

    }

    override fun onDestroy() {
    }

    /**
     * 切换 Ui 线程
     *
     * @param T
     * @param block
     */
    fun <T> launchUi(block: suspend () -> T) {
        viewModelScope.launch(coroutineDispatchers.ui) {
            runCatching {
                block()
            }.onFailure {
//                if (BuildConfig.DEBUG)
                    it.printStackTrace()
            }
        }
    }

    /**
     * 切换 Io 线程
     *
     * @param T
     * @param block
     */
    fun <T> launchIo(block: suspend () -> T) {
        viewModelScope.launch(coroutineDispatchers.io) {
            runCatching {
                block()
            }.onFailure {
//                if (BuildConfig.DEBUG)
                    it.printStackTrace()
            }
        }
    }
}