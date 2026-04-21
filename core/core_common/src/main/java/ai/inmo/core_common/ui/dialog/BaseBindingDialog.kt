package ai.inmo.core_common.ui.dialog

import ai.inmo.core_common.R
import android.content.Context
import android.graphics.Point
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.viewbinding.ViewBinding


/**
 * 
 * Date: 2024/09/20 18:06
 * 
 */
abstract class BaseBindingDialog<VB : ViewBinding>(
    context: Context,
    themeResId: Int = R.style.BaseDialog,
    inflate: (LayoutInflater) -> VB,
    private val canceledOnTouchOutside: Boolean = false,
    private val isInterceptTransparentAreaEvents: Boolean = false,
    private val widthPercentage: Float = 0.7f,
    private val heightPercentage: Float = -1f,
    private val gravity: Int = Gravity.CENTER,
    private val highlyConsistent: Boolean = false,
    private val yOffset: Int = 0
) : ComponentDialog(context, themeResId) {

    var binding: VB = inflate(layoutInflater)

    init {
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        adjustLayoutSize()
        setupLifecycleOwner()
        setupBackPressedDispatcher()
    }

    private fun init() {
        // Make us non-modal, so that others can receive touch events.
        window!!.setFlags(FLAG_NOT_TOUCH_MODAL, FLAG_NOT_TOUCH_MODAL);

        // ...but notify us that it happened.
        window!!.setFlags(FLAG_WATCH_OUTSIDE_TOUCH, FLAG_WATCH_OUTSIDE_TOUCH);

        setContentView(binding.root)
    }

    private fun adjustLayoutSize() {
        val vm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = vm.defaultDisplay
        val point = Point()
        display.getSize(point)
        val layoutParams = window!!.attributes
        layoutParams.width = (point.x * widthPercentage).toInt()    //宽度设置为屏幕宽度的0.8

        // 高度和原始布局保持一致
        if (highlyConsistent) {
            layoutParams.height = point.y
        } else {
            if (heightPercentage != -1f) {
                layoutParams.height = (point.y * heightPercentage).toInt()
            }
        }

        layoutParams.gravity = gravity

        if (isInterceptTransparentAreaEvents) {
            // 背景透明部分不拦截点击事件
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        // y 轴的偏移量
        if (yOffset != 0) {
            layoutParams.y = yOffset
        }

        window!!.attributes = layoutParams


        setCanceledOnTouchOutside(canceledOnTouchOutside)

//        window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }

    private fun setupLifecycleOwner() {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                super.onDestroy(owner)
                dismiss()
            }
        })
    }

    private fun setupBackPressedDispatcher() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                dismiss()
            }
        })
    }
}