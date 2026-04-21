package ai.inmo.openclaw.data.local.prefs

import android.content.Context
import com.tencent.mmkv.MMKV

object MmkvStore {
    private const val PREFS_STORE_ID = "openclaw_prefs"
    private const val NODE_IDENTITY_STORE_ID = "openclaw_node_identity"

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) {
            return
        }

        synchronized(this) {
            if (initialized) {
                return
            }

            MMKV.initialize(context.applicationContext)
            initialized = true
        }
    }

    fun preferences(context: Context): MMKV {
        initialize(context)
        return requireNotNull(MMKV.mmkvWithID(PREFS_STORE_ID, MMKV.SINGLE_PROCESS_MODE)) {
            "Failed to open MMKV store: $PREFS_STORE_ID"
        }
    }

    fun nodeIdentity(context: Context): MMKV {
        initialize(context)
        return requireNotNull(MMKV.mmkvWithID(NODE_IDENTITY_STORE_ID, MMKV.SINGLE_PROCESS_MODE)) {
            "Failed to open MMKV store: $NODE_IDENTITY_STORE_ID"
        }
    }
}
