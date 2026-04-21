package ai.inmo.openclaw.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

object CurrentActivityTracker : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null
    @Volatile
    private var registered = false

    fun ensureRegistered(application: Application) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            application.registerActivityLifecycleCallbacks(this)
            registered = true
        }
    }

    fun currentActivity(): Activity? = currentActivityRef?.get()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivityRef?.get() === activity) {
            currentActivityRef = null
        }
    }
}
