package ai.inmo.core_common.ui.activity

import ai.inmo.core_common.utils.Logger
import android.view.KeyEvent
import android.view.LayoutInflater
import androidx.viewbinding.ViewBinding

/**
 * 
 * Date: 2025/12/08 14:36
 * 
 */

/**
 * 专门用于监听物理按键的基类
 * 继承自 BaseBindingActivity
 */
abstract class KeyMonitorActivity<VB : ViewBinding>(
    inflate: (LayoutInflater) -> VB
) : BaseBindingActivity<VB>(inflate) {

    /**
     * 分发按键事件
     * 这里的优先级高于 Activity.onKeyDown 和 View.onKeyListener
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 过滤掉未知按键，防止干扰
        if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return super.dispatchKeyEvent(event)
        }

        // 只处理 ACTION_DOWN (按下) 事件，避免按下和抬起触发两次逻辑
        // 如果你需要处理长按或者抬起，可以在 onSmartKey 内部通过 event.action 判断
        if (event.action == KeyEvent.ACTION_DOWN) {

            // 打印日志，方便你在 Logcat 中快速查看键值 (TAG: KeyMonitor)
            Logger.d("KeyMonitor", "Captured Key -> Code: ${event.keyCode}, Name: ${KeyEvent.keyCodeToString(event.keyCode)}")

            // 调用抽象方法，将处理权交给子类
            // 如果子类返回 true，说明事件被消费，不再传给系统
            if (onSmartKey(event.keyCode, event)) {
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    /**
     * 【抽象方法】子类必须实现此方法来处理按键
     *
     * @param keyCode 按键值 (e.g., KeyEvent.KEYCODE_VOLUME_UP)
     * @param event   完整的事件对象 (包含 repeatCount, action 等信息)
     * @return Boolean
     *         true  -> 拦截事件（不让系统处理，比如拦截了音量键，音量条就不会出来）
     *         false -> 不拦截（系统继续处理，比如默认的返回键行为）
     */
    abstract fun onSmartKey(keyCode: Int, event: KeyEvent): Boolean
}