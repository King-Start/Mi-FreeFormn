package com.sunshine.freeform.ui.freeform

import android.app.ActivityOptions
import android.app.ActivityOptionsHidden
import android.app.PendingIntent
import android.app.PendingIntentHidden
import android.app.Service
import android.content.ComponentName
import android.content.ContextHidden
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.Parcelable
import android.os.SystemClock
import com.sunshine.freeform.app.MiFreeform
import com.sunshine.freeform.utils.ServiceUtils
import com.sunshine.freeform.utils.ServiceUtils.activityManager
import dev.rikka.tools.refine.Refine

class FreeformService : Service(), ScreenListener.ScreenStateListener {

    private val mFreeformViews = ArrayList<FreeformView>()
    private lateinit var mScreenListener: ScreenListener

    private val sp by lazy {
        getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, MODE_PRIVATE)
    }

    private fun createVirtualDisplay() = ServiceUtils.displayManager.createVirtualDisplay(
        "MiFreeform@${SystemClock.uptimeMillis()}",
        500, 500, 100, null,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    )

    override fun onCreate() {
        ServiceUtils.initWithShizuku(this)
        mScreenListener = ScreenListener(this)
        mScreenListener.addScreenStateListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START_INTENT -> {
                val config = FreeformConfig()
                val userId = intent.getIntExtra(Intent.EXTRA_USER, 0)
                config.userId = if (userId < 0) Refine.unsafeCast<ContextHidden>(this).userId else userId
                config.intent = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                config.componentName = intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME)

                val incomingPackage = (config.intent as? Intent)?.component?.packageName
                    ?: (config.intent as? Intent)?.`package`
                    ?: config.componentName?.packageName

                // Cek window aktif dengan package sama
                if (incomingPackage != null && incomingPackage != packageName) {
                    val existingView = mFreeformViews.firstOrNull {
                        !it.isDestroy && (
                            (it.config.intent as? Intent)?.component?.packageName == incomingPackage ||
                            (it.config.intent as? Intent)?.`package` == incomingPackage ||
                            it.config.componentName?.packageName == incomingPackage
                        )
                    }
                    if (existingView != null) {
                        if (existingView.isFloating || existingView.isHidden) {
                            existingView.moveToFirst()
                        } else {
                            existingView.showWindow()
                        }
                        mFreeformViews.removeAll { it.isDestroy }
                        return START_STICKY
                    }
                }

                // Cek batas maksimal floating window
                val activeCount = mFreeformViews.count { !it.isDestroy }
                val maxWindows = sp.getInt(PREF_MAX_WINDOWS, DEFAULT_MAX_WINDOWS)
                if (activeCount >= maxWindows) {
                    android.widget.Toast.makeText(
                        this,
                        "Maksimal $maxWindows floating window aktif. Tutup salah satu dulu.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return START_NOT_STICKY
                }

                val virtualDisplay = createVirtualDisplay()
                val freeformView = FreeformView(config, this, virtualDisplay, mScreenListener)
                freeformView.initSystemService()
                freeformView.initConfig()
                freeformView.initView()

                val parcelable: Parcelable? = config.intent
                val componentName: ComponentName? = config.componentName
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(virtualDisplay.display.displayId)
                var result = -1

                if (parcelable is Intent) {
                    parcelable.flags = parcelable.flags or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    result = activityManager.startActivityAsUserWithFeature(
                        null, SHELL, null, parcelable,
                        parcelable.type, null, null, 0, 0,
                        null, options.toBundle(), config.userId
                    )
                } else if (parcelable is PendingIntent) {
                    //q-fix: PendingIntent (notifikasi) harus diprioritaskan & hanya dikirim sekali.
                    //Sebelumnya ada di dalam branch componentName sehingga dua activity ikut terbuka.
                    val pendingIntentHidden = Refine.unsafeCast<PendingIntentHidden>(parcelable)
                    val activityOptionsHidden = Refine.unsafeCast<ActivityOptionsHidden>(options)
                        .setCallerDisplayId(virtualDisplay.display.displayId)
                    result = activityManager.sendIntentSender(
                        pendingIntentHidden.target, pendingIntentHidden.whitelistToken, 0, null,
                        null, null, null, activityOptionsHidden.toBundle()
                    )
                } else if (componentName != null) {
                    val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                        component = componentName
                        setPackage(componentName.packageName)
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        this.flags = this.flags or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    }
                    result = activityManager.startActivityAsUserWithFeature(
                        null, SHELL, null, launchIntent,
                        launchIntent.type, null, null, 0, 0,
                        null, options.toBundle(), config.userId
                    )
                }

                if (result < 0) {
                    freeformView.destroy()
                    virtualDisplay.release()
                    return START_NOT_STICKY
                }

                mFreeformViews.add(freeformView)
                freeformView.showWindow()
            }

            ACTION_CALL_INTENT -> {
                val activeView = mFreeformViews.lastOrNull { !it.isDestroy } ?: return START_NOT_STICKY
                val parcelable: Parcelable? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, activeView.displayId)
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                if (parcelable is Intent) {
                    parcelable.flags = parcelable.flags or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    activityManager.startActivityAsUserWithFeature(
                        null, SHELL, null, parcelable,
                        parcelable.type, null, null, 0, 0,
                        null, options.toBundle(), 0
                    )
                }
            }

            ACTION_DESTROY_FREEFORM -> {
                mFreeformViews.lastOrNull { !it.isDestroy }?.destroy()
            }
        }

        mFreeformViews.removeAll { it.isDestroy }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mFreeformViews.forEach { it.destroy() }
        mFreeformViews.clear()
        mScreenListener.unregisterListener()
    }

    override fun onScreenOn() {}

    override fun onScreenOff() {
        mFreeformViews.removeAll { it.isDestroy }
        // Hanya stop kalau setting auto_close_screen_off aktif
        // Kalau tidak, biarkan window tetap jalan di background
        if (sp.getBoolean("auto_close_screen_off", false)) {
            mFreeformViews.forEach { it.destroy() }
            mFreeformViews.clear()
            stopSelf()
        }
    }

    override fun onUserPresent() {}

    companion object {
        const val SHELL = "com.android.shell"
        const val ACTION_START_INTENT = "com.sunshine.freeform.action.start.intent"
        const val ACTION_CALL_INTENT = "com.sunshine.freeform.action.call.intent"
        const val ACTION_DESTROY_FREEFORM = "com.sunshine.freeform.action.destroy.freeform"
        const val EXTRA_DISPLAY_ID = "com.sunshine.freeform.action.intent.display.id"
        const val PREF_MAX_WINDOWS = "max_freeform_windows"
        const val DEFAULT_MAX_WINDOWS = 5
    }
}
