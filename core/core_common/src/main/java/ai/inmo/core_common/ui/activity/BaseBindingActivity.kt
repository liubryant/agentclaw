package ai.inmo.core_common.ui.activity

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding
import ai.inmo.core_common.ui.viewModel.IBaseViewModel
import ai.inmo.core_common.utils.Logger
import android.util.Log
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * 
 * Date: 2024/09/20 17:55
 * 
 */
abstract class BaseBindingActivity<VB : ViewBinding>(
    private val inflate: (LayoutInflater) -> VB
) : AppCompatActivity(), InitListener {

    lateinit var binding: VB

    protected var mMainScope = MainScope()

    open var handleInsets: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = inflate(layoutInflater)
        setContentView(binding.root)

        // 设置窗口标志位，保持常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

//        enableEdgeToEdge(binding.root,handleInsets)

        mMainScope = MainScope()


        val configContext = createConfigurationContext(resources.configuration)

        configContext.resources.apply {
            val fontScale = configuration.fontScale
            val scaledDensity = displayMetrics.scaledDensity
            Logger.d("fontScale:$fontScale,scaledDensity:$scaledDensity")
        }

        initData()
        initView()
        initEvent()
    }

    override fun onStart() {
        super.onStart()
//        mMainScope = MainScope()
    }

    override fun onStop() {
        super.onStop()
//        clearJobs()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearJobs()
    }

//    override fun getResources(): Resources {
//        val resources = super.getResources()
//        val configContext = createConfigurationContext(resources.configuration)
//        return configContext.resources.apply {
//            configuration.fontScale = 1.0f
//            displayMetrics.scaledDensity = displayMetrics.density * configuration.fontScale
//        }
//    }

    /**
     * Cancels all active background jobs.
     */
    private fun clearJobs() {
        mMainScope.cancel()
    }

    /**
     * Increase Lifecycle monitoring
     *
     */
    protected fun IBaseViewModel.addObserver() {
        lifecycle.addObserver(this)
    }

    /**
     * Monitor software disk
     * @param block Function0<Int>
     * @param block Function1<Int>
     */
    protected fun softKeyBoardListener(
        keyBoardShow: (height: Int) -> Unit,
        keyBoardHide: (height: Int) -> Unit
    ) {
        var rootViewVisibleHeight: Int = 0
        //Gets the root view of the activity
        val rootView = window?.decorView
        //Listen for a change in the global layout of the view tree or a change in the visual state of a view in the view tree
        rootView?.viewTreeObserver?.addOnGlobalLayoutListener {
            //Gets the size of the current root view displayed on the screen
            val r = Rect()
            rootView.getWindowVisibleDisplayFrame(r)
            val visibleHeight = r.height()
            Log.d("BaseBindingActivity", "The height of the current screen $visibleHeight")
            if (rootViewVisibleHeight == 0) {
                rootViewVisibleHeight = visibleHeight
                return@addOnGlobalLayoutListener
            }
            //The root view display height has not changed and can be viewed as the soft keyboard display/hide state has not changed
            if (rootViewVisibleHeight == visibleHeight) return@addOnGlobalLayoutListener
            //The root view displays a smaller height of more than 200, which can be viewed as a soft keyboard display
            if (rootViewVisibleHeight - visibleHeight > 200) {
                Log.d(
                    "BaseBindingActivity",
                    "Pop-up soft keyboard,height is ${rootViewVisibleHeight - visibleHeight}"
                )
                keyBoardShow(rootViewVisibleHeight)
                rootViewVisibleHeight = visibleHeight
                return@addOnGlobalLayoutListener
            }
            //The root view shows that the height has increased by more than 200, which can be seen as a hidden soft keyboard
            if (visibleHeight - rootViewVisibleHeight > 200) {
                Log.d("BaseBindingActivity", "Put away the soft keyboard")
                keyBoardHide(visibleHeight - rootViewVisibleHeight)
                rootViewVisibleHeight = visibleHeight
                return@addOnGlobalLayoutListener
            }
        }
    }
}