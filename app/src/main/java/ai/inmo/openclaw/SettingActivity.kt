package ai.inmo.openclaw

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity

class SettingActivity : ComponentActivity() {
    private var panelController: SettingPanelController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting)
        panelController =
            findViewById<View>(android.R.id.content)?.let {
                SettingPanelController(this, it).also {
                    it.bind()
                }
            }
    }

    override fun onDestroy() {
        panelController?.release()
        panelController = null
        super.onDestroy()
    }
}
